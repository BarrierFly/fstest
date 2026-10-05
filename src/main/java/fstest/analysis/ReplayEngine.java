package fstest.analysis;

import fstest.config.FstestConfig;
import fstest.record.CapturedDispatch;
import fstest.record.CaptureSession;
import fstest.record.FstEvent;
import fstest.record.Markers;
import fstest.record.RecorderHub;
import fstest.record.RemovalSideEffect;
import fstest.record.RootDispatch;
import fstest.sim.FstestSimWorld;
import fstest.sim.RegionSnapshot;
import fstest.sim.SimLevelData;
import fstest.transform.Symmetry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Builds a simulated space from a {@link RegionSnapshot} under one transform,
 * replays the captured root actions of the operation, backfills handler-side
 * scheduled ticks, and returns the simulated event stream.
 */
public final class ReplayEngine
{
	private ReplayEngine()
	{
	}

	/** Per-phase multi-tick execution counters (MTR mode); all zero in instant mode. */
	public record SimPhaseStats(int ticks, int unexecutedBlockTicks, int unexecutedFluidTicks,
	                            int executedBlockEvents, int tickFailures, List<BlockPos> unexecutedPositions)
	{
		public static final SimPhaseStats NONE =
				new SimPhaseStats(0, 0, 0, 0, 0, List.of());
	}

	/**
	 * Wall-clock split of a timed run, in nanoseconds. Absent in instant mode,
	 * whose whole cost is the replay itself (already in
	 * {@link RunStats#runNanos()}).
	 */
	public record RunTimings(long promoteNanos, long clearNanos, long copyInNanos, long replayNanos,
	                         long simTicksNanos)
	{
		public static final RunTimings NONE = new RunTimings(0, 0, 0, 0, 0);
	}

	/** Replay diagnostics for one run, surfaced in the simulation log header. */
	public record RunStats(int rootsApplied, int rootsTotal, long setBlockCalls, long neighborUpdateDispatches,
	                       int createdBlockEvents, int createdTicks, int rawEvents,
	                       List<BlockPos> sideEffectContextMismatches,
	                       long runNanos, int preTickEvents, List<Integer> tickBoundaries,
	                       SimPhaseStats simPhase, @Nullable RunTimings timings)
	{
	}

	public record RunOutcome(String label, List<FstEvent> events, RunStats stats, BlockPos offset)
	{
	}

	public static BlockPos mapPos(BlockPos anchor, BlockPos offset, Symmetry symmetry, BlockPos realPos)
	{
		BlockPos rel = RegionSnapshot.subtract(realPos, anchor);
		return symmetry.applyToPos(rel).offset(
				anchor.getX() + offset.getX(),
				anchor.getY() + offset.getY(),
				anchor.getZ() + offset.getZ());
	}

	/**
	 * Maps a snapshot-RELATIVE position into simulation space. Snapshot keys are
	 * anchor-relative, so they must not go through {@link #mapPos} (which treats
	 * its input as a real-space position and would subtract the anchor a second
	 * time); the simulated space itself lives in real-absolute coordinates.
	 */
	public static BlockPos mapRelPos(BlockPos anchor, BlockPos offset, Symmetry symmetry, BlockPos rel)
	{
		return symmetry.applyToPos(rel).offset(
				anchor.getX() + offset.getX(),
				anchor.getY() + offset.getY(),
				anchor.getZ() + offset.getZ());
	}

