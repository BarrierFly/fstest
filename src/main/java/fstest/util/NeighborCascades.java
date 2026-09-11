package fstest.util;

import fstest.mixin.CollectingNeighborUpdaterAccessor;
import fstest.mixin.LevelNeighborUpdaterAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;

/**
 * Root-capture guard: a setBlock counts as an operation root only when no
 * neighbour-update cascade is in progress. Cascades driven by out-of-band
 * notifications (e.g. the redstone wire evaluator calling updateNeighborsAt
 * from its own handler, outside any setBlock scope) leave the setBlock
 * nesting depth at zero, so depth alone cannot tell an operation's own
 * transition from a cascade-produced one.
 *
 * Lives outside the mixin package on purpose: only real mixin classes may
 * reside there (direct references to non-mixin classes in a mixin config's
 * package are rejected by the transformer at load time).
 */
public final class NeighborCascades
{
	private NeighborCascades()
	{
	}

	public static boolean isBusy(Level level)
	{
		CollectingNeighborUpdater updater = ((LevelNeighborUpdaterAccessor) level).fstest$getNeighborUpdater();
		return ((CollectingNeighborUpdaterAccessor) updater).fstest$getCount() > 0;
	}
}
