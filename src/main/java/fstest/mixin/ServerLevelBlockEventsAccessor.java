package fstest.mixin;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the server world's pending block events (piston actions etc.)
 * for snapshot copying into the simulated space.
 */
@Mixin(ServerLevel.class)
public interface ServerLevelBlockEventsAccessor
{
	@Accessor("blockEvents")
	ObjectLinkedOpenHashSet<BlockEventData> fstest$getBlockEvents();
}
