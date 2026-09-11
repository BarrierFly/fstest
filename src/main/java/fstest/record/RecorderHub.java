package fstest.record;

import fstest.config.FstestConfig;
import fstest.util.NeighborCascades;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Central registry of active {@link CaptureSession}s.
 *
 * Sessions live on a thread-local stack because every operation is processed
 * synchronously on the server thread (packets are forced onto the main
 * thread before game-mode logic runs).
 *
 * The same recording entry points are called for the real world (via mixins)
 * and for the simulated space (from the simulation's own world methods),
 * which keeps collection behaviour identical on both sides - the core
 * requirement from the mod plan.
 */
public final class RecorderHub
{
	private static final ThreadLocal<Deque<CaptureSession>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
	/** Current net.minecraft.world.level.Level#setBlock nesting depth on this thread. */
	private static final ThreadLocal<int[]> SETBLOCK_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	/**
	 * One-shot hook fired on the active window's first root change while that
	 * setBlock is still at HEAD (nothing has mutated yet). The capture layer
	 * uses it to take the pre-operation snapshot: a snapshot taken after the
	 * operation would already contain the operation's own result, and the
	 * replayed root setBlock would no-op against it.
	 */
	private static final ThreadLocal<Runnable> FIRST_ROOT_ACTION = new ThreadLocal<>();
	/**
	 * Stack of open {@code affectNeighborsAfterRemoval} side-effect windows.
	 *
	 * Vanilla runs block-specific removal behaviour there (vanilla
	 * {@code LevelChunk#setBlockState}); the simulated space cannot invoke that
	 * hook because its signature demands a {@code ServerLevel}. Instead the
	 * dispatches the real hook performs are collected per removal and later
	 * re-issued AT THE SIMULATED REMOVAL POINT (see {@link RemovalSideEffect}),
	 * which keeps the micro-timing order and the cascade context intact.
	 *
	 * A window can open at the top level (the operation removed a block) or
	 * inside an ongoing neighbour cascade (a shape update destroyed a block,
	 * e.g. a trapdoor invalidating a redstone wire's support). The two cases
	 * need different filtering of the dispatches seen inside the window - see
	 * {@link #onNeighborDispatch}.
	 */
	private static final ThreadLocal<Deque<RemovalWindow>> REMOVAL_WINDOWS = ThreadLocal.withInitial(ArrayDeque::new);

	/** One open removal window: where the hook runs, and what it dispatches. */
	private static final class RemovalWindow
	{
		final CaptureSession session;
		final BlockPos pos;
		final boolean cascadeBusy;
		final int setBlockDepth;
		final List<CapturedDispatch> dispatches = new ArrayList<>();

		RemovalWindow(CaptureSession session, BlockPos pos, boolean cascadeBusy, int setBlockDepth)
		{
			this.session = session;
			this.pos = pos;
			this.cascadeBusy = cascadeBusy;
			this.setBlockDepth = setBlockDepth;
		}
	}

	private RecorderHub()
	{
	}

	public static CaptureSession push(Level world)
	{
		CaptureSession session = new CaptureSession(world);
		stack().push(session);
		return session;
	}

	public static void pop(CaptureSession session)
	{
		Deque<CaptureSession> st = stack();
		if (!st.isEmpty() && st.peek() == session)
		{
			st.pop();
		}
	}

	/** The active session for this exact world instance, or null. */
	public static CaptureSession current(Level world)
	{
		Deque<CaptureSession> st = stack();
		if (st.isEmpty())
		{
			return null;
		}
		CaptureSession top = st.peek();
		return top.world == world ? top : null;
	}

	/** Registers the one-shot first-root-change hook (capture layer only). */
	public static void setFirstRootAction(@Nullable Runnable action)
	{
		FIRST_ROOT_ACTION.set(action);
	}

	// ------------------------------------------------------------------
	// Recording entry points (called from mixins and from the sim world)
	// ------------------------------------------------------------------

	/** Called immediately before Level.setBlock applies a transition. */
	public static void beforeSetBlock(Level world, BlockPos pos, BlockState newState, int flags)
	{
		CaptureSession session = current(world);
		int[] depth = SETBLOCK_DEPTH.get();
		if (session != null)
		{
			// A transition counts as an operation root only when it is neither
			// nested in another setBlock NOR running inside a neighbour-update
			// cascade. Wire-driven cascades notify out-of-band (outside any
			// setBlock scope), so depth alone misclassifies them as roots.
			boolean root = depth[0] == 0 && !NeighborCascades.isBusy(world);
			BlockState oldState = world.getBlockState(pos);
			FstEvent.BlockChange event = new FstEvent.BlockChange(
					session.nextSeq(), pos.immutable(), oldState, newState, flags);
			if (!oldState.equals(newState))
			{
				if (root)
				{
					if (session.rootChanges.isEmpty() && session.world instanceof ServerLevel)
					{
						Runnable firstRootAction = FIRST_ROOT_ACTION.get();
						FIRST_ROOT_ACTION.set(null);
						if (firstRootAction != null)
						{
							firstRootAction.run();
						}
					}
					session.rootChanges.add(event);
				}
				if (session.isSubscribed(pos))
				{
					session.record(event);
				}
			}
		}
	}

	public static void enterSetBlock()
	{
		SETBLOCK_DEPTH.get()[0]++;
	}

	public static void exitSetBlock()
	{
		int[] depth = SETBLOCK_DEPTH.get();
		if (depth[0] > 0)
		{
			depth[0]--;
		}
	}

	/**
	 * Hard-resets the setBlock nesting depth and closes any removal window left
	 * open at a capture window start. Mixin provides no THROW injection point,
	 * so a setBlock (or a removal hook) aborted by an exception would otherwise
	 * leave this thread's bookkeeping permanently skewed.
	 */
	public static void resetDepth()
	{
		SETBLOCK_DEPTH.get()[0] = 0;
		REMOVAL_WINDOWS.get().clear();
	}

	/**
	 * Opens a block-removal side-effect window (see {@link #REMOVAL_WINDOWS}).
	 *
	 * @param world       the world the removal happens in
	 * @param cascadeBusy whether a neighbour cascade was already running when
	 *                    the removal happened - it decides how dispatches made
	 *                    inside the window are filtered
	 * @param pos         position passed to the setBlock that removed the block
	 */
	public static void enterRemovalWindow(Level world, boolean cascadeBusy, BlockPos pos)
	{
		REMOVAL_WINDOWS.get().push(new RemovalWindow(current(world), pos.immutable(), cascadeBusy,
				SETBLOCK_DEPTH.get()[0]));
	}

	/** Closes the innermost block-removal side-effect window. */
	public static void exitRemovalWindow()
	{
		Deque<RemovalWindow> windows = REMOVAL_WINDOWS.get();
		if (windows.isEmpty())
		{
			return;
		}
		RemovalWindow window = windows.pop();
		if (window.session != null && !window.dispatches.isEmpty())
		{
			window.session.removalSideEffects.add(new RemovalSideEffect(window.pos, window.cascadeBusy, window.dispatches));
		}
	}

	public static void onSchedTickCreate(Level world, BlockPos pos, Block block, int delay, TickPriority priority, boolean success)
	{
		CaptureSession session = current(world);
		if (session == null)
		{
			return;
		}
		FstEvent.SchedTickCreate event = new FstEvent.SchedTickCreate(
				session.nextSeq(), pos.immutable(), block, delay, priority, success);
		session.createdTicks.add(event);
		if (session.isSubscribed(pos) && FstestConfig.INSTANCE.recordsCreation(success))
		{
			session.record(event);
		}
	}

	public static void onBlockEventCreate(Level world, BlockPos pos, Block block, int type, int data, boolean success)
	{
		CaptureSession session = current(world);
		if (session == null)
		{
			return;
		}
		FstEvent.BlockEventCreate event = new FstEvent.BlockEventCreate(
				session.nextSeq(), pos.immutable(), block, type, data, success);
		session.createdBlockEvents.add(event);
		if (session.isSubscribed(pos) && FstestConfig.INSTANCE.recordsCreation(success))
		{
			session.record(event);
		}
	}

	/**
	 * Records a block-update DISPATCH performed by the block at {@code pos}
	 * (the block that changed and is notifying its neighbours) - the TIS
	 * microTiming block-update semantics: the event belongs to the dispatching
	 * block, gated by the end-rod rule at the dispatching position. Updates
	 * merely RECEIVED by a block are not recorded.
	 *
	 * Also captures top-level dispatches (neither inside a setBlock nor inside
	 * a cascade) as {@link RootDispatch}es, so the replay can re-issue the
	 * operation's own out-of-band notifications (e.g. LeverBlock#updateNeighbours).
	 *
	 * The events-stream write is additionally gated by the {@code updates}
	 * toggle; the replay captures below are never gated.
	 *
	 * @param recordUpdateEvent false for dispatch paths that are captured for
	 *                          replay bookkeeping only
	 */
	public static void onNeighborDispatch(Level world, UpdateKind kind, BlockPos pos, Block block,
	                                      @Nullable Direction exceptDir, @Nullable Orientation orientation,
	                                      boolean movedByPiston, boolean recordUpdateEvent)
	{
		CaptureSession session = current(world);
		if (session == null)
		{
			return;
		}
		if (recordUpdateEvent && FstestConfig.INSTANCE.updates() && session.isUpdateSubscribed(pos))
		{
			session.record(new FstEvent.NeighborUpdate(session.nextSeq(), pos.immutable(), block, kind, exceptDir));
		}
		// Capture the dispatch for replay in two situations:
		//
		// 1. inside a block-removal side-effect window: the dispatches the hook
		//    performs are collected for the removal and re-issued at the matching
		//    removal inside the simulation. The hook's own dispatches sit at the
		//    setBlock depth the window opened at. If the removal happened inside
		//    an ongoing cascade, the neighbour updater only ENQUEUES those
		//    dispatches - nothing downstream runs while the window is open - so
		//    every dispatch at that depth is the hook's own. If the removal
		//    happened at the top level, its cascades do run synchronously, so the
		//    busy check keeps downstream dispatches out (they are regenerated by
		//    re-issuing the hook's own dispatches).
		// 2. top-level dispatch outside any window (e.g. LeverBlock#updateNeighbours
		//    after its toggle's setBlock returned).
		int depth = SETBLOCK_DEPTH.get()[0];
		boolean busy = NeighborCascades.isBusy(world);
		Deque<RemovalWindow> windows = REMOVAL_WINDOWS.get();
		if (!windows.isEmpty())
		{
			RemovalWindow window = windows.peek();
			if (depth == window.setBlockDepth && (window.cascadeBusy || !busy))
			{
				window.dispatches.add(new CapturedDispatch(kind, pos.immutable(), block, exceptDir, orientation, movedByPiston));
			}
			return;
		}
		if (depth == 0 && !busy)
		{
			session.rootDispatches.add(new RootDispatch(session.rootChanges.size() - 1, kind,
					pos.immutable(), block, exceptDir, orientation, movedByPiston));
		}
	}

	private static Deque<CaptureSession> stack()
	{
		return STACK.get();
	}
}
