package fstest.mixin;

import fstest.record.RecorderHub;
import fstest.record.UpdateKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Comparator-output updates, mirroring Carpet TIS Addition's
 * {@code ComparatorUpdateMixin} ({@code BlockUpdateType.COMPARATOR_UPDATE}).
 *
 * Vanilla calls {@code Level#updateNeighbourForOutputSignal(pos, block)} from
 * the setBlock notification phase (when the new state has an analog output)
 * and from analog-output blocks; the method then notifies comparators directly
 * adjacent or one block behind a conductor via the full-form
 * {@code neighborChanged}, which is separately logged as a single-block update.
 *
 * Hooked on {@code Level} rather than {@code ServerLevel} so the simulated
 * space (which inherits the implementation) records through the very same
 * path - the two streams stay symmetric by construction.
 */
@Mixin(Level.class)
public abstract class LevelComparatorUpdateMixin
{
	@Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"))
	private void fstest$recordComparatorUpdate(BlockPos pos, Block block, CallbackInfo ci)
	{
		RecorderHub.onNeighborDispatch((Level) (Object) this, UpdateKind.COMPARATOR_UPDATE,
				pos, block, null, null, false, true);
	}
}
