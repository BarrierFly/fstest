package fstest.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes {@code MinecraftServer#levels} so the simulation server can install
 * its hand-built void dimensions (same approach simulatica uses; constructing
 * levels via {@code createLevels()} would also search for an initial spawn).
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerLevelsAccessor
{
	@Accessor("levels")
	Map<ResourceKey<Level>, ServerLevel> fstest$levels();
}
