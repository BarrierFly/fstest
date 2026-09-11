package fstest.mixin;

import fstest.duck.WorldBearingLevelTicks;
import fstest.record.RecorderHub;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records scheduled-tick creation attempts on the real world (spec section
 * five: creation attempts are micro-timing events, duplicates included).
 * The simulated space records through its own SimTickQueue instead.
 *
 * Success mirrors TIS's flag: vanilla's per-chunk container deduplicates an
 * attempt for a (type, position) that is already queued, so the attempt is
 * recorded as failed when the container's size did not grow.
 */
@Mixin(net.minecraft.world.ticks.LevelTicks.class)
public abstract class LevelTicksScheduleMixin
{
	@Unique
	private int fstest$containerCountBefore;

	@Inject(method = "schedule", at = @At("HEAD"))
	private void fstest$beforeSchedule(ScheduledTick<?> tick, CallbackInfo ci)
	{
		this.fstest$containerCountBefore = this.fstest$containerCount(tick.pos());
	}

	@Inject(method = "schedule", at = @At("RETURN"))
	private void fstest$onSchedule(ScheduledTick<?> tick, CallbackInfo ci)
	{
		ServerLevel world = ((WorldBearingLevelTicks) (Object) this).fstest$getOwnerWorld();
		if (world == null)
		{
			return;
		}
		if (!(tick.type() instanceof Block block))
		{
			return; // fluid ticks are copied into the snapshot but not diffed in v1
		}
		boolean success = this.fstest$containerCount(tick.pos()) > this.fstest$containerCountBefore;
		int delay = (int) Math.max(0, tick.triggerTick() - world.getGameTime());
		RecorderHub.onSchedTickCreate(world, tick.pos(), block, delay, tick.priority(), success);
	}

	/** Entry count of the chunk container that {@code LevelTicks#schedule} would use. */
	@Unique
	private int fstest$containerCount(BlockPos pos)
	{
		Long2ObjectMap<LevelChunkTicks<?>> containers =
				((LevelTicksAccessor) (Object) this).fstest$getAllContainers();
		LevelChunkTicks<?> container = containers.get(ChunkPos.asLong(pos));
		return container == null ? 0 : container.count();
	}
}
