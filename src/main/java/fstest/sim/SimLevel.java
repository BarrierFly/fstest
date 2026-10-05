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
import net.minecraft.core.SectionPos;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
	/**
	 * Section keys ({@link SectionPos#asLong(int, int, int)}) written since the
	 * last wipe. The wipe walks these instead of the whole snapshot box: a volume
	 * scan costs {@code (2r+1)^3} chunk lookups per run, which at the default
	 * radius is ~900k for a device that occupies a few hundred blocks.
	 *
	 * <p>Recording happens on the single {@code setBlock} funnel plus two
	 * fallbacks ({@code LevelChunk#setBlockState} direct callers and block
	 * entity installation), so blocks the simulated ticks create on their own -
	 * spreading fluid, lava meeting water, a piston pushing - are covered just
	 * like the blocks copied in from the snapshot.
	 */
	private final LongSet dirtySections = new LongOpenHashSet();
	/** Positions that received a block entity since the last wipe. */
	private final LongSet dirtyBlockEntities = new LongOpenHashSet();
	/** Whether writes are being recorded right now (off during the wipe itself). */
	private boolean trackingWrites;
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
		// the chunks these sections lived in are on their way out, so the
		// bookkeeping would only name positions nobody can read any more
		this.dirtySections.clear();
		this.dirtyBlockEntities.clear();
	}

	/**
	 * Starts recording the sections this level writes to, so the next
	 * {@link #fstest$clearBox} knows where the previous run left something.
	 * Must be armed before the snapshot copy-in - that copy is itself a write.
	 */
	public void fstest$beginTracking()
	{
		this.dirtySections.clear();
		this.dirtyBlockEntities.clear();
		this.trackingWrites = true;
	}

	/** Stops recording, and returns the "sections/blockEntities" tally for the run's stats line. */
	public String fstest$endTracking()
	{
		this.trackingWrites = false;
		return this.dirtySections.size() + "/" + this.dirtyBlockEntities.size();
	}

	/**
 * The chunk a write at {@code pos} belongs to, or null when it is not resident.
 *
 * <p>Bulk callers use this to resolve the chunk once instead of once per block:
 * {@code Level#getBlockState} and {@code Level#getChunkAt} both go through a
 * {@code ChunkMap} holder lookup, and a snapshot copy pays two of them per
 * copied block.
 */
	public @Nullable LevelChunk fstest$chunkForWrite(BlockPos pos)
	{
		return this.getChunkSource().getChunkNow(
				SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ()));
	}

	/**
	 * {@code setBlock(pos, state, RAW_FLAGS, 0)} with the chunk already resolved.
	 *
	 * <p>Step for step the same as {@code Level#setBlock} with those flags:
	 * {@code RAW_FLAGS} carries none of the neighbour / shape / client bits, so
	 * the only things {@code Level#setBlock} adds around
	 * {@code LevelChunk#setBlockState} are the bounds test below, the two chunk
	 * lookups this skips, the no-op {@code setBlocksDirty}, and the POI refresh
	 * - which still runs. What it saves is one chunk-holder lookup and one
	 * section re-read per block.
	 *
	 * <p>The heightmaps, section-emptiness bookkeeping, light queueing and block
	 * entity handling all live in {@code LevelChunk#setBlockState} and are
	 * deliberately left in place: a real {@code ServerLevel} has to stay
	 * self-consistent for the next run, which is exactly what the chunkless
	 * instant engine does not have to care about.
	 */
	public void fstest$storeState(LevelChunk chunk, BlockPos pos, BlockState state)
	{
		// LevelChunk indexes its section array with the raw block Y, so a
		// vertical P offset that leaves the build height must be refused here,
		// exactly as Level#setBlock refuses it.
		if (!this.isInValidBounds(pos))
		{
			return;
		}
		BlockState previous = chunk.setBlockState(pos, state, RAW_FLAGS);
		if (previous == null)
		{
			return; // the section or the block was already in that state
		}
		BlockState current = chunk.getBlockState(pos);
		if (current == state)
		{
			if (previous != current)
			{
				this.setBlocksDirty(pos, previous, current);
			}
			this.updatePOIOnBlockStateChange(pos, previous, current);
		}
		this.fstest$markDirty(pos);
	}

	private void fstest$markDirty(BlockPos pos)
	{
		if (this.trackingWrites)
		{
			this.dirtySections.add(SectionPos.asLong(pos));
		}
	}

	private void fstest$markDirtyBlockEntity(BlockPos pos)
	{
		if (this.trackingWrites)
		{
			this.dirtySections.add(SectionPos.asLong(pos));
			this.dirtyBlockEntities.add(pos.asLong());
		}
	}

	/**
	 * Records a write that reached {@code LevelChunk#setBlockState} without
	 * passing through {@link Level#setBlock}. Called from a mixin, so the
	 * instanceof test is what keeps the real world's hot path untouched.
	 */
	public static void fstest$markDirtyWrite(Level level, BlockPos pos)
	{
		if (level instanceof SimLevel sim)
		{
			sim.fstest$markDirty(pos);
		}
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
	 * Resets everything a previous simulated run may have left behind: blocks
	 * back to air, the block entities among them torn down, every scheduled
	 * tick, every pending block event, and the (never-ticked) leftover entities.
	 *
	 * <p>Only what the previous run actually wrote to is walked - see
	 * {@link #dirtySections}. A whole-box scan would also reach blocks the run
	 * created outside the snapshot bounds (a cascade escaping the region), but
	 * those are recorded too, and the scan costs one chunk-status lookup per
	 * position in the box on every single run.
	 *
	 * @return wall-clock cost of this wipe, for the per-run timing breakdown
	 */
	public long fstest$clearBox()
	{
		long startNanos = System.nanoTime();
		boolean wasTracking = this.trackingWrites;
		this.trackingWrites = false;
		try
		{
			LongSet dirtyChunks = new LongOpenHashSet();
			for (long sectionKey : this.dirtySections)
			{
				dirtyChunks.add(ChunkPos.asLong(SectionPos.x(sectionKey), SectionPos.z(sectionKey)));
			}
			this.fstest$wipeDirtyBlocks();
			this.fstest$wipeDirtyBlockEntities();
			this.fstest$wipeDirtyTicks(((LevelTicksAccessor) this.getBlockTicks()).fstest$getAllContainers(),
					dirtyChunks);
			this.fstest$wipeDirtyTicks(((LevelTicksAccessor) this.getFluidTicks()).fstest$getAllContainers(),
					dirtyChunks);
			// discard leftovers from earlier runs (drops etc.); copy first - the
			// live iterable is backed by the entity section storage
			List<Entity> leftover = new ArrayList<>();
			this.getAllEntities().forEach(leftover::add);
			for (Entity entity : leftover)
			{
				entity.discard();
			}
			((ServerLevelBlockEventsAccessor) (ServerLevel) this).fstest$getBlockEvents().clear();
		}
		finally
		{
			this.trackingWrites = wasTracking;
		}
		return System.nanoTime() - startNanos;
	}

	/**
	 * Clears every block in every section the previous run wrote to. The read
	 * goes straight at the chunk's section array (skipping all-air sections
	 * wholesale); the write still goes through {@code setBlock} with raw flags,
	 * so heightmaps, section-emptiness and light stay consistent for whatever
	 * the next run copies in.
	 */
	private void fstest$wipeDirtyBlocks()
	{
		if (this.dirtySections.isEmpty())
		{
			return;
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		long[] sections = this.dirtySections.toLongArray();
		for (long sectionKey : sections)
		{
			int sectionX = SectionPos.x(sectionKey);
			int sectionY = SectionPos.y(sectionKey);
			int sectionZ = SectionPos.z(sectionKey);
			// getChunkNow, not getChunk: a section whose chunk is already gone
			// has nothing left to clear, and asking for it would load one
			LevelChunk chunk = this.getChunkSource().getChunkNow(sectionX, sectionZ);
			if (chunk == null)
			{
				continue;
			}
			int index = chunk.getSectionIndexFromSectionY(sectionY);
			if (index < 0 || index >= chunk.getSectionsCount())
			{
				continue;
			}
			LevelChunkSection section = chunk.getSection(index);
			if (section == null || section.hasOnlyAir())
			{
				continue;
			}
			int baseX = sectionX << 4;
			int baseY = sectionY << 4;
			int baseZ = sectionZ << 4;
			for (int localY = 0; localY < 16; localY++)
			{
				for (int localZ = 0; localZ < 16; localZ++)
				{
					for (int localX = 0; localX < 16; localX++)
					{
						if (section.getBlockState(localX, localY, localZ).isAir())
						{
							continue;
						}
						this.fstest$storeState(chunk, cursor.set(baseX | localX, baseY | localY, baseZ | localZ),
								air);
					}
				}
			}
		}
		this.dirtySections.clear();
	}

	/**
	 * Removes the block entities the previous run installed. Mostly redundant
	 * with the block wipe (vanilla drops the block entity when its block goes),
	 * but a block entity can outlive its block - a finalised moving piston, an
	 * entity-backed one whose state was never cleared - and such a leftover sits
	 * at an all-air position the block wipe skips.
	 */
	private void fstest$wipeDirtyBlockEntities()
	{
		if (this.dirtyBlockEntities.isEmpty())
		{
			return;
		}
		long[] positions = this.dirtyBlockEntities.toLongArray();
		this.dirtyBlockEntities.clear();
		for (long packed : positions)
		{
			BlockPos pos = BlockPos.of(packed);
			LevelChunk chunk = this.getChunkSource().getChunkNow(
					SectionPos.blockToSectionCoord(pos.getX()),
					SectionPos.blockToSectionCoord(pos.getZ()));
			if (chunk != null)
			{
				chunk.removeBlockEntity(pos);
			}
		}
	}

	/**
	 * Empties the tick containers of every chunk the previous run wrote to.
	 *
	 * <p>Whole containers, keyed by chunk rather than filtered by the run's box:
	 * a scheduled tick can be created outside the snapshot bounds by the same
	 * cascade that writes outside them, and a box filter would let it survive
	 * into the next run - where it would fire at a position the transform never
	 * put anything at. Emptied containers also re-sync themselves: an empty
	 * container's stale entry in {@code LevelTicks#nextTickForContainer} is
	 * dropped on the next collect pass because its head is null.
	 */
	private static void fstest$wipeDirtyTicks(Long2ObjectMap<LevelChunkTicks<?>> containers, LongSet dirtyChunks)
	{
		for (Long2ObjectMap.Entry<LevelChunkTicks<?>> entry : containers.long2ObjectEntrySet())
		{
			if (dirtyChunks.contains(entry.getLongKey()))
			{
				entry.getValue().removeIf(tick -> true);
			}
		}
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
		this.fstest$markDirty(pos);
		return super.setBlock(pos, state, flags, recursionLeft);
	}

	@Override
	public void setBlockEntity(BlockEntity blockEntity)
	{
		// A block entity can be installed without any block change at that
		// position, so the block wipe would never see it.
		this.fstest$markDirtyBlockEntity(blockEntity.getBlockPos());
		super.setBlockEntity(blockEntity);
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
