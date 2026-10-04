package fstest.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fstest.client.FstestAreaRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the fstest selection areas during the world pass.
 *
 * <p>Injected into the existing block-outline pass rather than a render event:
 * that call site already owns a world-space {@link PoseStack} and an
 * unflushed {@link MultiBufferSource.BufferSource}, and it runs twice (solid
 * and translucent) - only the solid pass draws, so the boxes are not overdrawn.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererAreasMixin
{
	@Inject(method = "renderBlockOutline", at = @At("HEAD"))
	private void fstest$renderAreas(MultiBufferSource.BufferSource bufferSource, PoseStack poseStack,
	                                boolean translucent, LevelRenderState renderState, CallbackInfo ci)
	{
		if (!translucent)
		{
			FstestAreaRenderer.render(poseStack, bufferSource, renderState.cameraRenderState.pos);
		}
	}
}