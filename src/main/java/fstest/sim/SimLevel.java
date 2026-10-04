package fstest.sim;

import fstest.FstestMod;
import fstest.mixin.LevelTicksAccessor;
import fstest.mixin.ServerLevelBlockEventsAccessor;
import fstest.record.UpdateKind;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;

/**
 * A real {@link ServerLevel} inside the simulation server (same construction
 * simulatica uses): genuine chunk pipeline, tick queues, block events and
 * block entity ticking, so every vanilla tick path - scheduled ticks, fluid
 * ticks, {@code affectNeighborsAfterRemoval}, piston events - runs unmodified.
 *
 * Isolation: the dimension is an empty void world (only copied blocks exist),
 * nothing is saved, no packets leave, entities are refused (drops, falling
 * blocks and anything entity-driven is out of scope by design), and the world
 * is wiped between simulated runs.
 */
public final class SimLevel extends ServerLevel
{
	/** Copy/clear flag: no neighbour updates, no shape updates, no drops, no removal hooks. */
	public static final int RAW_FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	/** Phase cap mirrored from vanilla's scheduled-tick drain. */
	public static final int MAX_TICKS_PER_PHASE = 65536;

	/** A private tick-rate manager that always reports "running normally" (nothing is ever frozen here). */
	private final TickRateManager tickRateManager;
	/** Chunk tickets taken by the timed engine, released when the analysis ends. */
	private final LongSet forcedChunks = new LongOpenHashSet();
	@Nullable
	private BlockPos lastClearedMin;
	@Nullable
	private BlockPos lastClearedMax;
	private long setBlockCalls;
	private long neighborUpdateDispatches;

	SimLevel(SimServer server, Executor executor, LevelStorageSource.LevelStorageAccess storage,
	         ServerLevelData levelData, ResourceKey<Level> dimension, LevelStem stem, long seed,
	         List<CustomSpawner> customSpawners, boolean tickTime, @Nullable RandomSequences randomSequences)
	{
		super(server, executor, storage, levelData, dimension, stem, false, seed, customSpawners, tickTime,
				randomSequences);
		this.tickRateManager = new ServerTickRateManager(server);
	}

	// ------------------------------------------------------------------
	// Multi-tick execution (mirrors ServerLevel.tick's relevant phases)
	// ------------------------------------------------------------------

