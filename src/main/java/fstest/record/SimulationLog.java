package fstest.record;

import fstest.FstestMod;
import fstest.analysis.DiffEngine;
import fstest.analysis.ReportFormatter;
import fstest.analysis.ReplayEngine;
import fstest.analysis.ReplayEngine.RunOutcome;
import fstest.capture.TriggerCapture;
import fstest.config.FstestConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persists one text report per accepted analysis window under
 * {@code <runDir>/fstest-logs/}. Every event recorded on the monitored
 * positions is written, with consecutive duplicates collapsed to a single
 * line plus a {@code ×N} count. JSONL output is planned for v2.
 */
public final class SimulationLog
{
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT);
	private static final int MAX_HEADER_LABELS = 6;
	/** How many mismatch positions the per-run stats line spells out before eliding. */
	private static final int MAX_MISMATCH_POSITIONS = 4;
	/** How many per-tick sections the baseline breakdown spells out before collapsing. */
	private static final int MAX_TICK_SECTIONS = 8;

	private SimulationLog()
	{
	}

	/**
	 * Write a single analysis window to disk. The file is closed before this
	 * method returns; failures are logged and swallowed (writing a log must
	 * never crash the analysis path).
	 *
	 * @param mtrMode    whether the runs executed simulated game ticks (MTR mode);
	 *                   the comparison reference is then the baseline run, not reality
	 * @param totalNanos wall-clock duration of the whole analysis (runs + report)
	 */
	public static void write(ServerLevel level, @Nullable ServerPlayer player, TriggerCapture.OpKind kind,
	                         BlockPos anchor, CaptureSession realSession, FstestConfig cfg, String snapshotSource,
	                         @Nullable RunOutcome baseline, List<RunOutcome> runs, boolean mtrMode, long totalNanos)
	{
		Path file;
		try
		{
			Path dir = baseDir();
			Files.createDirectories(dir);
			String playerTag = player != null ? sanitize(player.getName().getString()) : "console";
			String stamp = LocalDateTime.now().format(STAMP);
			String name = String.format(Locale.ROOT, "%s_%s_at_%d_%d_%d_%s.txt",
					stamp, kind, anchor.getX(), anchor.getY(), anchor.getZ(), playerTag);
			file = dir.resolve(name);
		}
		catch (IOException e)
		{
			FstestMod.LOGGER.error("[fstest] could not prepare simulation log directory", e);
			return;
		}

		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
		{
			writeHeader(w, level, player, kind, anchor, cfg, realSession, snapshotSource, baseline, runs,
					mtrMode, totalNanos);
			writeEvents(w, mtrMode ? "REAL (instant window)" : "REAL", realSession.events);
			if (baseline != null)
			{
				if (mtrMode)
				{
					writeBaselineBreakdown(w, baseline, realSession);
				}
				else
				{
					writeEvents(w, "BASELINE (identity, 0° no mirror)", baseline.events());
				}
				writeDiff(w, mtrMode ? "BASELINE (instant part) vs REAL" : "BASELINE vs REAL",
						realSession.events,
						mtrMode ? baseline.events().subList(0, baseline.stats().preTickEvents()) : baseline.events());
			}
			for (ReplayEngine.RunOutcome run : runs)
			{
				writeEvents(w, "RUN: " + run.label(), run.events());
				writeRunStats(w, run);
				writeDiff(w, mtrMode ? "RUN " + run.label() + " vs BASELINE" : "RUN " + run.label() + " vs REAL",
						mtrMode && baseline != null ? baseline.events() : realSession.events, run.events());
			}
			ReportFormatter.Aggregation agg = ReportFormatter.aggregate(
					mtrMode && baseline != null ? baseline.events() : realSession.events, runs);
			writeSummary(w, agg);
			w.flush();
		}
		catch (IOException e)
		{
			FstestMod.LOGGER.error("[fstest] failed to write simulation log " + file, e);
			return;
		}

		if (player != null)
		{
			// clickable log path (open_file click event; plain text fallback in the log)
			player.sendSystemMessage(Component.literal("[fstest] full log: " + file.toAbsolutePath())
					.withStyle(ChatFormatting.DARK_GRAY)
					.withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenFile(file.toFile()))));
		}
		FstestMod.LOGGER.info("[fstest] simulation log written to {}", file.toAbsolutePath());
	}

	private static Path baseDir()
	{
		return Paths.get(System.getProperty("user.dir", "."), "fstest-logs");
	}

	private static String sanitize(String s)
	{
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-')
			{
				out.append(c);
			}
			else
			{
				out.append('_');
			}
		}
		return out.length() == 0 ? "player" : out.toString();
	}

	private static void writeHeader(BufferedWriter w, ServerLevel level, @Nullable ServerPlayer player,
	                                TriggerCapture.OpKind kind, BlockPos anchor, FstestConfig cfg,
	                                CaptureSession realSession, String snapshotSource,
	                                @Nullable RunOutcome baseline,
	                                List<RunOutcome> runs, boolean mtrMode, long totalNanos) throws IOException
	{
		String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		w.write("=== Fengshui Tester simulation log ==="); w.newLine();
		w.write("mod version      : " + modVersion()); w.newLine();
		w.write("timestamp        : " + ts); w.newLine();
		w.write("player           : " + (player != null ? player.getName().getString() : "<console>")); w.newLine();
		w.write("operation        : " + kind); w.newLine();
		w.write("anchor           : " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ()); w.newLine();
		w.write("dimension        : " + level.dimension().identifier()); w.newLine();
		w.write("game time        : " + level.getGameTime()); w.newLine();
		w.write("mode             : " + cfg.mode()); w.newLine();
		w.write("color filter     : " + (cfg.color().isEmpty() ? "all" : cfg.color().get().getName())); w.newLine();
		w.write("count d/p/pd     : " + cfg.countD() + " / " + cfg.countP() + " / " + cfg.countPd()); w.newLine();
		w.write("range            : " + (cfg.isRangeUnlimited() ? "unlimited" : Integer.toString(cfg.range()))); w.newLine();
		w.write("pstrategy        : " + cfg.strategy()); w.newLine();
		w.write("updates          : " + (cfg.updates() ? "on" : "off")); w.newLine();
		w.write("duplications     : " + (cfg.duplications() ? "on" : "off")); w.newLine();
		w.write("sim mode         : " + (mtrMode
				? "timed (" + cfg.simTicks() + " simulated ticks per run)"
				: "instant")); w.newLine();
		w.write("test area        : " + areaText(cfg)); w.newLine();
		w.write("targets          : " + cfg.targets().size()); w.newLine();
		w.write("real events      : " + realSession.events.size()); w.newLine();
		w.write("monitored roots  : " + realSession.rootChanges.size()); w.newLine();
		w.write("tick attempts    : " + realSession.createdTicks.size()
				+ " (" + recordedOf(realSession.events, FstEvent.SchedTickCreate.class) + " recorded)"); w.newLine();
		w.write("event attempts   : " + realSession.createdBlockEvents.size()
				+ " (" + recordedOf(realSession.events, FstEvent.BlockEventCreate.class) + " recorded)"); w.newLine();
		w.write("snapshot         : " + snapshotSource); w.newLine();
		if (baseline != null)
		{
			w.write("baseline replay  : " + baseline.stats().rootsApplied() + "/" + baseline.stats().rootsTotal()
					+ " roots, setBlocks " + baseline.stats().setBlockCalls()
					+ ", nuDispatches " + baseline.stats().neighborUpdateDispatches()
					+ ", eventAttempts " + baseline.stats().createdBlockEvents()
					+ ", tickAttempts " + baseline.stats().createdTicks()
					+ ", rawEvents " + baseline.stats().rawEvents()); w.newLine();
			DiffEngine.Diff baselineDiff = DiffEngine.diff(realSession.events,
					mtrMode ? baseline.events().subList(0, baseline.stats().preTickEvents()) : baseline.events());
			w.write("baseline match   : " + (baselineDiff.isEmpty() ? "OK" : "DISTORTION (see BASELINE vs REAL)")); w.newLine();
		}
		w.write("simulations      : " + runs.size()); w.newLine();
		w.write("analysis time    : " + formatMillis(totalNanos)); w.newLine();
		w.newLine();
	}

	private static String areaText(FstestConfig cfg)
	{
		FstestConfig.Area area = cfg.scopedArea().orElse(null);
		if (area != null)
		{
			return "area '" + cfg.scopeArea() + "' "
					+ area.pos1().getX() + " " + area.pos1().getY() + " " + area.pos1().getZ()
					+ " -> " + area.pos2().getX() + " " + area.pos2().getY() + " " + area.pos2().getZ();
		}
		return "radius " + (cfg.isRangeUnlimited() ? "unlimited" : Integer.toString(cfg.range()));
	}

	private static String formatMillis(long nanos)
	{
		return String.format(Locale.ROOT, "%.1f ms", nanos / 1_000_000.0);
	}

	private static String modVersion()
	{
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("fstest")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	/**
	 * MTR mode: writes the baseline's full multi-tick stream with a per-tick
	 * breakdown (bounded by {@link #MAX_TICK_SECTIONS}); the first section
	 * holds the instant-window events, each further one one simulated tick.
	 */
	private static void writeBaselineBreakdown(BufferedWriter w, RunOutcome baseline,
	                                           CaptureSession realSession) throws IOException
	{
		List<FstEvent> events = baseline.events();
		List<Integer> boundaries = baseline.stats().tickBoundaries();
		int instant = Math.min(baseline.stats().preTickEvents(), events.size());
		writeEvents(w, "BASELINE (identity, 0° no mirror, instant part)", events.subList(0, instant));
		int from = instant;
		int nextTick = 1;
		for (; nextTick <= boundaries.size(); nextTick++)
		{
			if (nextTick > MAX_TICK_SECTIONS)
			{
				break;
			}
			int to = Math.min(boundaries.get(nextTick - 1), events.size());
			writeEvents(w, "BASELINE sim tick #" + nextTick, events.subList(from, to));
			from = to;
		}
		if (from < events.size())
		{
			writeEvents(w, "BASELINE remaining (from sim tick #" + nextTick + ", "
					+ (events.size() - from) + " event(s))", events.subList(from, events.size()));
		}
	}

	private static void writeEvents(BufferedWriter w, String sectionTitle, List<FstEvent> events) throws IOException
	{
		w.write("--- " + sectionTitle + " (" + events.size() + " event(s)) ---");
		w.newLine();
		Map<String, DedupEntry> byKey = new LinkedHashMap<>();
		for (FstEvent e : events)
		{
			String key = e.key();
			DedupEntry entry = byKey.computeIfAbsent(key, k -> new DedupEntry(formatEvent(e)));
			entry.count++;
		}
		for (DedupEntry entry : byKey.values())
		{
			w.write(entry.count > 1 ? (entry.line + "  ×" + entry.count) : entry.line);
			w.newLine();
		}
		w.newLine();
	}

	private static void writeRunStats(BufferedWriter w, ReplayEngine.RunOutcome run) throws IOException
	{
		ReplayEngine.RunStats s = run.stats();
		StringBuilder line = new StringBuilder(96);
		line.append("    stats: roots ").append(s.rootsApplied()).append('/').append(s.rootsTotal())
				.append(", setBlocks ").append(s.setBlockCalls())
				.append(", nuDispatches ").append(s.neighborUpdateDispatches())
				.append(", eventAttempts ").append(s.createdBlockEvents())
				.append(", tickAttempts ").append(s.createdTicks())
				.append(", rawEvents ").append(s.rawEvents())
				.append(", time ").append(formatMillis(s.runNanos()));
		if (!run.offset().equals(BlockPos.ZERO))
		{
			line.append(", offset ").append(run.offset().getX())
					.append(' ').append(run.offset().getY())
					.append(' ').append(run.offset().getZ());
		}
		if (!s.sideEffectContextMismatches().isEmpty())
		{
			// the removal side-effect replay landed in a different cascade context
			// than the captured one, so its relative timing is not guaranteed
			line.append(", sideEffectCtxMismatch ").append(s.sideEffectContextMismatches().size()).append(" @");
			appendPositions(line, s.sideEffectContextMismatches());
		}
		ReplayEngine.SimPhaseStats sim = s.simPhase();
		if (sim != null && sim.ticks() > 0)
		{
			line.append(", simTicks ").append(sim.ticks())
					.append(", blockEventsExecuted ").append(sim.executedBlockEvents());
			if (sim.unexecutedBlockTicks() > 0 || sim.unexecutedFluidTicks() > 0 || sim.tickFailures() > 0)
			{
				line.append(", unexecutedBlockTicks ").append(sim.unexecutedBlockTicks())
						.append(", unexecutedFluidTicks ").append(sim.unexecutedFluidTicks())
						.append(", tickFailures ").append(sim.tickFailures());
				if (!sim.unexecutedPositions().isEmpty())
				{
					line.append(" @");
					appendPositions(line, sim.unexecutedPositions());
				}
			}
		}
		w.write(line.toString());
		w.newLine();
	}

	private static void appendPositions(StringBuilder line, List<BlockPos> positions)
	{
		int shown = 0;
		for (BlockPos pos : positions)
		{
			if (shown++ >= MAX_MISMATCH_POSITIONS)
			{
				line.append(" ...");
				return;
			}
			line.append(' ').append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ());
		}
	}

	private static void writeDiff(BufferedWriter w, String sectionTitle, List<FstEvent> real, List<FstEvent> sim) throws IOException
	{
		DiffEngine.Diff diff = DiffEngine.diff(real, sim);
		if (diff.isEmpty())
		{
			w.write("--- " + sectionTitle + " (matches reference exactly) ---");
			w.newLine();
			w.newLine();
			return;
		}
		if (diff.orderOnly())
		{
			// same events, different order: a legitimate position/orientation
			// dependent delivery order, not an extra/missing event
			w.write("--- " + sectionTitle + " (order change only: "
					+ diff.plus().size() + " event(s) in a different order) ---");
			w.newLine();
		}
		else
		{
			w.write("--- " + sectionTitle + " (+ " + diff.plus().size()
					+ " sim extra, - " + diff.minus().size() + " sim missing) ---");
			w.newLine();
		}
		for (String p : diff.plus())
		{
			w.write("+ " + p); w.newLine();
		}
		for (String m : diff.minus())
		{
			w.write("- " + m); w.newLine();
		}
		w.newLine();
	}

	private static long recordedOf(List<FstEvent> events, Class<? extends FstEvent> type)
	{
		return events.stream().filter(type::isInstance).count();
	}

	private static void writeSummary(BufferedWriter w, ReportFormatter.Aggregation agg) throws IOException
	{
		w.write("--- aggregated summary ---"); w.newLine();
		w.write("total simulations : " + agg.total()); w.newLine();
		w.write("matched reference : " + agg.identical()); w.newLine();
		w.write("distinct outcomes : " + agg.outcomes().size()); w.newLine();
		w.newLine();
		// deterministic order: insertion order (which mirrors runs order)
		int shown = 0;
		for (Map.Entry<String, ReportFormatter.OutcomeAgg> e : agg.outcomes().entrySet())
		{
			ReportFormatter.OutcomeAgg o = e.getValue();
			List<String> labels = o.labels();
			String labelField = labels.size() < MAX_HEADER_LABELS
					? String.join(", ", labels)
					: String.join(", ", labels.subList(0, MAX_HEADER_LABELS)) + ", ...";
			w.write("=== " + o.count() + "x [" + labelField + "] ==="); w.newLine();
			if (o.sample().orderOnly())
			{
				w.write("(order change only: the same events in a different order)"); w.newLine();
			}
			writeGroupedOps(w, o.sample());
			shown++;
		}
	}

	/** Summary diff lines grouped by monitored position (mirrors the chat report). */
	private static void writeGroupedOps(BufferedWriter w, DiffEngine.Diff diff) throws IOException
	{
		Map<String, List<String[]>> byPosition = new LinkedHashMap<>();
		for (String[] entry : diffLines(diff))
		{
			byPosition.computeIfAbsent(positionOf(entry[1]), k -> new ArrayList<>()).add(entry);
		}
		for (Map.Entry<String, List<String[]>> entry : byPosition.entrySet())
		{
			if (byPosition.size() > 1)
			{
				w.write("@ " + entry.getKey()); w.newLine();
			}
			for (String[] line : entry.getValue())
			{
				w.write(line[0] + " " + line[1]); w.newLine();
			}
		}
		w.newLine();
	}

	private static List<String[]> diffLines(DiffEngine.Diff diff)
	{
		List<String[]> lines = new ArrayList<>();
		for (String p : diff.plus())
		{
			lines.add(new String[]{"+", p});
		}
		for (String m : diff.minus())
		{
			lines.add(new String[]{"-", m});
		}
		return lines;
	}

	private static String positionOf(String displayLine)
	{
		int separator = displayLine.indexOf(" | ");
		return separator >= 0 ? displayLine.substring(0, separator) : displayLine;
	}

	private static String formatEvent(FstEvent event)
	{
		return event.pos().toShortString() + " | " + event.signature();
	}

	private static final class DedupEntry
	{
		final String line;
		int count = 0;

		DedupEntry(String line)
		{
			this.line = line;
		}
	}

	/** Public so server commands (planned v2) can resolve the same directory. */
	public static Path resolveLogDir()
	{
		return baseDir();
	}
}
