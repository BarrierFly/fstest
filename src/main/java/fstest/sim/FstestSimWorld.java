package fstest.sim;

import fstest.record.CapturedDispatch;
import fstest.record.RecorderHub;
import fstest.record.RemovalSideEffect;
import fstest.record.UpdateKind;
import fstest.util.NeighborCascades;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The simulated space: a chunkless virtual {@link Level} holding a
 * transformed snapshot of the region around the tested operation.
 *
 * Isolation requirements from the spec:
 * - independent seeded random sequence (never touches the real world's RNG)
 * - no statistics / packets / sound output
 * - pre-existing scheduled ticks and block events are copied in for queries
 *   but never executed inside the operation window
 * - nothing written here can ever reach the real world
 */
public final class FstestSimWorld extends Level
{
	public static final int MAX_CHAINED_NEIGHBOR_UPDATES = 1_000_000; // vanilla hardcodes this value on the server

	private final MinecraftServer server;
	private final ServerLevel source;
	private final SimLevelData simData;

	// block storage: one sparse section array per 16^3 section, null = air
	private final Map<Long, BlockState[]> sections = new HashMap<>();
	// loaded envelope (sim space), grown while filling; writes outside are rejected like unloaded chunks
	private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
	private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

	private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
	final SimTickQueue<Block> blockQueueForBuild;
	final SimTickQueue<Fluid> fluidQueueForBuild;
	private final Set<BlockEventData> blockEvents = new LinkedHashSet<>();
	private final EnvironmentAttributeSystem environmentAttributes;
	private final WorldBorder worldBorder = new WorldBorder();
	private LevelLightEngine lightEngineStub;
	private long capturedRandomSeed;
	private boolean capturedSeedKnown;
	/** Removal-hook dispatches captured from reality, keyed by removal position. */
	private Map<BlockPos, Deque<RemovalSideEffect>> removalSideEffects = Map.of();
	/**
	 * Positions of removals whose cascade context differed from the captured one.
	 * Non-empty means those removals' dispatches entered the neighbour-update
	 * manual stack in a different context than in reality, so their relative
	 * timing may differ - surfaced (with coordinates) in the per-run log stats.
	 */
	private final List<BlockPos> sideEffectContextMismatches = new ArrayList<>();
	// replay diagnostics, surfaced in the simulation log header so an empty
	// baseline can be attributed (cascade never started vs nothing subscribed)
	private long setBlockCalls;
	private long neighborUpdateDispatches;

	public FstestSimWorld(ServerLevel source, MinecraftServer server, SimLevelData data, Holder<DimensionType> dimensionType,
	              ResourceKey<Level> dimensionKey)
	{
		super(data, dimensionKey, server.registryAccess(), dimensionType, false, false, 0L, MAX_CHAINED_NEIGHBOR_UPDATES);
		this.source = source;
		this.server = server;
		this.simData = data;
		this.blockQueueForBuild = new SimTickQueue<>(this, true);
		this.fluidQueueForBuild = new SimTickQueue<>(this, false);
		this.environmentAttributes = EnvironmentAttributeSystem.builder().addDefaultLayers(this).build();
	}

	// ------------------------------------------------------------------
	// Storage primitives (template-space fill + runtime access)
	// ------------------------------------------------------------------

	public void storeState(BlockPos pos, BlockState state)
	{
		long sectionKey = SectionPos.of(pos).asLong();
		BlockState[] arr = this.sections.computeIfAbsent(sectionKey, k -> new BlockState[4096]);
		arr[index(pos)] = state.isAir() ? null : state;
		if (pos.getX() < this.minX) this.minX = pos.getX();
		if (pos.getY() < this.minY) this.minY = pos.getY();
		if (pos.getZ() < this.minZ) this.minZ = pos.getZ();
		if (pos.getX() > this.maxX) this.maxX = pos.getX();
		if (pos.getY() > this.maxY) this.maxY = pos.getY();
		if (pos.getZ() > this.maxZ) this.maxZ = pos.getZ();
	}

	public void addBlockEntity(BlockPos pos, BlockEntity be)
	{
		this.blockEntities.put(pos.immutable(), be);
	}

	public void injectBlockEvent(BlockEventData event)
	{
		this.blockEvents.add(event);
	}

	public SimTickQueue<Block> blockQueue()
	{
		return this.blockQueueForBuild;
	}

	public SimTickQueue<Fluid> fluidQueue()
	{
		return this.fluidQueueForBuild;
	}

	/**
	 * Installs the removal side effects captured from reality, transformed into
	 * simulation space and keyed by the position whose removal produced them.
	 * Values are consumed in capture order, so repeated removals of the same
	 * position replay their effects one by one.
	 */
	public void setRemovalSideEffects(Map<BlockPos, Deque<RemovalSideEffect>> effects)
	{
		this.removalSideEffects = effects;
	}

	public List<BlockPos> sideEffectContextMismatches()
	{
		return this.sideEffectContextMismatches;
	}

	/** Re-issues the captured removal-hook dispatches for one removal, if any. */
	private void issueRemovalSideEffects(BlockPos pos)
	{
		Deque<RemovalSideEffect> pending = this.removalSideEffects.get(pos);
		if (pending == null || pending.isEmpty())
		{
			return;
		}
		RemovalSideEffect effect = pending.poll();
		if (NeighborCascades.isBusy(this) != effect.cascadeBusy())
		{
			// the two removals no longer sit in the same cascade context, so the
			// dispatches below will not enter the manual stack the same way
			this.sideEffectContextMismatches.add(pos.immutable());
		}
		for (CapturedDispatch dispatch : effect.dispatches())
		{
			this.issueDispatch(dispatch.kind(), dispatch.pos(), dispatch.block(),
					dispatch.exceptDir(), dispatch.orientation(), dispatch.movedByPiston());
		}
	}

	/**
	 * Performs one block-update dispatch inside the simulated space. Shared by
	 * the removal-side-effect replay (here) and the captured root dispatches
	 * (ReplayEngine), so both go through exactly the same code path.
	 */
	public void issueDispatch(UpdateKind kind, BlockPos pos, Block block, @Nullable Direction exceptDir,
	                          @Nullable Orientation orientation, boolean movedByPiston)
	{
		switch (kind)
		{
			case BLOCK_UPDATE -> this.updateNeighborsAt(pos, block, orientation);
			case BLOCK_UPDATE_EXCEPT -> this.updateNeighborsAtExceptFromFacing(pos, block, exceptDir, orientation);
			case SINGLE_BLOCK_UPDATE ->
					// the receiving state is re-read from the sim world: it equals the
					// inverse-transformed captured state and avoids transform bookkeeping
					this.neighborChanged(this.getBlockState(pos), pos, block, orientation, movedByPiston);
			case COMPARATOR_UPDATE -> this.updateNeighbourForOutputSignal(pos, block);
		}
	}

	public void setCapturedRandomSeed(long seed, boolean known)
	{
		this.capturedRandomSeed = seed;
		this.capturedSeedKnown = known;
	}

	boolean envelopeContains(BlockPos pos)
	{
		return pos.getX() >= this.minX && pos.getX() <= this.maxX
				&& pos.getY() >= this.minY && pos.getY() <= this.maxY
				&& pos.getZ() >= this.minZ && pos.getZ() <= this.maxZ;
	}

	private static int index(BlockPos pos)
	{
		return ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
	}

	// ------------------------------------------------------------------
	// Random isolation
	// ------------------------------------------------------------------

	/** Seeds the world's own random from the state captured in reality (best effort). */
	public void reseedRandomFromCapture(int runSalt)
	{
		long base = this.capturedSeedKnown ? this.capturedRandomSeed : 0x9E3779B97F4A7C15L;
		this.random.setSeed(base ^ (runSalt * 0x5DEECE66DL));
	}

	static long tryCaptureRealRandomSeed(Level real)
	{
		try
		{
			RandomSource rng = real.random;
			if (rng instanceof LegacyRandomSource legacy)
			{
				Field seedField = LegacyRandomSource.class.getDeclaredField("seed");
				seedField.setAccessible(true);
				Object atomic = seedField.get(legacy);
				if (atomic instanceof AtomicLong atomicLong)
				{
					return atomicLong.get(); // read only - never advances the real sequence
				}
			}
		}
		catch (Throwable ignored)
		{
		}
		return 0L;
	}

	// ------------------------------------------------------------------
	// setBlock - replicates Level.setBlock + LevelChunk.setBlockState essentials
	// ------------------------------------------------------------------

	@Override
	public boolean setBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft)
	{
		this.setBlockCalls++;
		RecorderHub.beforeSetBlock(this, pos.immutable(), newState, flags);
		RecorderHub.enterSetBlock();
		try
		{
			return this.setBlockInternal(pos.immutable(), newState, flags, recursionLeft);
		}
		finally
		{
			RecorderHub.exitSetBlock();
		}
	}

	public long setBlockCalls()
	{
		return this.setBlockCalls;
	}

	public long neighborUpdateDispatches()
	{
		return this.neighborUpdateDispatches;
	}

	private boolean setBlockInternal(BlockPos pos, BlockState state, int flags, int recursionLeft)
	{
		// The simulated space deliberately ignores the dimension's build height
		// (spec: "the custom virtual world ignores world height limits"): a
		// vertical P offset may legitimately place the captured region's edges
		// outside [minY, maxY], and those blocks are still stored and must stay
		// readable. The built envelope alone bounds writes.
		if (!this.envelopeContains(pos))
		{
			return false;
		}
		BlockState old = this.getBlockState(pos);
		if (old == state || old.equals(state))
		{
			return false; // mirrors "no change" of LevelChunk.setBlockState
		}

		boolean differentBlock = !old.is(state.getBlock());
		boolean movingByPiston = (flags & 64) != 0;

		// --- chunk-equivalent phase: store, BE teardown, removal side effects, onPlace ---
		// The state is stored before the removal hook runs, exactly like vanilla
		// (LevelChunk#setBlockState replaces the section state first), so the hook
		// and the dispatches it issues observe the post-removal world.
		this.storeState(pos, state);

		BlockEntity oldBe = differentBlock ? this.blockEntities.remove(pos) : null;
		if (oldBe != null)
		{
			oldBe.setRemoved();
		}
		if ((differentBlock || state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock)
				&& ((flags & 1) != 0 || movingByPiston))
		{
			// Vanilla runs old.affectNeighborsAfterRemoval(serverLevel, ...) here, but
			// that hook demands a ServerLevel the chunkless simulated space cannot be.
			// The hook's observable effect was captured from reality per removal
			// (RecorderHub's removal window -> RemovalSideEffect); re-issuing it at
			// this exact point keeps the micro-timing order and cascade context.
			this.issueRemovalSideEffects(pos);
		}

		if ((flags & 512) == 0)
		{
			state.onPlace(this, pos, old, movingByPiston);
		}

		if (state.hasBlockEntity())
		{
			BlockEntity existing = this.blockEntities.get(pos);
			if (existing != null && !existing.isValidBlockState(state))
			{
				this.blockEntities.remove(pos);
				existing = null;
			}
			if (existing == null)
			{
				// frozen space: hydrate an empty BE if the block declares one, but never tick it
				if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)
				{
					BlockEntity created = entityBlock.newBlockEntity(pos, state);
					if (created != null)
					{
						created.setLevel(this);
						created.clearRemoved();
						this.blockEntities.put(pos.immutable(), created);
					}
				}
			}
			else
			{
				existing.setBlockState(state);
			}
		}

		// --- notification phase (mirrors inline logic in Level.setBlock) ---
		if ((flags & 2) != 0)
		{
			this.sendBlockUpdated(pos, old, state, flags); // no-op in the simulated space
		}
		if ((flags & 1) != 0)
		{
			this.updateNeighborsAt(pos, old.getBlock());
			if (state.hasAnalogOutputSignal())
			{
				this.updateNeighbourForOutputSignal(pos, state.getBlock());
			}
		}

		// --- shape update phase ---
		int shapeFlags = flags & -34;
		if ((flags & 16) == 0 && recursionLeft > 0)
		{
			old.updateIndirectNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
			state.updateNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
			state.updateIndirectNeighbourShapes(this, pos, shapeFlags, recursionLeft - 1);
		}
		return true;
	}

	@Override
	public void blockEvent(BlockPos pos, Block block, int eventID, int eventParam)
	{
		// creation attempts are recorded as events on both worlds (spec section
		// five); success = the pending set accepted the entry, mirroring the
		// real-side queue-size delta
		boolean success = this.blockEvents.add(new BlockEventData(pos.immutable(), block, eventID, eventParam));
		RecorderHub.onBlockEventCreate(this, pos, block, eventID, eventParam, success);
	}

	// ------------------------------------------------------------------
	// Neighbor updates - copied from ServerLevel's overrides (base Level is empty!)
	// ------------------------------------------------------------------

	@Override
	public void updateNeighborsAt(BlockPos pos, Block block)
	{
		this.updateNeighborsAt(pos, block, net.minecraft.world.level.redstone.ExperimentalRedstoneUtils.initialOrientation(this, null, null));
	}

	@Override
	public void updateNeighborsAt(BlockPos pos, Block block, @Nullable Orientation orientation)
	{
		this.neighborUpdateDispatches++;
		RecorderHub.onNeighborDispatch(this, UpdateKind.BLOCK_UPDATE,
				pos, block, null, orientation, false, true);
		this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, null, orientation);
	}

	@Override
	public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction facing, @Nullable Orientation orientation)
	{
		this.neighborUpdateDispatches++;
		RecorderHub.onNeighborDispatch(this, UpdateKind.BLOCK_UPDATE_EXCEPT,
				pos, block, facing, orientation, false, true);
		this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, facing, orientation);
	}

	@Override
	public void neighborChanged(BlockPos pos, Block neighborBlock, @Nullable Orientation orientation)
	{
		this.neighborUpdateDispatches++;
		RecorderHub.onNeighborDispatch(this, UpdateKind.SINGLE_BLOCK_UPDATE,
				pos, neighborBlock, null, orientation, false, true);
		this.neighborUpdater.neighborChanged(pos, neighborBlock, orientation);
	}

	@Override
	public void neighborChanged(BlockState state, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston)
	{
		this.neighborUpdateDispatches++;
		RecorderHub.onNeighborDispatch(this, UpdateKind.SINGLE_BLOCK_UPDATE,
				pos, neighborBlock, null, orientation, movedByPiston, true);
		this.neighborUpdater.neighborChanged(state, pos, neighborBlock, orientation, movedByPiston);
	}

	// ------------------------------------------------------------------
	// Chunkless accessors
	// ------------------------------------------------------------------

	@Override
	public BlockState getBlockState(BlockPos pos)
	{
		// Build height is intentionally NOT checked (spec: the virtual world
		// ignores world height limits); only the built envelope delimits the
		// known region, and everything outside it reads as void air.
		if (!this.envelopeContains(pos))
		{
			return Blocks.VOID_AIR.defaultBlockState();
		}
		BlockState[] arr = this.sections.get(SectionPos.of(pos).asLong());
		if (arr == null)
		{
			return Blocks.VOID_AIR.defaultBlockState();
		}
		BlockState st = arr[index(pos)];
		return st == null ? Blocks.AIR.defaultBlockState() : st;
	}

	@Override
	public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos)
	{
		return this.getBlockState(pos).getFluidState();
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos)
	{
		return this.envelopeContains(pos) ? this.blockEntities.get(pos.immutable()) : null;
	}

	@Override
	public boolean hasChunkAt(int x, int z)
	{
		// comparator rescans and similar paths guard on chunk presence
		return true;
	}

	@Override
	public boolean hasChunkAt(BlockPos pos)
	{
		return true;
	}

	@Override
	public int getHeight(Heightmap.Types type, int x, int z)
	{
		for (int y = this.maxY; y >= this.minY; y--)
		{
			if (!this.getBlockState(new BlockPos(x, y, z)).isAir())
			{
				return y + 1;
			}
		}
		return this.getMinY();
	}

	@Override
	public void blockEntityChanged(BlockPos pos)
	{
		// no persistence in the simulated space
	}

	// ------------------------------------------------------------------
	// Tick queues & block events accessors
	// ------------------------------------------------------------------

	@Override
	public LevelTickAccess<Block> getBlockTicks()
	{
		return this.blockQueueForBuild;
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks()
	{
		return this.fluidQueueForBuild;
	}

	public Set<BlockEventData> simBlockEvents()
	{
		return this.blockEvents;
	}

	// ------------------------------------------------------------------
	// Light: v1 limitation - constant neutral values, documented in README
	// ------------------------------------------------------------------

	@Override
	public LevelLightEngine getLightEngine()
	{
		if (this.lightEngineStub == null)
		{
			this.lightEngineStub = new LevelLightEngine(new net.minecraft.world.level.chunk.LightChunkGetter()
			{
				@Override
				public @Nullable LightChunk getChunkForLighting(int x, int z)
				{
					return null;
				}

				@Override
				public net.minecraft.world.level.BlockGetter getLevel()
				{
					return FstestSimWorld.this;
				}
			}, false, false);
		}
		return this.lightEngineStub;
	}

	@Override
	public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos)
	{
		return layer == net.minecraft.world.level.LightLayer.SKY ? 15 : 0;
	}

	@Override
	public int getRawBrightness(BlockPos pos, int amount)
	{
		return Math.max(0, 15 - amount);
	}

	@Override
	public boolean canSeeSky(BlockPos pos)
	{
		return true;
	}

	@Override
	public int getSkyDarken()
	{
		return 0;
	}

	@Override
	public float getShade(Direction direction, boolean shade)
	{
		return 1.0F;
	}

	@Override
	public int getSeaLevel()
	{
		return 63;
	}

	@Override
	public FeatureFlagSet enabledFeatures()
	{
		return this.source.enabledFeatures();
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z)
	{
		return this.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
	}

	// ------------------------------------------------------------------
	// Environment passthrough (frozen copies or shared read-only services)
	// ------------------------------------------------------------------

	@Override
	public EnvironmentAttributeSystem environmentAttributes()
	{
		return this.environmentAttributes;
	}

	@Override
	public TickRateManager tickRateManager()
	{
		return this.server.tickRateManager();
	}

	@Override
	public RecipeAccess recipeAccess()
	{
		return this.source.recipeAccess();
	}

	@Override
	public PotionBrewing potionBrewing()
	{
		return this.server.potionBrewing();
	}

	@Override
	public net.minecraft.world.level.block.entity.FuelValues fuelValues()
	{
		return this.server.fuelValues();
	}

	@Override
	public Scoreboard getScoreboard()
	{
		return this.server.getScoreboard();
	}

	@Override
	public @Nullable MinecraftServer getServer()
	{
		return this.server;
	}

	@Override
	public WorldBorder getWorldBorder()
	{
		return this.worldBorder;
	}

	@Override
	public List<? extends net.minecraft.world.entity.player.Player> players()
	{
		return List.of();
	}

	@Override
	protected LevelEntityGetter<Entity> getEntities()
	{
		return EmptyEntityGetter.INSTANCE;
	}

	@Override
	public @Nullable Entity getEntity(int id)
	{
		return null;
	}

	@Override
	public void gameEvent(net.minecraft.core.Holder<GameEvent> gameEvent, net.minecraft.world.phys.Vec3 position, GameEvent.Context context)
	{
		// no game events inside the simulation
	}

	@Override
	public void levelEvent(@Nullable Entity entity, int type, BlockPos pos, int data)
	{
		// suppressed: would broadcast effects to clients in a real world
	}

	@Override
	public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags)
	{
		// no clients to notify
	}

	@Override
	public void playSeededSound(@Nullable Entity source, double x, double y, double z,
	                            Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed)
	{
		// sounds are computed by vanilla call sites but never sent anywhere
	}

	@Override
	public void playSeededSound(@Nullable Entity source, Entity except, Holder<SoundEvent> sound,
	                            SoundSource category, float volume, float pitch, long seed)
	{
	}

	@Override
	public void explode(@Nullable Entity source, @Nullable DamageSource damageSource,
	                    @Nullable ExplosionDamageCalculator damageCalculator,
	                    double x, double y, double z, float radius, boolean fire,
	                    Level.ExplosionInteraction explosionInteraction,
	                    ParticleOptions smallParticles, ParticleOptions largeParticles,
	                    net.minecraft.util.random.WeightedList<net.minecraft.core.particles.ExplosionParticleInfo> blockParticles,
	                    Holder<SoundEvent> explosionSound)
	{
		throw new UnsupportedOperationException("Fengshui Tester v1 does not simulate explosions");
	}

	@Override
	public String gatherChunkSourceStats()
	{
		return "fstest simulated space";
	}

	@Override
	public LevelData.RespawnData getRespawnData()
	{
		return this.simData.getRespawnData();
	}

	@Override
	public void setRespawnData(LevelData.RespawnData respawnData)
	{
		this.simData.setSpawn(respawnData);
	}

	@Override
	public void destroyBlockProgress(int breakerId, BlockPos pos, int progress)
	{
	}

	@Override
	public @Nullable MapItemSavedData getMapData(MapId mapId)
	{
		return null;
	}

	@Override
	public Collection<EnderDragonPart> dragonParts()
	{
		return List.of();
	}

	// ------------------------------------------------------------------
	// Chunk plumbing stubs (nothing in the redstone window should reach these)
	// ------------------------------------------------------------------

	@Override
	public ChunkSource getChunkSource()
	{
		throw new UnsupportedOperationException("chunk operations are not supported inside the simulated space");
	}

	private static final class EmptyEntityGetter implements LevelEntityGetter<Entity>
	{
		static final EmptyEntityGetter INSTANCE = new EmptyEntityGetter();

		@Override
		public @Nullable Entity get(int id)
		{
			return null;
		}

		@Override
		public @Nullable Entity get(java.util.UUID uuid)
		{
			return null;
		}

		@Override
		public Iterable<Entity> getAll()
		{
			return List.of();
		}

		@Override
		public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity, U> test,
		                                   net.minecraft.util.AbortableIterationConsumer<U> consumer)
		{
		}

		@Override
		public void get(AABB boundingBox, java.util.function.Consumer<Entity> consumer)
		{
		}

		@Override
		public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity, U> test,
		                                   AABB bounds, net.minecraft.util.AbortableIterationConsumer<U> consumer)
		{
		}
	}
}