	/**
	 * Runs {@code count} simulated game ticks, mirroring vanilla
	 * {@code ServerLevel#tick}'s relevant phase order: time advance, scheduled
	 * block ticks, scheduled fluid ticks, block events, block entity ticking.
	 * Deliberately skipped (per the plan): world border, weather, raids, chunk
	 * management, entities and chunk/random ticks.
	 *
	 * @param onTickEnd invoked once per simulated tick (per-tick log breakdown)
	 * @return the recording session's event count after each simulated tick
	 */
	public List<Integer> fstest$runSimTicks(int count, IntSupplier onTickEnd)
	{
		List<Integer> boundaries = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			this.tickTime(); // protected vanilla hook: gameTime (+ dayTime per gamerule) advance
			long now = this.getGameTime();
			boolean failed = false;
			try
			{
				this.getBlockTicks().tick(now, MAX_TICKS_PER_PHASE, this::fstest$tickBlock);
				this.getFluidTicks().tick(now, MAX_TICKS_PER_PHASE, this::fstest$tickFluid);
				this.fstest$runBlockEvents();
				this.tickBlockEntities(); // public vanilla Level method; tickers are registered by real chunk paths
			}
			catch (Throwable t)
			{
				failed = true;
				FstestMod.LOGGER.error("[fstest] simulated tick phase failed; aborting the remaining ticks of this run", t);
			}
			boundaries.add(onTickEnd.getAsInt());
			if (failed)
			{
				break;
			}
		}
		return boundaries;
	}

	/** Replica of vanilla {@code ServerLevel#tickBlock}. */
	private void fstest$tickBlock(BlockPos pos, Block block)
	{
		BlockState state = this.getBlockState(pos);
		if (state.is(block))
		{
			state.tick(this, pos, this.random);
		}
	}

	/** Replica of vanilla {@code ServerLevel#tickFluid}. */
	private void fstest$tickFluid(BlockPos pos, Fluid fluid)
	{
		BlockState state = this.getBlockState(pos);
		FluidState fluidState = state.getFluidState();
		if (fluidState.is(fluid))
		{
			fluidState.tick(this, pos, state);
		}
	}

	/**
	 * Replica of vanilla {@code ServerLevel#runBlockEvents} without the client
	 * broadcast. Events are drained head-first (insertion order) and events for
	 * chunks that stopped being tickable are re-queued, exactly like vanilla.
	 */
	private void fstest$runBlockEvents()
	{
		ObjectLinkedOpenHashSet<BlockEventData> events =
				((ServerLevelBlockEventsAccessor) (ServerLevel) this).fstest$getBlockEvents();
		List<BlockEventData> rescheduled = new ArrayList<>();
		while (!events.isEmpty())
		{
			BlockEventData event = events.removeFirst();
			if (this.shouldTickBlocksAt(ChunkPos.asLong(event.pos())))
			{
				BlockState state = this.getBlockState(event.pos());
				if (state.is(event.block()))
				{
					state.triggerEvent(this, event.pos(), event.paramA(), event.paramB());
				}
			}
			else
			{
				rescheduled.add(event);
			}
		}
		events.addAll(rescheduled);
	}

	/** Re-issues one captured out-of-band dispatch through the real ServerLevel methods. */
	public void fstest$issueDispatch(UpdateKind kind, BlockPos pos, Block block, @Nullable Direction exceptDir,
	                                 @Nullable Orientation orientation, boolean movedByPiston)
	{
		switch (kind)
		{
			case BLOCK_UPDATE -> this.updateNeighborsAt(pos, block, orientation);
			case BLOCK_UPDATE_EXCEPT -> this.updateNeighborsAtExceptFromFacing(pos, block, exceptDir, orientation);
			case SINGLE_BLOCK_UPDATE ->
					this.neighborChanged(this.getBlockState(pos), pos, block, orientation, movedByPiston);
			case COMPARATOR_UPDATE -> this.updateNeighbourForOutputSignal(pos, block);
		}
	}

	/** Inserts a pending block event directly (no creation-event recording). */
	public void fstest$addBlockEvent(BlockEventData event)
	{
		((ServerLevelBlockEventsAccessor) (ServerLevel) this).fstest$getBlockEvents().add(event);
	}

	/** Sets the shared simulation clock so scheduled-tick trigger times align with the captured reality. */
	public void fstest$setSimClock(long gameTime, long dayTime)
	{
		ServerLevelData data = (ServerLevelData) this.getLevelData();
		data.setGameTime(gameTime);
		data.setDayTime(dayTime);
	}

	// ------------------------------------------------------------------
	// Region lifecycle (between simulated runs / analyses)
	// ------------------------------------------------------------------

	/**
	 * Takes the forced ticket of one chunk (tracked, so it can be released
	 * again). Deliberately goes through the chunk source instead of
	 * {@link #setChunkForced}, which synchronously blocks on a full chunk load
	 * per chunk - serially, on the server thread. The promotion pump brings the
	 * whole region to a ticking state anyway, and it lets the chunk pipeline
	 * fill the region in parallel.
	 */
	public void fstest$forceChunk(int chunkX, int chunkZ)
	{
		this.getChunkSource().updateChunkForced(new ChunkPos(chunkX, chunkZ), true);
		this.forcedChunks.add(ChunkPos.asLong(chunkX, chunkZ));
	}

	/** Releases one chunk ticket previously taken by {@link #fstest$forceChunk}. */
	public void fstest$releaseChunk(int chunkX, int chunkZ)
	{
		this.getChunkSource().updateChunkForced(new ChunkPos(chunkX, chunkZ), false);
		this.forcedChunks.remove(ChunkPos.asLong(chunkX, chunkZ));
	}

	/** The number of chunk tickets currently held by this level. */
	public int fstest$heldChunkCount()
	{
		return this.forcedChunks.size();
	}

	/** Releases every chunk ticket this level took during analyses. */
	public void fstest$releaseAllChunks()
	{
		for (long key : this.forcedChunks.toLongArray())
		{
			ChunkPos pos = new ChunkPos(key);
			this.setChunkForced(pos.x, pos.z, false);
		}
		this.forcedChunks.clear();
	}

	public void fstest$rememberClearedBox(BlockPos min, BlockPos max)
	{
		this.lastClearedMin = min.immutable();
		this.lastClearedMax = max.immutable();
	}

	public @Nullable BlockPos fstest$lastClearedMin()
	{
		return this.lastClearedMin;
	}

	public @Nullable BlockPos fstest$lastClearedMax()
	{
		return this.lastClearedMax;
	}

	/**
	 * Resets everything a previous simulated run may have left behind inside
	 * the given real-space box: blocks back to air (raw flags - no cascades),
	 * block entities torn down by that, every scheduled tick within the box,
	 * every pending block event, and the (never-ticked) leftover entities.
	 */
	public void fstest$clearBox(BlockPos min, BlockPos max)
	{
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = min.getX(); x <= max.getX(); x++)
		{
			for (int y = min.getY(); y <= max.getY(); y++)
			{
				for (int z = min.getZ(); z <= max.getZ(); z++)
				{
					cursor.set(x, y, z);
					BlockState state = this.getBlockState(cursor);
					if (!state.isAir())
					{
						this.setBlock(cursor, Blocks.AIR.defaultBlockState(), RAW_FLAGS, 0);
					}
				}
			}
		}
		// discard leftovers from earlier runs (drops etc.); copy first - the
		// live iterable is backed by the entity section storage
		List<Entity> leftover = new ArrayList<>();
		this.getAllEntities().forEach(leftover::add);
		for (Entity entity : leftover)
		{
			entity.discard();
		}
		clearTicks(((LevelTicksAccessor) this.getBlockTicks()).fstest$getAllContainers(), min, max);
		clearTicks(((LevelTicksAccessor) this.getFluidTicks()).fstest$getAllContainers(), min, max);
		((ServerLevelBlockEventsAccessor) (ServerLevel) this).fstest$getBlockEvents().clear();
	}

	private static void clearTicks(Long2ObjectMap<LevelChunkTicks<?>> containers, BlockPos min, BlockPos max)
	{
		for (Object value : containers.values())
		{
			@SuppressWarnings("unchecked")
			LevelChunkTicks<Object> container = (LevelChunkTicks<Object>) value;
			container.removeIf(tick -> boxContains(min, max, tick.pos()));
		}
	}

	private static boolean boxContains(BlockPos min, BlockPos max, BlockPos pos)
	{
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	// ------------------------------------------------------------------
	// Stats counters (super-call-through overrides)
	// ------------------------------------------------------------------

	public long fstest$setBlockCalls()
	{
		return this.setBlockCalls;
	}

	public long fstest$neighborUpdateDispatches()
	{
		return this.neighborUpdateDispatches;
	}

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft)
	{
		this.setBlockCalls++;
		return super.setBlock(pos, state, flags, recursionLeft);
	}

	@Override
	public void updateNeighborsAt(BlockPos pos, Block block)
	{
		this.neighborUpdateDispatches++;
		super.updateNeighborsAt(pos, block);
	}

	@Override
	public void updateNeighborsAt(BlockPos pos, Block block, @Nullable Orientation orientation)
	{
		this.neighborUpdateDispatches++;
		super.updateNeighborsAt(pos, block, orientation);
	}

	@Override
	public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction facing,
	                                              @Nullable Orientation orientation)
	{
		this.neighborUpdateDispatches++;
		super.updateNeighborsAtExceptFromFacing(pos, block, facing, orientation);
	}

	@Override
	public void neighborChanged(BlockPos pos, Block neighborBlock, @Nullable Orientation orientation)
	{
		this.neighborUpdateDispatches++;
		super.neighborChanged(pos, neighborBlock, orientation);
	}

	@Override
	public void neighborChanged(BlockState state, BlockPos pos, Block neighborBlock,
	                            @Nullable Orientation orientation, boolean movedByPiston)
	{
		this.neighborUpdateDispatches++;
		super.neighborChanged(state, pos, neighborBlock, orientation, movedByPiston);
	}

	// ------------------------------------------------------------------
	// Isolation overrides
	// ------------------------------------------------------------------

	/** Nothing is ever frozen inside the simulation: its phases always run normally. */
	@Override
	public TickRateManager tickRateManager()
	{
		return this.tickRateManager;
	}

	/** No clients exist for this level. */
	@Override
	public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags)
	{
	}

	@Override
	public void levelEvent(@Nullable Entity entity, int type, BlockPos pos, int data)
	{
		// suppressed: would broadcast effects in a real world
	}

	@Override
	public void globalLevelEvent(int id, BlockPos pos, int data)
	{
		// suppressed
	}

	/** Isolation: the simulated world never persists anything. */
	@Override
	public void save(@Nullable ProgressListener progressListener, boolean flush, boolean skipSave)
	{
		super.save(progressListener, flush, true);
	}

	/** Isolation: no entities ever exist here (drops, falling blocks - out of scope by design). */
	@Override
	public boolean addFreshEntity(Entity entity)
	{
		return false;
	}
}
