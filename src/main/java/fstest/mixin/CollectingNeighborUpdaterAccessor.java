package fstest.mixin;

import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the updater's in-flight counter. {@code count > 0} while the
 * updater's runUpdates loop is on the stack, which is the only reliable signal
 * of "inside a neighbour-update cascade": such cascades are NOT necessarily
 * nested inside a Level#setBlock scope (wire evaluators notify out-of-band),
 * so the setBlock nesting depth alone misclassifies cascade-produced setBlocks
 * as operation roots.
 */
@Mixin(CollectingNeighborUpdater.class)
public interface CollectingNeighborUpdaterAccessor
{
	@Accessor("count")
	int fstest$getCount();
}