	public static RunOutcome run(ServerLevel real, MinecraftServer server, RegionSnapshot snapshot,
	                             Symmetry symmetry, BlockPos offset, String label, CaptureSession realSession)
	{
		long startNanos = System.nanoTime();
		FstestSimWorld world = buildWorld(real, server, snapshot, symmetry, offset);
		world.setRemovalSideEffects(buildRemovalSideEffects(snapshot.anchor, offset, symmetry, realSession));
		CaptureSession session = RecorderHub.push(world);
		// Share the real session's wool-subscription decisions with the simulated
		// session, remapped into simulation space. Sim positions live on the same
		// absolute grid as real ones, so an unmapped cache would mis-subscribe
		// transformed runs wherever a sim coordinate coincides with a real cached
		// key; the transformed world answers identically at transformed positions.
		if (symmetry.equals(Symmetry.IDENTITY) && offset.equals(BlockPos.ZERO))
		{
			session.prePopulateMarkerCache(realSession.getMarkerCacheView());
		}
		else
		{
			Map<BlockPos, Markers.Subscription> remapped = new HashMap<>();
			for (Map.Entry<BlockPos, Markers.Subscription> entry : realSession.getMarkerCacheView().entrySet())
			{
				remapped.put(mapPos(snapshot.anchor, offset, symmetry, entry.getKey()), entry.getValue());
			}
			session.prePopulateMarkerCache(remapped);
		}
		// Manual targets are registered in real coordinates, so the simulated
		// world's own lookup would miss them wherever the transform moved the
		// block. Seed every target's simulated position explicitly: a target that
		// produced no real event was never cached by the real session, and that is
		// precisely the case (nothing in reality, something under the transform)
		// the tester must not hide.
		Map<BlockPos, Markers.Subscription> targetSeed = new HashMap<>();
		for (BlockPos realTarget : FstestConfig.INSTANCE.targets().keySet())
		{
			Markers.targetSubscription(realTarget).ifPresent(sub ->
					targetSeed.put(mapPos(snapshot.anchor, offset, symmetry, realTarget), sub));
		}
		session.prePopulateMarkerCache(targetSeed);
		try
		{
			int rootsApplied = 0;
			List<RootDispatch> dispatches = realSession.rootDispatches;
			int cursor = 0;
			// dispatches recorded before any root (defensive edge case)
			while (cursor < dispatches.size() && dispatches.get(cursor).rootIndex() < 0)
			{
				replayDispatch(world, dispatches.get(cursor), snapshot, offset, symmetry);
				cursor++;
			}
			for (int i = 0; i < realSession.rootChanges.size(); i++)
			{
				FstEvent.BlockChange root = realSession.rootChanges.get(i);
				BlockPos simPos = mapPos(snapshot.anchor, offset, symmetry, root.pos());
				if (world.setBlock(simPos, symmetry.applyToState(root.newState()), root.flags()))
				{
					rootsApplied++;
				}
				// out-of-band dispatches the operation's code made right after
				// this root's setBlock (e.g. LeverBlock#updateNeighbours) - the
				// cascades they start cannot regenerate from the setBlock alone
				while (cursor < dispatches.size() && dispatches.get(cursor).rootIndex() == i)
				{
					replayDispatch(world, dispatches.get(cursor), snapshot, offset, symmetry);
					cursor++;
				}
			}
			while (cursor < dispatches.size())
			{
				replayDispatch(world, dispatches.get(cursor), snapshot, offset, symmetry);
				cursor++;
			}
			reconcileCreations(snapshot, symmetry, offset, world, session, realSession);
			// Instant mode never executes ticks; the multi-tick stream is the
			// timed engine's (TimedReplayEngine, real ServerLevel) job.
			int preTickEvents = session.events.size();
			// mismatch positions are reported in real coordinates, like every other
			// event in the log, so they line up with the device the user built
			List<BlockPos> sideEffectMismatches = world.sideEffectContextMismatches().stream()
					.map(p -> mapPosInverse(p, snapshot.anchor, offset, symmetry))
					.toList();
			RunStats stats = new RunStats(rootsApplied, realSession.rootChanges.size(),
					world.setBlockCalls(), world.neighborUpdateDispatches(),
					session.createdBlockEvents.size(), session.createdTicks.size(), session.events.size(),
					sideEffectMismatches,
					System.nanoTime() - startNanos, preTickEvents, List.of(), ReplayEngine.SimPhaseStats.NONE,
					RunTimings.NONE);
			// Simulated events live in simulation space (transformed positions,
			// directions and states); map them back onto real coordinates so every
			// run can be diffed against the real stream directly.
			return new RunOutcome(label, canonicalize(session.events, snapshot.anchor, offset, symmetry), stats, offset);
		}
		finally
		{
			RecorderHub.pop(session);
		}
	}

	/**
	 * Maps simulation-space events back onto real-world coordinates (and
	 * inverse-transforms the directions/states they carry) by applying the
	 * inverse of the transform that produced the simulated space.
	 */
	static List<FstEvent> canonicalize(List<FstEvent> events, BlockPos anchor, BlockPos offset, Symmetry symmetry)
	{
		if (symmetry.equals(Symmetry.IDENTITY) && offset.equals(BlockPos.ZERO))
		{
			return events;
		}
		List<FstEvent> out = new ArrayList<>(events.size());
		for (FstEvent event : events)
		{
			BlockPos realPos = mapPosInverse(event.pos(), anchor, offset, symmetry);
			if (event instanceof FstEvent.BlockChange blockChange)
			{
				out.add(new FstEvent.BlockChange(blockChange.seq(), realPos,
						symmetry.applyToStateInverse(blockChange.oldState()),
						symmetry.applyToStateInverse(blockChange.newState()),
						blockChange.flags()));
			}
			else if (event instanceof FstEvent.NeighborUpdate neighborUpdate)
			{
				out.add(new FstEvent.NeighborUpdate(neighborUpdate.seq(), realPos, neighborUpdate.fromBlock(),
						neighborUpdate.kind(),
						neighborUpdate.exceptDir() == null ? null
								: symmetry.applyToDirectionInverse(neighborUpdate.exceptDir())));
			}
			else if (event instanceof FstEvent.SchedTickCreate schedTick)
			{
				out.add(new FstEvent.SchedTickCreate(schedTick.seq(), realPos, schedTick.block(),
						schedTick.delay(), schedTick.priority(), schedTick.success()));
			}
			else if (event instanceof FstEvent.BlockEventCreate blockEvent)
			{
				out.add(new FstEvent.BlockEventCreate(blockEvent.seq(), realPos, blockEvent.block(),
						blockEvent.type(),
						canonicalBlockEventData(blockEvent.block(), blockEvent.data(), symmetry),
						blockEvent.success()));
			}
			else
			{
				out.add(event);
			}
		}
		return out;
	}

