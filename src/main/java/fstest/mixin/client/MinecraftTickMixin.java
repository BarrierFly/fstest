package fstest.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fstest.client.FstestAreaRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the fstest selection areas once per client tick.
 *
 * <p>Injected into {@code Minecraft#tick} rather than into the level renderer:
 * the renderer's world pass is built around a deferred frame graph whose
 * per-pass lambdas make it a fragile injection site, whereas the client tick
 * runs unconditionally every frame. Buffers taken here are flushed immediately
 * in this method, so they cannot interleave with the level's own geometry.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTickMixin
{
	@Inject(method = "tick", at = @At("RETURN"))
	private void fstest$renderAreas(CallbackInfo ci)
	{
		Minecraft minecraft = Minecraft.getInstance();
		PoseStack poseStack = new PoseStack();
		MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
		Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
		FstestAreaRenderer.render(poseStack, buffers, camera);
		buffers.endBatch(RenderTypes.lines());
		buffers.endBatch(RenderTypes.debugFilledBox());
	}
}