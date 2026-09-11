package fstest.mixin;

import fstest.record.RecorderHub;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records every block-event creation attempt on the real server world
 * (mirrors Carpet TIS Addition's blockevent hook point, including its
 * success flag). The simulated space records the equivalent calls directly
 * inside its own blockEvent override, keeping both streams symmetric.
 *
 * 1.21.11's {@code ServerLevel#blockEvent} returns void and simply does
 * {@code blockEvents.add(...)}; the pending set deduplicates an identical
 * (pos, block, paramA, paramB) entry silently. TIS therefore infers success
 * from the queue-size delta, which this hook reproduces.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockEventMixin
{
	@Unique
	private int fstest$blockEventsBefore;

	@Inject(method = "blockEvent", at = @At("HEAD"))
	private void fstest$beforeBlockEvent(BlockPos pos, Block block, int eventID, int eventParam, CallbackInfo ci)
	{
		this.fstest$blockEventsBefore = ((ServerLevelBlockEventsAccessor) (Object) this)
				.fstest$getBlockEvents().size();
	}

	@Inject(method = "blockEvent", at = @At("RETURN"))
	private void fstest$onBlockEvent(BlockPos pos, Block block, int eventID, int eventParam, CallbackInfo ci)
	{
		ServerLevel self = (ServerLevel) (Object) this;
		boolean success = ((ServerLevelBlockEventsAccessor) (Object) this)
				.fstest$getBlockEvents().size() > this.fstest$blockEventsBefore;
		RecorderHub.onBlockEventCreate(self, pos, block, eventID, eventParam, success);
	}
}
