package fstest.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the per-chunk scheduled-tick containers of vanilla {@code LevelTicks}
 * so the snapshot can enumerate pre-existing ticks (they are copied into the
 * simulated space query-only, never executed there).
 */
@Mixin(net.minecraft.world.ticks.LevelTicks.class)
public interface LevelTicksAccessor
{
	@Accessor("allContainers")
	Long2ObjectMap<LevelChunkTicks<?>> fstest$getAllContainers();
}