	/** Re-issues one captured out-of-band dispatch inside the simulated space. */
	private static void replayDispatch(FstestSimWorld world, RootDispatch dispatch,
	                                    RegionSnapshot snap, BlockPos offset, Symmetry symmetry)
	{
		BlockPos simPos = mapPos(snap.anchor, offset, symmetry, dispatch.pos());
		Direction exceptDir = dispatch.exceptDir() == null ? null : symmetry.applyToDirection(dispatch.exceptDir());
		world.issueDispatch(dispatch.kind(), simPos, dispatch.block(), exceptDir,
				dispatch.orientation(), dispatch.movedByPiston());
	}

	/**
	 * Transforms the removal-hook dispatches captured from reality into
	 * simulation space, keyed by the removal position. The simulated space
	 * re-issues each list at its matching removal, where vanilla would have run
	 * the hook itself.
	 */
	private static Map<BlockPos, Deque<RemovalSideEffect>> buildRemovalSideEffects(
			BlockPos anchor, BlockPos offset, Symmetry symmetry, CaptureSession realSession)
	{
		Map<BlockPos, Deque<RemovalSideEffect>> effects = new HashMap<>();
		for (RemovalSideEffect effect : realSession.removalSideEffects)
		{
			List<CapturedDispatch> mapped = new ArrayList<>(effect.dispatches().size());
			for (CapturedDispatch dispatch : effect.dispatches())
			{
				mapped.add(new CapturedDispatch(dispatch.kind(),
						mapPos(anchor, offset, symmetry, dispatch.pos()),
						dispatch.block(),
						dispatch.exceptDir() == null ? null : symmetry.applyToDirection(dispatch.exceptDir()),
						dispatch.orientation(),
						dispatch.movedByPiston()));
			}
			RemovalSideEffect mappedEffect = new RemovalSideEffect(
					mapPos(anchor, offset, symmetry, effect.pos()), effect.cascadeBusy(), mapped);
			effects.computeIfAbsent(mappedEffect.pos(), k -> new ArrayDeque<>()).add(mappedEffect);
		}
		return effects;
	}

	/** Inverse of {@link #mapPos}: simulation position -> real position. */
	static BlockPos mapPosInverse(BlockPos simPos, BlockPos anchor, BlockPos offset, Symmetry symmetry)
	{
		BlockPos rel = new BlockPos(
				simPos.getX() - anchor.getX() - offset.getX(),
				simPos.getY() - anchor.getY() - offset.getY(),
				simPos.getZ() - anchor.getZ() - offset.getZ());
		return symmetry.applyToPosInverse(rel).offset(
				anchor.getX(), anchor.getY(), anchor.getZ());
	}

	/**
	 * Canonicalizes a block-event payload. The only v1-relevant payload that
	 * encodes a direction is the piston's: paramB carries Direction#get3DDataValue
	 * of the facing the piston acts in, which must be mapped back onto the
	 * real-space orientation just like every other direction the simulation
	 * emits. Every other block's payload is opaque and passes through unchanged.
	 */
	private static int canonicalBlockEventData(Block block, int data, Symmetry symmetry)
	{
		if (block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)
		{
			Direction direction = Direction.from3DDataValue(data);
			return symmetry.applyToDirectionInverse(direction).get3DDataValue();
		}
		return data;
	}

