package fstest.mixin;

import fstest.duck.WorldBearingLevelTicks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Stores the owning {@code ServerLevel} on its block/fluid tick lists
 * (same approach Carpet TIS Addition uses via ITileTickListWithServerWorld).
 * Mixin copies implemented interfaces onto the target, so the plain duck
 * interface in {@code fstest.duck} becomes available on real LevelTicks.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksWorldMixin implements WorldBearingLevelTicks
{
	@Unique
	private ServerLevel fstest$ownerWorld;

	@Override
	public void fstest$setOwnerWorld(ServerLevel world)
	{
		this.fstest$ownerWorld = world;
	}

	@Override
	public ServerLevel fstest$getOwnerWorld()
	{
		return this.fstest$ownerWorld;
	}
}
