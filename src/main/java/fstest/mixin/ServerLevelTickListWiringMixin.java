package fstest.mixin;

import fstest.duck.WorldBearingLevelTicks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lazily wires the owning {@code ServerLevel} onto its block/fluid tick lists
 * (first accessor call), so the schedule hook can resolve its capture session.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTickListWiringMixin
{
	@Shadow
	@Final
	private LevelTicks<Block> blockTicks;

	@Shadow
	@Final
	private LevelTicks<Fluid> fluidTicks;

	@Unique
	private boolean fstest$tickListsWired;

	@Inject(method = "getBlockTicks", at = @At("HEAD"))
	private void fstest$wireTickLists(CallbackInfoReturnable<LevelTicks<Block>> cir)
	{
		if (!this.fstest$tickListsWired)
		{
			this.fstest$tickListsWired = true;
			ServerLevel self = (ServerLevel) (Object) this;
			((WorldBearingLevelTicks) this.blockTicks).fstest$setOwnerWorld(self);
			((WorldBearingLevelTicks) this.fluidTicks).fstest$setOwnerWorld(self);
		}
	}
}
