package fstest.capture;

import fstest.FstestMod;
import fstest.analysis.FstestAnalysis;
import fstest.config.FstestConfig;
import fstest.config.FstestMode;
import fstest.record.CaptureSession;
import fstest.record.RecorderHub;
import fstest.sim.RegionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.jspecify.annotations.Nullable;

/**
 * Detects accepted player operations that directly affect blocks, and wraps
 * their whole synchronous server-side processing in a recording session.
 *
 * Injection points (see the mixin):
 * - {@code ServerPlayerGameMode#useItemOn}  - accepted iff InteractionResult.Success
 * - {@code ServerPlayerGameMode#useItem}    - same acceptance rule (buckets etc.)
 * - {@code ServerPlayerGameMode#destroyAndAck} - accepted iff the target block
 *   actually changed during processing (covers survival, creative and insta-mine)
 *
 * Rejected / cancelled operations never produce an analysis.
 */
public final class TriggerCapture
{
	public enum OpKind
	{
		USE_ITEM_ON_BLOCK,
		USE_ITEM,
		BREAK_BLOCK
	}

	private static final class ActiveOp
	{
		final OpKind kind;
		final BlockPos anchor;
		final CaptureSession session;
		final ServerPlayer player;
		BlockState beforeState;
		/**
		 * Pre-operation region snapshot, taken lazily by the first-root-change
		 * hook before the operation mutates anything. Null until then (ops that
		 * never change a block never pay for one); null afterwards means the
		 * capture failed and analysis falls back to a post-operation snapshot.
		 */
		@Nullable RegionSnapshot snapshot;

		ActiveOp(OpKind kind, BlockPos anchor, CaptureSession session, ServerPlayer player)
		{
			this.kind = kind;
			this.anchor = anchor;
			this.session = session;
			this.player = player;
		}
	}

	private static final ThreadLocal<ActiveOp> ACTIVE = new ThreadLocal<>();

	private TriggerCapture()
	{
	}

	public static void begin(Level world, OpKind kind, @Nullable BlockPos anchor, @Nullable ServerPlayer player)
	{
		if (!(world instanceof ServerLevel serverLevel))
		{
			return;
		}
		// useItemOn/useItem call ensureRunningOnSameThread AFTER our HEAD injection,
		// which throws on network threads - never capture there.
		if (Thread.currentThread() != serverLevel.getServer().getRunningThread())
		{
			return;
		}
		if (FstestConfig.INSTANCE.mode() == FstestMode.NONE)
		{
			return;
		}
		// Resolved before the scope gate: the gate has to judge the position the
		// operation is anchored at, not the raw `anchor` argument. `useItem` has
		// no hit result and passes a null anchor, so gating on `anchor != null`
		// left that whole path unchecked - and the client falls back to it for
		// any block interaction the server answers with PASS (e.g. a bucket on a
		// stair whose clicked face cannot take water), which then placed water
		// anywhere in the world and triggered timed mode from outside the
		// selection.
		BlockPos pos = anchor != null ? anchor.immutable()
				: player != null ? player.blockPosition().immutable()
				: BlockPos.ZERO;
		if (!inTimedScope(pos))
		{
			// timed mode only ever simulates the selected area: an operation
			// anywhere else in the world has nothing to do with it, and letting
			// it through would capture (and analyse) every block the player
			// touches. Only player-initiated operations reach this hook, so
			// block updates caused by entities or redstone never trigger it.
			return;
		}
		ActiveOp stale = ACTIVE.get();
		if (stale != null)
		{
			// A previous window aborted through an exception path and never reached its
			// RETURN injection point. Game-mode operations never nest on the server
			// thread, so anything still active here is stale - discard it instead of
			// staying dead until restart.
			FstestMod.LOGGER.warn("[fstest] previous capture window did not close cleanly; discarding it");
			RecorderHub.pop(stale.session);
			ACTIVE.remove();
		}
		RecorderHub.resetDepth(); // guard against depth leaked by exception paths in setBlock
		CaptureSession session = RecorderHub.push(world);
		ActiveOp op = new ActiveOp(kind, pos, session, player);
		RecorderHub.setFirstRootAction(() -> op.snapshot = capturePreOpSnapshot(serverLevel, pos));
		ACTIVE.set(op);
	}

	/**
	 * The timed-mode scope gate: with a named area selected, only operations
	 * anchored inside it may start a capture. Instant mode and timed mode
	 * without a selection keep the old behaviour (every accepted operation
	 * anywhere triggers), because there the region is derived from the anchor
	 * *after* the fact.
	 *
	 * <p>Callers pass the already-resolved operation position, never a nullable
	 * anchor: {@link OpKind#USE_ITEM} carries no hit result, and letting a null
	 * through here would exempt it from the selection entirely.
	 */
	private static boolean inTimedScope(BlockPos pos)
	{
		if (!FstestConfig.INSTANCE.isTimed())
		{
			return true;
		}
		FstestConfig.Area area = FstestConfig.INSTANCE.scopedArea().orElse(null);
		return area == null || area.contains(pos);
	}

	/**
	 * Runs at the first root change of the window, before that setBlock applies
	 * - the only moment the region still reflects the pre-operation state the
	 * replay needs. Failures degrade to the post-operation fallback in analysis.
	 */
	private static @Nullable RegionSnapshot capturePreOpSnapshot(ServerLevel world, BlockPos anchor)
	{
		try
		{
			FstestConfig cfg = FstestConfig.INSTANCE;
			// scope: a named test area takes precedence over the numeric radius
			return cfg.scopedArea()
					.map(area -> RegionSnapshot.capture(world, world.getServer(), anchor, area))
					.orElseGet(() -> RegionSnapshot.capture(world, world.getServer(), anchor,
							cfg.effectiveRange()));
		}
		catch (Throwable t)
		{
			FstestMod.LOGGER.error("[fstest] pre-operation snapshot failed; analysis will fall back to a post-operation capture", t);
			return null;
		}
	}

	public static void endWithInteractionResult(Level world, InteractionResult result)
	{
		finish(world, result instanceof InteractionResult.Success);
	}

	public static void endWithBreakOutcome(Level world)
	{
		// destroyAndAck is void; acceptance is decided by comparing the block
		// state at the anchor before/after the call.
		ActiveOp op = ACTIVE.get();
		if (op == null || op.session.world != world || !(world instanceof ServerLevel serverLevel))
		{
			return;
		}
		boolean changed = op.beforeState != null && !op.beforeState.equals(world.getBlockState(op.anchor));
		finish(world, changed || !op.session.rootChanges.isEmpty());
	}

	/** Snapshot helper used by the break hook before vanilla processes the action. */
	public static void snapshotForBreak(Level world, BlockPos pos)
	{
		ActiveOp op = ACTIVE.get();
		if (op != null && op.session.world == world)
		{
			op.beforeState = world.getBlockState(pos);
		}
	}

	private static void finish(Level world, boolean accepted)
	{
		ActiveOp op = ACTIVE.get();
		if (op == null)
		{
			return;
		}
		if (op.session.world == world)
		{
			ACTIVE.remove();
			RecorderHub.setFirstRootAction(null);
			RecorderHub.pop(op.session);
			if (accepted)
			{
				try
				{
					FstestAnalysis.analyze((ServerLevel) world, op.player, op.kind, op.anchor, op.session, op.snapshot);
				}
				catch (Throwable t)
				{
					FstestMod.LOGGER.error("[fstest] analysis failed", t);
					FstestAnalysis.analysisCrashed(op.player);
				}
			}
		}
	}
}
