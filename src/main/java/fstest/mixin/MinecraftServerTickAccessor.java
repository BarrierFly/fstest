package fstest.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the tick-budget fields of {@code MinecraftServer} so the simulation
 * server's task pump ({@code runAllTasks}) believes it has time to drain
 * queued chunk-promotion work on the real server thread (same approach
 * simulatica uses).
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerTickAccessor
{
	@Accessor("tickCount")
	int fstest$getTickCount();

	@Accessor("tickCount")
	void fstest$setTickCount(int tickCount);

	@Accessor("nextTickTimeNanos")
	void fstest$setNextTickTimeNanos(long nanos);
}