	private static FstestSimWorld buildWorld(ServerLevel real, MinecraftServer server, RegionSnapshot snap,
	                                         Symmetry symmetry, BlockPos offset)
	{
		SimLevelData data = new SimLevelData(real.getLevelData(), real.getGameRules(), real.enabledFeatures());
		FstestSimWorld world = new FstestSimWorld(real, server, data, real.dimensionTypeRegistration(), real.dimension());
		world.setCapturedRandomSeed(snap.realRandomSeed, snap.seedKnown);

		for (Map.Entry<BlockPos, BlockState> entry : snap.states.entrySet())
		{
			world.storeState(mapRelPos(snap.anchor, offset, symmetry, entry.getKey()),
					symmetry.applyToState(entry.getValue()));
		}

		for (Map.Entry<BlockPos, CompoundTag> entry : snap.blockEntities.entrySet())
		{
			BlockPos simPos = mapRelPos(snap.anchor, offset, symmetry, entry.getKey());
			BlockEntity be = BlockEntity.loadStatic(simPos, world.getBlockState(simPos), entry.getValue(),
					server.registryAccess());
			if (be != null)
			{
				be.setLevel(world);
				be.clearRemoved();
				world.addBlockEntity(simPos, be);
			}
		}

		for (ScheduledTick<Block> tick : snap.blockTicks)
		{
			world.blockQueue().inject(new ScheduledTick<>(tick.type(),
					mapRelPos(snap.anchor, offset, symmetry, tick.pos()),
					tick.triggerTick(), tick.priority(), tick.subTickOrder()));
		}
		for (ScheduledTick<Fluid> tick : snap.fluidTicks)
		{
			world.fluidQueue().inject(new ScheduledTick<>(tick.type(),
					mapRelPos(snap.anchor, offset, symmetry, tick.pos()),
					tick.triggerTick(), tick.priority(), tick.subTickOrder()));
		}
		for (BlockEventData event : snap.blockEvents)
		{
			world.injectBlockEvent(new BlockEventData(mapRelPos(snap.anchor, offset, symmetry, event.pos()),
					event.block(), event.paramA(), event.paramB()));
		}
		return world;
	}

	/**
	 * Backfills scheduled ticks that the operation handler created directly in
	 * reality (e.g. a button scheduling its release): only raw setBlocks and
	 * neighbour dispatches are replayed, so those schedules have no natural
	 * counterpart inside the simulation. Their payload (block/pos/delay/
	 * priority) is transform-invariant given the mapped position, so the
	 * mapped copy is exact under every symmetry.
	 *
	 * Block-event creations are deliberately NOT backfilled: the simulation
	 * regenerates all of them naturally from the replayed updates, and any
	 * event it fails to produce is a genuine divergence signal. Backfilling
	 * would both mask such signals and - because a block-event payload can
	 * encode a direction (piston paramB) that the transform must map - risk
	 * injecting untransformed phantoms under rotated runs.
	 */
	private static void reconcileCreations(RegionSnapshot snap, Symmetry symmetry, BlockPos offset,
	                                       FstestSimWorld world, CaptureSession simSession, CaptureSession realSession)
	{
		long gameTime = world.getGameTime();

		Set<String> naturalTickKeys = new HashSet<>();
		for (ScheduledTick<Block> tick : world.blockQueue().snapshotEntries())
		{
			naturalTickKeys.add(tickKey(tick.type(), tick.pos(), delayOf(tick, gameTime), tick.priority()));
		}
		long subOrder = 10_000_000L;
		for (FstEvent.SchedTickCreate created : realSession.createdTicks)
		{
			BlockPos simPos = mapPos(snap.anchor, offset, symmetry, created.pos());
			String key = tickKey(created.block(), simPos, created.delay(), created.priority());
			if (naturalTickKeys.contains(key))
			{
				continue;
			}
			ScheduledTick<Block> injected = new ScheduledTick<>(created.block(), simPos,
					gameTime + created.delay(), created.priority(), subOrder++);
			boolean accepted = world.blockQueue().inject(injected);
			// mirror the natural recorder's gate: creations at unsubscribed
			// positions stay queryable but never enter the events stream, and a
			// duplicate attempt only shows up when duplications are recorded
			if (simSession.isSubscribed(simPos) && FstestConfig.INSTANCE.recordsCreation(accepted))
			{
				simSession.record(new FstEvent.SchedTickCreate(simSession.nextSeq(), simPos,
						created.block(), created.delay(), created.priority(), accepted));
			}
		}
	}

	private static int delayOf(ScheduledTick<?> tick, long gameTime)
	{
		return (int) Math.max(0, tick.triggerTick() - gameTime);
	}

	private static String tickKey(Block block, BlockPos pos, int delay, TickPriority priority)
	{
		// Block instances are registry singletons shared by both worlds, so identity is stable
		return System.identityHashCode(block) + "@" + pos.asLong() + "+" + delay + "/" + priority.ordinal();
	}
}
