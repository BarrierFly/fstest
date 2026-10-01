package fstest.sim;

import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;

/**
 * Frozen copy of the tested world's environment: time, weather, difficulty
 * and an independent deep copy of the game rules. Game time stays identical
 * to reality so scheduled-tick trigger times and event timestamps align
 * between the two worlds.
 */
public final class SimLevelData implements WritableLevelData
{
	private final GameRules gameRules;
	private LevelData.RespawnData respawnData;
	private long gameTime;
	private long dayTime;
	private boolean raining;
	private boolean thundering;
	private final boolean hardcore;
	private final Difficulty difficulty;
	private final boolean difficultyLocked;

	public SimLevelData(LevelData source, GameRules sourceRules, net.minecraft.world.flag.FeatureFlagSet features)
	{
		this.gameRules = sourceRules.copy(features);
		this.respawnData = source.getRespawnData();
		this.gameTime = source.getGameTime();
		this.dayTime = source.getDayTime();
		this.raining = source.isRaining();
		this.thundering = source.isThundering();
		this.hardcore = source.isHardcore();
		this.difficulty = source.getDifficulty();
		this.difficultyLocked = source.isDifficultyLocked();
	}

	public GameRules getGameRules()
	{
		return this.gameRules;
	}

	@Override
	public LevelData.RespawnData getRespawnData()
	{
		return this.respawnData;
	}

	@Override
	public void setSpawn(LevelData.RespawnData respawnData)
	{
		this.respawnData = respawnData;
	}

	@Override
	public long getGameTime()
	{
		return this.gameTime;
	}

	/**
	 * Advances the simulated clock by one game tick, mirroring vanilla
	 * {@code ServerLevel#tickTime}: game time always moves (it drives scheduled
	 * tick trigger times), day time only while the daylight gamerule is on.
	 */
	public void advanceTickTime()
	{
		this.gameTime++;
		if (this.gameRules.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME))
		{
			this.dayTime++;
		}
	}

	@Override
	public long getDayTime()
	{
		return this.dayTime;
	}

	@Override
	public boolean isThundering()
	{
		return this.thundering;
	}

	// not part of LevelData at 1.21.11 - harmless extra setter kept for symmetry
	public void setThundering(boolean thundering)
	{
		this.thundering = thundering;
	}

	@Override
	public boolean isRaining()
	{
		return this.raining;
	}

	@Override
	public void setRaining(boolean raining)
	{
		this.raining = raining;
	}

	@Override
	public boolean isHardcore()
	{
		return this.hardcore;
	}

	@Override
	public Difficulty getDifficulty()
	{
		return this.difficulty;
	}

	@Override
	public boolean isDifficultyLocked()
	{
		return this.difficultyLocked;
	}
}
