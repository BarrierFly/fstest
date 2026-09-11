package fstest.mixin;

import fstest.capture.TriggerCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wraps accepted player operations with a recording session.
 *
 * All three entry points run synchronously on the server thread
 * (PacketUtils.ensureRunningOnSameThread), so a thread-local capture is safe.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin
{
	@Shadow
	public ServerPlayer player;

	// ------------------------------------------------------------------
	// Right-click on a block (place / use item on block / interact)
	// ------------------------------------------------------------------

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void fstest$useItemOnBegin(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
	                                   BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir)
	{
		TriggerCapture.begin(level, TriggerCapture.OpKind.USE_ITEM_ON_BLOCK, hitResult.getBlockPos(), this.player);
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void fstest$useItemOnEnd(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
	                                 BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir)
	{
		TriggerCapture.endWithInteractionResult(level, cir.getReturnValue());
	}

	// ------------------------------------------------------------------
	// Right-click with item in the air (buckets etc.)
	// ------------------------------------------------------------------

	@Inject(method = "useItem", at = @At("HEAD"))
	private void fstest$useItemBegin(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
	                                 CallbackInfoReturnable<InteractionResult> cir)
	{
		TriggerCapture.begin(level, TriggerCapture.OpKind.USE_ITEM, null, this.player);
	}

	@Inject(method = "useItem", at = @At("RETURN"))
	private void fstest$useItemEnd(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
	                               CallbackInfoReturnable<InteractionResult> cir)
	{
		TriggerCapture.endWithInteractionResult(level, cir.getReturnValue());
	}

	// ------------------------------------------------------------------
	// Block breaking - destroyAndAck funnels survival STOP_DESTROY_BLOCK,
	// insta-mine and creative instant break. Acceptance is decided by
	// comparing the anchor block state before/after the call.
	// ------------------------------------------------------------------

	@Inject(method = "destroyAndAck", at = @At("HEAD"))
	private void fstest$destroyBegin(BlockPos pos, int sequence, String message, CallbackInfo ci)
	{
		Level level = this.player.level();
		TriggerCapture.begin(level, TriggerCapture.OpKind.BREAK_BLOCK, pos, this.player);
		TriggerCapture.snapshotForBreak(level, pos);
	}

	@Inject(method = "destroyAndAck", at = @At("RETURN"))
	private void fstest$destroyEnd(BlockPos pos, int sequence, String message, CallbackInfo ci)
	{
		TriggerCapture.endWithBreakOutcome(this.player.level());
	}
}
