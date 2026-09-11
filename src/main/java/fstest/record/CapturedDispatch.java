package fstest.record;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * One block-update dispatch performed by a block-removal side-effect hook
 * ({@code affectNeighborsAfterRemoval}). Captured in reality, re-issued in the
 * simulated space at the corresponding removal point.
 */
public record CapturedDispatch(UpdateKind kind, BlockPos pos, Block block,
                               @Nullable Direction exceptDir, @Nullable Orientation orientation,
                               boolean movedByPiston)
{
}
