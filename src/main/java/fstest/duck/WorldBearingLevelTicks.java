package fstest.duck;

import net.minecraft.server.level.ServerLevel;

/**
 * Duck interface carrying the owning world onto vanilla {@code LevelTicks}
 * instances so schedule hooks can find the active capture session.
 *
 * Lives OUTSIDE the mixin package on purpose: classes inside a registered
 * mixin package cannot be referenced directly at runtime
 * (IllegalClassLoadError). Interfaces implemented by a mixin class are
 * copied onto its target by Mixin, so a plain interface here is enough -
 * see {@code fstest.mixin.LevelTicksWorldMixin}.
 */
public interface WorldBearingLevelTicks
{
	void fstest$setOwnerWorld(ServerLevel world);

	ServerLevel fstest$getOwnerWorld();
}
