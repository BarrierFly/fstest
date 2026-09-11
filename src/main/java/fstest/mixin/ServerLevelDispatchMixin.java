package fstest.mixin;

import fstest.record.RecorderHub;
import fstest.record.UpdateKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors Carpet TIS Addition's microTiming block-update hooks (sender-side:
 * the event belongs to the block DISPATCHING the updates) and additionally
 * captures top-level dispatches for the replay. Targets ServerLevel because
 * the base Level methods are empty no-ops overridden here.
 *
 * On 1.21.11 TIS logs all four of these as block-update events, with the
 * subtypes {@code BLOCK_UPDATE}, {@code BLOCK_UPDATE_EXCEPT} (carrying the
 * skipped side) and {@code SINGLE_BLOCK_UPDATE} for both neighborChanged
 * forms; the comparator-output dispatch is hooked separately on {@code Level}.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelDispatchMixin
{
	@Inject(
			method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
			at = @At("HEAD")
	)
	private void fstest$dispatchUpdateNeighbors(BlockPos pos, Block block, @Nullable Orientation orientation, CallbackInfo ci)
	{
		RecorderHub.onNeighborDispatch((ServerLevel) (Object) this, UpdateKind.BLOCK_UPDATE,
				pos, block, null, orientation, false, true);
	}

	@Inject(
			method = "updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/redstone/Orientation;)V",
			at = @At("HEAD")
	)
	private void fstest$dispatchUpdateNeighborsExcept(BlockPos pos, Block block, @Nullable Direction exceptDir,
	                                                  @Nullable Orientation orientation, CallbackInfo ci)
	{
		RecorderHub.onNeighborDispatch((ServerLevel) (Object) this, UpdateKind.BLOCK_UPDATE_EXCEPT,
				pos, block, exceptDir, orientation, false, true);
	}

	@Inject(
			method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
			at = @At("HEAD")
	)
	private void fstest$dispatchNeighborChanged(BlockPos pos, Block block, @Nullable Orientation orientation, CallbackInfo ci)
	{
		RecorderHub.onNeighborDispatch((ServerLevel) (Object) this, UpdateKind.SINGLE_BLOCK_UPDATE,
				pos, block, null, orientation, false, true);
	}

	// The full-form neighborChanged is also logged as SINGLE_BLOCK_UPDATE on
	// 1.19+ (TIS's SingleBlockUpdate2Mixin); it fires for direct single-position
	// updates such as the comparator half of updateNeighbourForOutputSignal.
	// It is additionally captured as a replayable dispatch (recordUpdateEvent
	// stays true for that reason).
	@Inject(
			method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V",
			at = @At("HEAD")
	)
	private void fstest$dispatchNeighborChangedFull(BlockState state, BlockPos pos, Block block,
	                                                @Nullable Orientation orientation, boolean movedByPiston, CallbackInfo ci)
	{
		RecorderHub.onNeighborDispatch((ServerLevel) (Object) this, UpdateKind.SINGLE_BLOCK_UPDATE,
				pos, block, null, orientation, movedByPiston, true);
	}
}
