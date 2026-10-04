package fstest.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code ServerLevel#entityManager} so the simulation server's chunk
 * promotion pump can drive the entity section manager like simulatica does.
 */
@Mixin(ServerLevel.class)
public interface ServerLevelEntityManagerAccessor
{
	@Accessor("entityManager")
	PersistentEntitySectionManager<Entity> fstest$entityManager();
}
