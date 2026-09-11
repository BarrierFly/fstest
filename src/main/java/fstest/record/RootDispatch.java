package fstest.record;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * A top-level block-update dispatch performed by the operation's own code
 * path OUTSIDE any setBlock scope (e.g. LeverBlock#updateNeighbours re-notifies
 * the lever and its support block after the toggle's setBlock returned). Such
 * dispatches start cascades that cannot regenerate from the root setBlock
 * alone - the simplified lever-to-wire circuit depends on the support-block
 * notification - so they are captured alongside the roots and replayed after
 * them.
 *
 * {@code rootIndex} attributes the dispatch to the last root recorded before
 * it (-1 = before any root); the replay re-issues it right after that root's
 * setBlock, preserving the original order.
 */
public record RootDispatch(int rootIndex, UpdateKind kind, BlockPos pos, Block block,
                           @Nullable Direction exceptDir, @Nullable Orientation orientation,
                           boolean movedByPiston)
{
}
