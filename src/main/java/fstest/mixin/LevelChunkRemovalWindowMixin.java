package fstest.mixin;

import fstest.record.RecorderHub;
import fstest.util.NeighborCascades;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks the block-removal side-effect window.
 *
 * Vanilla {@code LevelChunk#setBlockState} runs
 * {@code oldState.affectNeighborsAfterRemoval(serverLevel, pos, movedByPiston)}
 * whenever a block is replaced or removed: wire re-evaluates its power and
 * announces it, neighbouring wires recompute their connections, and so on.
 * The simulated space cannot call that hook - its signature demands a
 * {@code ServerLevel}, which the chunkless virtual world is not - so the
 * dispatches the hook performs are captured (see
 * {@link RecorderHub#enterRemovalWindow(boolean)}) and re-issued during the
 * replay.
 *
 * The window is opened around the single {@code affectNeighborsAfterRemoval}
 * invocation, so the generic notification vanilla issues afterwards is NOT
 * captured: the simulated setBlock already performs that one itself, and
 * replaying it too would double it.
 *
 * The cascade state at entry is recorded as well, because a removal can also
 * be produced by a cascade (a shape update destroying a block, e.g. a trapdoor
 * invalidating a redstone wire's support); the two cases need different
 * filtering of the dispatches made inside the window.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkRemovalWindowMixin
{
	@Inject(
			method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/state/BlockState;affectNeighborsAfterRemoval(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Z)V",
					shift = At.Shift.BEFORE
			)
	)
	private void fstest$enterRemovalWindow(BlockPos pos, BlockState state, int flags,
	                                        CallbackInfoReturnable<BlockState> cir)
	{
		Level level = ((LevelChunk) (Object) this).getLevel();
		RecorderHub.enterRemovalWindow(level, level != null && NeighborCascades.isBusy(level), pos);
	}

	@Inject(
			method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/state/BlockState;affectNeighborsAfterRemoval(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Z)V",
					shift = At.Shift.AFTER
			)
	)
	private void fstest$exitRemovalWindow(CallbackInfoReturnable<BlockState> cir)
	{
		RecorderHub.exitRemovalWindow();
	}
}
