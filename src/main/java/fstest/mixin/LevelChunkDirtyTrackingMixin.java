package fstest.mixin;

import fstest.sim.SimLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records writes that reach {@code LevelChunk#setBlockState} without going
 * through {@link Level#setBlock} - piston block moves and a few other vanilla
 * paths call the chunk directly.
 *
 * <p>This matters for the timed engine's wipe: it only clears the sections a
 * run actually wrote to, so a write that slipped past the level-level funnel
 * would survive into the next run and show up as a phantom divergence. The
 * {@code instanceof} test inside {@link SimLevel#fstest$markDirtyWrite} keeps
 * the real world off this path entirely.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkDirtyTrackingMixin
{
	@Inject(
			method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At("HEAD")
	)
	private void fstest$trackDirtySection(BlockPos pos, BlockState state, int flags,
	                                      CallbackInfoReturnable<BlockState> cir)
	{
		SimLevel.fstest$markDirtyWrite(((LevelChunk) (Object) this).getLevel(), pos);
	}
}
