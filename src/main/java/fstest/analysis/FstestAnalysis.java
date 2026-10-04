package fstest.analysis;

import fstest.analysis.ReplayEngine.RunOutcome;
import fstest.capture.TriggerCapture;
import fstest.config.FstestConfig;
import fstest.record.CaptureSession;
import fstest.record.FstEvent;
import fstest.sim.RegionSnapshot;
import fstest.transform.OffsetSampler;
import fstest.transform.Symmetry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.dimension.DimensionType;

import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates one analysis cycle after an accepted operation:
 * snapshot -> baseline self-check -> D/P/PD simulation batches -> report.
 *
 * Instant mode (MTR off): every run replays the operation's synchronous
 * window; runs diff against the real recording.
 *
 * MTR mode ({@code /fstest mtr <ticks>}): every run additionally executes
 * {@code ticks} game ticks inside the simulated space (scheduled ticks, block
 * events, block entity ticking - the real world is never advanced). The
 * baseline self-check still compares the identity run's pre-tick events
 * against the real instant-window stream; the transformed runs then diff
 * against the BASELINE RUN's full multi-tick stream, since there is no
 * reality recording beyond the instant window.
 */
public final class FstestAnalysis
{
	private static final AtomicLong OP_COUNTER = new AtomicLong();

	private FstestAnalysis()
	{
	}

	public static void analysisCrashed(@Nullable ServerPlayer player)
	{
		ReportFormatter.error(player, "fstest.analysis.crashed");
	}

	public static void analyze(ServerLevel level, @Nullable ServerPlayer player, TriggerCapture.OpKind kind,
	                           BlockPos anchor, CaptureSession realSession, @Nullable RegionSnapshot preOpSnapshot)
	{
		FstestConfig cfg = FstestConfig.INSTANCE;

		if (realSession.rootChanges.isEmpty() && realSession.events.isEmpty())
		{
			ReportFormatter.message(player, "fstest.analysis.no_events");
			return;
		}
		if (realSession.events.isEmpty())
		{
			// something changed, but no wool-marked monitor subscribed under the selected color
			ReportFormatter.message(player, "fstest.analysis.no_markers");
			return;
		}

		MinecraftServer server = level.getServer();
		if (server == null)
		{
			return;
		}

		boolean timed = cfg.isTimed();
		int simTicks = timed ? cfg.simTicks() : 0;

		long analysisStart = System.nanoTime();
		RegionSnapshot snapshot;
		try
		{
			// Must be pre-operation: the replay re-applies the operation's root
			// setBlocks, which only mutate anything against the state as it was
			// before the operation ran. The lazy capture fires at the window's
			// first root change (and already honours the scope selection);
			// the fallback here only covers ops that never reached one (nothing
			// to replay) or a failed pre-capture.
			if (preOpSnapshot != null)
			{
				snapshot = preOpSnapshot;
			}
			else
			{
				// scope: a named test area takes precedence over the radius
				snapshot = cfg.scopedArea()
						.map(area -> RegionSnapshot.capture(level, server, anchor, area))
						.orElseGet(() -> RegionSnapshot.capture(level, server, anchor, cfg.effectiveRange()));
			}
		}
		catch (Throwable t)
		{
			ReportFormatter.error(player, "fstest.analysis.snapshot_failed");
			fstest.FstestMod.LOGGER.error("[fstest] snapshot failed", t);
			return;
		}
		String snapshotSource = preOpSnapshot != null ? "pre-operation" : "post-operation fallback";

		Progress progress = new Progress(player, plannedExecutions(cfg));
		progress.start();

		try
		{
			// Baseline self-check: the identity transform must reproduce reality
			// bit-for-bit over the instant window, otherwise the simulator is
			// distorted and comparison output would be meaningless.
			RunOutcome baseline = timed
					? TimedReplayEngine.run(level, server, snapshot,
							Symmetry.IDENTITY, BlockPos.ZERO, "baseline", realSession, simTicks)
					: ReplayEngine.run(level, server, snapshot,
							Symmetry.IDENTITY, BlockPos.ZERO, "baseline", realSession);
			progress.tick();
			List<FstEvent> baselineInstant = timed
					? baseline.events().subList(0, baseline.stats().preTickEvents())
					: baseline.events();
			DiffEngine.Diff baselineDiff = DiffEngine.diff(realSession.events, baselineInstant);
			if (!baselineDiff.isEmpty())
			{
				fstest.record.SimulationLog.write(level, player, kind, anchor, realSession, cfg, snapshotSource,
						baseline, List.of(), timed, System.nanoTime() - analysisStart);
				ReportFormatter.reportDistortion(player, baselineDiff);
				return;
			}

			List<ReplayEngine.RunOutcome> runs = new ArrayList<>();
			RandomSource rng = RandomSource.create(seedFor(level, anchor));
			DimensionType dim = level.dimensionType();

			if (cfg.mode().testsDirectionality())
			{
				for (Symmetry symmetry : Symmetry.fullSet())
				{
					if (symmetry == Symmetry.IDENTITY)
					{
						runs.add(baseline); // reuse - identical transform
						continue;
					}
					for (int i = 0; i < cfg.countD(); i++)
					{
						runs.add(runOne(level, server, snapshot, symmetry, BlockPos.ZERO,
								symmetry.label(), realSession, timed, simTicks));
						progress.tick();
					}
				}
			}

			if (cfg.mode().testsPositionality())
			{
				for (int i = 0; i < cfg.countP(); i++)
				{
					BlockPos offset = OffsetSampler.sample(cfg.strategy(), rng, dim, anchor);
					runs.add(runOne(level, server, snapshot, Symmetry.IDENTITY, offset,
							"P#" + (i + 1), realSession, timed, simTicks));
					progress.tick();
				}
			}

			if (cfg.mode() == fstest.config.FstestMode.BOTH)
			{
				Symmetry[] set = Symmetry.fullSet();
				for (int i = 0; i < cfg.countPd(); i++)
				{
					Symmetry symmetry = set[rng.nextInt(set.length)];
					BlockPos offset = OffsetSampler.sample(cfg.strategy(), rng, dim, anchor);
					runs.add(runOne(level, server, snapshot, symmetry, offset,
							"PD#" + (i + 1), realSession, timed, simTicks));
					progress.tick();
				}
			}

			// In timed mode the comparison reference is the baseline simulation's
			// multi-tick stream (reality has no recording beyond the instant
			// window); in instant mode it is the real recording itself.
			List<FstEvent> reference = timed ? baseline.events() : realSession.events;
			ReportFormatter.report(player, kind, reference, runs, timed);
			fstest.record.SimulationLog.write(level, player, kind, anchor, realSession, cfg, snapshotSource,
					baseline, runs, timed, System.nanoTime() - analysisStart);
		}
		finally
		{
			// timed mode: release the chunk tickets the simulated runs took
			if (timed)
			{
				TimedReplayEngine.finishAnalysis();
			}
		}
	}

