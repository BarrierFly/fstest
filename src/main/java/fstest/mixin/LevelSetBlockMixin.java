package fstest.mixin;

import fstest.record.RecorderHub;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every applied block transition through the world's setBlock path -
 * in the real world and (via the simulation's own calls) inside the simulated
 * space alike. This mirrors Carpet TIS Addition's blockstatechange/WorldMixin
 * hook point (LGPL-3.0 collection-layer logic, re-implemented).
 *
 * Only the 4-arg overload is hooked: the 3-arg variant delegates to it.
 *
 * Call nesting depth lets us distinguish transitions applied directly by the
 * player operation itself (depth 0 = root actions, replayed inside the
 * simulation) from transitions produced by neighbour-update cascades
 * (depth > 0, regenerated naturally by the simulation).
 *
 * Exception paths: Mixin has no THROW injection point, so a setBlock call that
 * aborts through a thrown exception would leave the depth counter incremented.
 * That leak is harmless here because every capture window hard-resets the
 * depth at its start (see TriggerCapture#begin / RecorderHub#resetDepth) and
 * exitSetBlock clamps at zero.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin
{
	@Inject(
			method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At("HEAD")
	)
	private void fstest$setBlockHead(BlockPos pos, BlockState newState, int flags, int recursionLeft,
	                                 CallbackInfoReturnable<Boolean> cir)
	{
		RecorderHub.beforeSetBlock((Level) (Object) this, pos, newState, flags);
		RecorderHub.enterSetBlock();
	}

	@Inject(
			method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At("RETURN")
	)
	private void fstest$setBlockReturn(BlockPos pos, BlockState newState, int flags, int recursionLeft,
	                                   CallbackInfoReturnable<Boolean> cir)
	{
		RecorderHub.exitSetBlock();
	}
}
