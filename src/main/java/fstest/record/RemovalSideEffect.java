package fstest.record;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * The dispatches a block's {@code affectNeighborsAfterRemoval} hook performed
 * when the block at {@code pos} was removed or replaced in reality.
 *
 * The simulated space cannot call that hook (its signature demands a
 * {@code ServerLevel}), so the hook's observable effect is captured as this
 * dispatch list and re-issued AT THE SIMULATED REMOVAL POINT - the same place
 * in the setBlock flow where vanilla runs the hook - keeping the micro-timing
 * order and the cascade context intact.
 *
 * {@code cascadeBusy} records whether a neighbour cascade was already running
 * when the hook ran. It is the invariant that makes the re-issue equivalent:
 * with a cascade active the neighbour updater only ENQUEUES the dispatches
 * (they enter the manual stack contiguously and run later), while at the top
 * level each dispatch drains its cascade synchronously. The simulation
 * compares its own cascade state at its matching removal and reports a
 * mismatch, which would mean the two removal contexts have already diverged.
 */
public record RemovalSideEffect(BlockPos pos, boolean cascadeBusy, List<CapturedDispatch> dispatches)
{
}