	/** Dispatches one replay run to the timed engine (real ServerLevel) or the instant engine (chunkless). */
	private static ReplayEngine.RunOutcome runOne(ServerLevel level, MinecraftServer server, RegionSnapshot snapshot,
	                                              Symmetry symmetry, BlockPos offset, String label,
	                                              CaptureSession realSession, boolean timed, int simTicks)
	{
		return timed
				? TimedReplayEngine.run(level, server, snapshot, symmetry, offset, label, realSession, simTicks)
				: ReplayEngine.run(level, server, snapshot, symmetry, offset, label, realSession);
	}

	/**
	 * Total number of replay executions one analysis will perform: the identity
	 * baseline plus every run that is not the reused baseline (the D set's
	 * identity symmetry reuses it rather than replaying again).
	 */
	private static int plannedExecutions(FstestConfig cfg)
	{
		int n = 1; // baseline self-check
		if (cfg.mode().testsDirectionality())
		{
			n += 7 * cfg.countD();
		}
		if (cfg.mode().testsPositionality())
		{
			n += cfg.countP();
		}
		if (cfg.mode() == fstest.config.FstestMode.BOTH)
		{
			n += cfg.countPd();
		}
		return n;
	}

	/** Emits a start notice and throttled progress notices (every 10% or 50 runs, whichever is larger). */
	private static final class Progress
	{
		private final ServerPlayer player;
		private final int total;
		private final int interval;
		private int done;

		Progress(@Nullable ServerPlayer player, int total)
		{
			this.player = player;
			this.total = total;
			this.interval = Math.max(50, (int) Math.ceil(total * 0.10));
		}

		void start()
		{
			ReportFormatter.analysisStart(this.player, this.total);
		}

		void tick()
		{
			this.done++;
			if (this.done < this.total && this.done % this.interval == 0)
			{
				ReportFormatter.analysisProgress(this.player, this.done, this.total);
			}
		}
	}

	private static long seedFor(ServerLevel level, BlockPos anchor)
	{
		long counter = OP_COUNTER.incrementAndGet();
		long time = level.getGameTime();
		return mix(time ^ anchor.asLong() ^ (counter * 0x9E3779B97F4A7C15L));
	}

	private static long mix(long x)
	{
		x ^= x >>> 33;
		x *= 0xFF51AFD7ED558CCDL;
		x ^= x >>> 33;
		x *= 0xC4CEB9FE1A85EC53L;
		x ^= x >>> 33;
		return x;
	}
}
