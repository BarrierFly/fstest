package fstest.analysis;

import fstest.config.FstestConfig;
import fstest.record.CapturedDispatch;
import fstest.record.CaptureSession;
import fstest.record.FstEvent;
import fstest.record.Markers;
import fstest.record.RecorderHub;
import fstest.record.RootDispatch;
import fstest.sim.RegionSnapshot;
import fstest.analysis.ReplayEngine.RunOutcome;
import fstest.analysis.ReplayEngine.RunStats;
import fstest.sim.SimLevel;
import fstest.sim.SimServer;
import fstest.transform.Symmetry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The timed-mode ("sim timed") replay engine: runs the simulated space inside
 * a simulatica-style real {@link ServerLevel} hosted by the {@link SimServer}.
 *
 * Differences from the instant engine ({@link ReplayEngine}, chunkless world):
 * the operation's root setBlocks are applied through the REAL setBlock path,
 * so neighbour cascades, shape updates, removal hooks
 * ({@code affectNeighborsAfterRemoval}) and block events all happen natively;
 * after the replay the level actually runs {@code simTicks} game ticks
 * (scheduled block/fluid ticks, block events, block entity ticking) - all
 * vanilla code, on a void world whose only content is the copied region.
 *
 * Per run the level is reset (previous run's box cleared with raw flags), the
 * transformed snapshot is copied in, the shared clock is aligned with the
 * snapshot, and the whole multi-tick stream is recorded through the same
 * {@link RecorderHub} session machinery as reality (the capture mixins fire on
 * the simulated ServerLevel exactly like on the real one).
 */
public final class TimedReplayEngine
{
	private TimedReplayEngine()
	{
	}

	public static RunOutcome run(ServerLevel real, MinecraftServer server, RegionSnapshot snap,
	                             Symmetry symmetry, BlockPos offset, String label, CaptureSession realSession,
	                             int simTicks)
	{
		long startNanos = System.nanoTime();
		SimServer simServer;
		try
		{
			simServer = SimServer.getOrCreate(server);
		}
		catch (Exception e)
		{
			throw new IllegalStateException("failed to boot the simulation server", e);
		}
		SimLevel level = simServer.levelFor(real.dimension());

		// ------------------------------------------------------------------
		// Region geometry: snapshot bounds (real space) -> transform -> sim box
		// ------------------------------------------------------------------
		// The 8 orthogonal symmetries map the axis-aligned box onto an
		// axis-aligned box; transforming the two diagonal corners and taking
		// axis-wise extremes yields the transformed bounds.
		BlockPos cornerA = ReplayEngine.mapRelPos(snap.anchor, offset, symmetry,
				RegionSnapshot.subtract(snap.boundsMin(), snap.anchor));
		BlockPos cornerB = ReplayEngine.mapRelPos(snap.anchor, offset, symmetry,
				RegionSnapshot.subtract(snap.boundsMax(), snap.anchor));
		BlockPos simMin = minOf(cornerA, cornerB);
		BlockPos simMax = maxOf(cornerA, cornerB);

		// Force-load only the new box (+ margin). The previous run's box is
		// cleared separately below: its chunks stay ticket-held until the
		// analysis ends (finishAnalysis), so they never need re-loading here -
		// and the AABB union of two distant P/PD boxes would force-load every
		// chunk in between, which for dimension-wide offsets is billions of
		// chunks (the heap-exhausting freeze this replaces).
		simServer.forceAndPump(level, simMin, simMax);
		BlockPos clearMin = level.fstest$lastClearedMin();
		BlockPos clearMax = level.fstest$lastClearedMax();
		if (clearMin != null)
		{
			level.fstest$clearBox(clearMin, clearMax);
		}

		// ------------------------------------------------------------------
		// Environment: clock, gamerules, random (no recording session yet, so
		// copying pre-existing state emits no creation events)
		// ------------------------------------------------------------------
		level.fstest$setSimClock(snap.gameTime, snap.dayTime);
		simServer.getWorldData().getGameRules().setAll(real.getGameRules(), simServer);
		level.random.setSeed(snap.seedKnown ? snap.realRandomSeed : 0x9E3779B97F4A7C15L);

		for (Map.Entry<BlockPos, BlockState> entry : snap.states.entrySet())
		{
			BlockPos pos = ReplayEngine.mapRelPos(snap.anchor, offset, symmetry, entry.getKey());
			level.setBlock(pos, symmetry.applyToState(entry.getValue()), SimLevel.RAW_FLAGS, 0);
		}
		for (Map.Entry<BlockPos, CompoundTag> entry : snap.blockEntities.entrySet())
		{
			BlockPos pos = ReplayEngine.mapRelPos(snap.anchor, offset, symmetry, entry.getKey());
			BlockEntity be = BlockEntity.loadStatic(pos, level.getBlockState(pos), entry.getValue(),
					server.registryAccess());
			if (be != null)
			{
				level.setBlockEntity(be);
			}
		}
		for (ScheduledTick<Block> tick : snap.blockTicks)
		{
			level.getBlockTicks().schedule(new ScheduledTick<>(tick.type(),
					ReplayEngine.mapRelPos(snap.anchor, offset, symmetry, tick.pos()),
					tick.triggerTick(), tick.priority(), tick.subTickOrder()));
		}
		for (ScheduledTick<Fluid> tick : snap.fluidTicks)
		{
			level.getFluidTicks().schedule(new ScheduledTick<>(tick.type(),
					ReplayEngine.mapRelPos(snap.anchor, offset, symmetry, tick.pos()),
					tick.triggerTick(), tick.priority(), tick.subTickOrder()));
		}
		for (BlockEventData event : snap.blockEvents)
		{
			// direct set insertion: blockEvent() would record a creation event
			level.fstest$addBlockEvent(new BlockEventData(
					ReplayEngine.mapRelPos(snap.anchor, offset, symmetry, event.pos()),
					event.block(), event.paramA(), event.paramB()));
		}
		level.fstest$rememberClearedBox(simMin, simMax);

		// ------------------------------------------------------------------
		// Recording session (same machinery as the instant engine)
		// ------------------------------------------------------------------
		CaptureSession session = RecorderHub.push(level);
		try
		{
			Map<BlockPos, Markers.Subscription> remapped = new HashMap<>();
			for (Map.Entry<BlockPos, Markers.Subscription> entry : realSession.getMarkerCacheView().entrySet())
			{
				remapped.put(ReplayEngine.mapPos(snap.anchor, offset, symmetry, entry.getKey()), entry.getValue());
			}
			session.prePopulateMarkerCache(remapped);
			Map<BlockPos, Markers.Subscription> targetSeed = new HashMap<>();
			for (BlockPos realTarget : FstestConfig.INSTANCE.targets().keySet())
			{
				Markers.targetSubscription(realTarget).ifPresent(sub ->
						targetSeed.put(ReplayEngine.mapPos(snap.anchor, offset, symmetry, realTarget), sub));
			}
			session.prePopulateMarkerCache(targetSeed);

			int rootsApplied = 0;
			List<RootDispatch> dispatches = realSession.rootDispatches;
			int cursor = 0;
			while (cursor < dispatches.size() && dispatches.get(cursor).rootIndex() < 0)
			{
				replayDispatch(level, dispatches.get(cursor), snap, offset, symmetry);
				cursor++;
			}
			// The root setBlocks go through the REAL setBlock path: removal
			// hooks run natively in the simulated world (no captured side
			// effects needed here), cascades and shape updates are vanilla.
			for (int i = 0; i < realSession.rootChanges.size(); i++)
			{
				FstEvent.BlockChange root = realSession.rootChanges.get(i);
				BlockPos simPos = ReplayEngine.mapPos(snap.anchor, offset, symmetry, root.pos());
				if (level.setBlock(simPos, symmetry.applyToState(root.newState()), root.flags(), 512))
				{
					rootsApplied++;
				}
				while (cursor < dispatches.size() && dispatches.get(cursor).rootIndex() == i)
				{
					replayDispatch(level, dispatches.get(cursor), snap, offset, symmetry);
					cursor++;
				}
			}
			while (cursor < dispatches.size())
			{
				replayDispatch(level, dispatches.get(cursor), snap, offset, symmetry);
				cursor++;
			}

			// Handler-side scheduled ticks the replay cannot regenerate (e.g. a
			// button's release): scheduling them through the level's real tick
			// lists lets the capture mixin record them exactly like natural
			// creations (vanilla (type, position) dedup included).
			for (FstEvent.SchedTickCreate created : realSession.createdTicks)
			{
				BlockPos simPos = ReplayEngine.mapPos(snap.anchor, offset, symmetry, created.pos());
				if (level.getBlockTicks().hasScheduledTick(simPos, created.block()))
				{
					continue; // naturally (re)generated during the replay
				}
				level.getBlockTicks().schedule(new ScheduledTick<>(created.block(), simPos,
						level.getGameTime() + created.delay(), created.priority(), subTickOrder()));
			}

			int preTickEvents = session.events.size();
			List<Integer> boundaries = simTicks > 0
					? level.fstest$runSimTicks(simTicks, session.events::size)
					: List.of();

			// mismatch positions are reported in real coordinates; the timed
			// engine has no captured side-effect replay, so none are produced
			ReplayEngine.SimPhaseStats simPhase = simTicks > 0
					? new ReplayEngine.SimPhaseStats(boundaries.size(), 0, 0, 0, 0, List.of())
					: ReplayEngine.SimPhaseStats.NONE;
			RunStats stats = new RunStats(rootsApplied, realSession.rootChanges.size(),
					level.fstest$setBlockCalls(), level.fstest$neighborUpdateDispatches(),
					session.createdBlockEvents.size(), session.createdTicks.size(), session.events.size(),
					List.of(), System.nanoTime() - startNanos, preTickEvents, List.copyOf(boundaries), simPhase);
			return new RunOutcome(label,
					ReplayEngine.canonicalize(session.events, snap.anchor, offset, symmetry), stats, offset);
		}
		finally
		{
			RecorderHub.pop(session);
		}
	}

	/** Releases every chunk ticket the timed engine took during this analysis. */
	public static void finishAnalysis()
	{
		SimServer simServer = SimServer.running();
		if (simServer != null)
		{
			simServer.releaseAll();
		}
	}

	/** Re-issues one captured out-of-band dispatch through the real ServerLevel methods. */
	private static void replayDispatch(SimLevel level, RootDispatch dispatch,
	                                    RegionSnapshot snap, BlockPos offset, Symmetry symmetry)
	{
		BlockPos simPos = ReplayEngine.mapPos(snap.anchor, offset, symmetry, dispatch.pos());
		Direction exceptDir = dispatch.exceptDir() == null ? null
				: symmetry.applyToDirection(dispatch.exceptDir());
		level.fstest$issueDispatch(dispatch.kind(), simPos, dispatch.block(), exceptDir,
				dispatch.orientation(), dispatch.movedByPiston());
	}

	private static BlockPos minOf(BlockPos a, BlockPos b)
	{
		return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
				Math.min(a.getZ(), b.getZ()));
	}

	private static BlockPos maxOf(BlockPos a, BlockPos b)
	{
		return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
				Math.max(a.getZ(), b.getZ()));
	}

	/** Monotonic sub-tick order for backfilled ticks: they drain after natural ones within a phase. */
	private static final java.util.concurrent.atomic.AtomicLong SUB_ORDER =
			new java.util.concurrent.atomic.AtomicLong(10_000_000L);

	private static long subTickOrder()
	{
		return SUB_ORDER.incrementAndGet();
	}
}
