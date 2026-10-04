package fstest.sim;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.datafixers.DataFixer;
import fstest.FstestMod;
import fstest.mixin.MinecraftServerLevelsAccessor;
import fstest.mixin.MinecraftServerTickAccessor;
import fstest.mixin.ServerLevelEntityManagerAccessor;
import net.minecraft.SystemReport;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Util;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * A second, real {@link MinecraftServer} (the approach simulatica uses) that
 * hosts the timed-mode simulated space: genuine void dimensions with a real
 * chunk pipeline, so every vanilla tick path runs unmodified. Never saves,
 * never touches the network, lives in a scratch directory that is wiped on
 * boot and shutdown.
 *
 * Booted lazily on the real server thread the first time a timed-mode
 * analysis runs (the datapack load costs a moment), shut down when the real
 * server closes.
 */
public final class SimServer extends MinecraftServer
{
	private static final String SCRATCH_DIR = "fstest-sim";
	private static final String SCRATCH_LEVEL_ID = "level";
	private static final int TICKING_MARGIN_CHUNKS = 2;
	/** How long to wait for a freshly force-loaded region to reach a ticking state before giving up. */
	private static final int MAX_PROMOTION_ATTEMPTS = 400;
	private static final int MAX_CHUNK_TASKS_PER_PUMP = 512;
	/** Chunk pipeline ticks spent letting released chunks actually unload. */
	private static final int UNLOAD_PUMP_ATTEMPTS = 8;
	private static final long TICK_BUDGET_NANOS = 50L * 1000L * 1000L;
	/**
	 * Fuse against runaway regions: forcing chunks allocates a ticket and a
	 * chunk holder per chunk on the caller thread, so an oversized box would
	 * freeze the server and exhaust the heap before the pump loop ever runs.
	 */
	private static final int MAX_FORCE_CHUNKS = 1024;

	@Nullable
	private static SimServer instance;

	private final LevelStorageSource.LevelStorageAccess storage;
	private final LocalSampleLogger tickTimeLogger = new LocalSampleLogger(1);

	private SimServer(Thread thread, LevelStorageSource.LevelStorageAccess storage, PackRepository packRepository,
	                  WorldStem worldStem, Services services, DataFixer fixerUpper)
	{
		super(thread, storage, packRepository, worldStem, Proxy.NO_PROXY, fixerUpper, services,
				NoopLevelLoadListener.INSTANCE);
		this.storage = storage;
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	/** The running simulation server, if one was booted. */
	public static @Nullable SimServer running()
	{
		return instance;
	}

	/** Boots the shared simulation server (once) or returns the running one. */
	public static SimServer getOrCreate(MinecraftServer realServer) throws Exception
	{
		if (instance != null)
		{
			return instance;
		}
		Path root = FabricLoader.getInstance().getGameDir().resolve(SCRATCH_DIR);
		deleteRecursively(root.resolve(SCRATCH_LEVEL_ID));
		Files.createDirectories(root);

		LevelStorageSource.LevelStorageAccess storage =
				LevelStorageSource.createDefault(root).createAccess(SCRATCH_LEVEL_ID);
		SimServer server;
		try
		{
			PackRepository packRepository = ServerPacksSource.createPackRepository(storage);
			WorldStem worldStem = loadWorldStem(packRepository, realServer);
			Services services = Services.create(new YggdrasilAuthenticationService(Proxy.NO_PROXY), root.toFile());

			server = new SimServer(Thread.currentThread(), storage, packRepository, worldStem, services,
					realServer.getFixerUpper());
			server.createSimulationLevels(realServer);
		}
		catch (Exception e)
		{
			try
			{
				storage.close();
			}
			catch (IOException ignored)
			{
			}
			throw e;
		}
		instance = server;
		FstestMod.LOGGER.info("[fstest] simulation server started with {} dimension(s)", server.simLevels().size());
		return server;
	}

	/** Shuts the simulation server down (real server close). Safe to call when never booted. */
	public static void shutdown()
	{
		SimServer server = instance;
		if (server == null)
		{
			return;
		}
		instance = null;
		for (ServerLevel level : server.getAllLevels())
		{
			try
			{
				level.close();
			}
			catch (IOException e)
			{
				FstestMod.LOGGER.warn("[fstest] failed to close simulation level {}", level.dimension().identifier(), e);
			}
		}
		try
		{
			server.storage.close();
		}
		catch (IOException e)
		{
			FstestMod.LOGGER.warn("[fstest] failed to release the simulation scratch world lock", e);
		}
		deleteRecursively(FabricLoader.getInstance().getGameDir().resolve(SCRATCH_DIR).resolve(SCRATCH_LEVEL_ID));
		FstestMod.LOGGER.info("[fstest] simulation server stopped");
	}

	// ------------------------------------------------------------------
	// Bootstrap
	// ------------------------------------------------------------------

	/**
	 * Real datapack load (so modded blocks/registries exist in the simulation)
	 * against hand-built void dimensions. The "main thread" executor is the
	 * calling thread itself: everything completes inline, no managed blocking.
	 */
	private static WorldStem loadWorldStem(PackRepository packRepository, MinecraftServer realServer) throws Exception
	{
		WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(
				new WorldLoader.PackConfig(packRepository, WorldDataConfiguration.DEFAULT, false, true),
				Commands.CommandSelection.INTEGRATED,
				LevelBasedPermissionSet.GAMEMASTER);

		CompletableFuture<WorldStem> future = WorldLoader.load(
				initConfig,
				context -> {
					Registry<LevelStem> none = SimDimensions.emptyStemRegistry();
					WorldDimensions.Complete dimensions = SimDimensions.create(context.datapackWorldgen()).bake(none);
					LevelSettings settings = new LevelSettings("fstest-simulation", GameType.CREATIVE, false,
							Difficulty.NORMAL, true,
							new GameRules(context.dataConfiguration().enabledFeatures()),
							context.dataConfiguration());
					WorldData worldData = new PrimaryLevelData(settings,
							new WorldOptions(realServer.getWorldData().worldGenOptions().seed(), false, false),
							dimensions.specialWorldProperty(), dimensions.lifecycle());
					return new WorldLoader.DataLoadOutput<>(worldData, dimensions.dimensionsRegistryAccess());
				},
				WorldStem::new,
				Util.backgroundExecutor(),
				Runnable::run);
		return future.get();
	}

	/**
	 * Builds one {@link SimLevel} per dimension. {@code createLevels()} is
	 * deliberately not used - it also searches for an initial spawn.
	 */
	private void createSimulationLevels(MinecraftServer realServer)
	{
		this.setPlayerList(new SimPlayerList(this, this.storage));

		WorldData worldData = this.getWorldData();
		ServerLevelData overworldData = worldData.overworldData();
		Registry<LevelStem> stems = this.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
		long seed = BiomeManager.obfuscateSeed(worldData.worldGenOptions().seed());
		Map<ResourceKey<Level>, ServerLevel> levels =
				((MinecraftServerLevelsAccessor) (Object) this).fstest$levels();

		// The overworld has to come first; the other dimensions derive from its data.
		SimLevel overworld = new SimLevel(this, Util.backgroundExecutor(), this.storage,
				overworldData, Level.OVERWORLD, stems.getValueOrThrow(LevelStem.OVERWORLD),
				seed, List.of(), true, null);
		levels.put(Level.OVERWORLD, overworld);

		RandomSequences sequences = overworld.getRandomSequences();
		for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : stems.entrySet())
		{
			if (entry.getKey().equals(LevelStem.OVERWORLD))
			{
				continue;
			}
			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, entry.getKey().identifier());
			levels.put(dimension, new SimLevel(this, Util.backgroundExecutor(), this.storage,
					new DerivedLevelData(worldData, overworldData), dimension, entry.getValue(),
					seed, List.of(), false, sequences));
		}
	}

	/** The level for a dimension, falling back to the simulation overworld when absent. */
	public SimLevel levelFor(ResourceKey<Level> dimension)
	{
		ServerLevel level = this.getLevel(dimension);
		return (SimLevel) (level != null ? level : this.overworld());
	}

	// ------------------------------------------------------------------
	// Region force-load and promotion (chunk pipeline on the caller thread)
	// ------------------------------------------------------------------

	/** Force-loads every chunk of the box (plus a ticking margin) and pumps the pipeline until it ticks. */
	public void forceAndPump(SimLevel level, BlockPos min, BlockPos max)
	{
		ChunkPos cMin = new ChunkPos(min);
		ChunkPos cMax = new ChunkPos(max);
		long wanted = (long) (cMax.x - cMin.x + 1 + 2 * TICKING_MARGIN_CHUNKS)
				* (cMax.z - cMin.z + 1 + 2 * TICKING_MARGIN_CHUNKS);
		if (wanted > MAX_FORCE_CHUNKS)
		{
			throw new IllegalArgumentException("[fstest] refusing to force-load " + wanted
					+ " chunks for region [" + cMin.x + ", " + cMin.z + "]..[" + cMax.x + ", " + cMax.z
					+ "] (limit " + MAX_FORCE_CHUNKS + ")");
		}
		for (int cx = cMin.x - TICKING_MARGIN_CHUNKS; cx <= cMax.x + TICKING_MARGIN_CHUNKS; cx++)
		{
			for (int cz = cMin.z - TICKING_MARGIN_CHUNKS; cz <= cMax.z + TICKING_MARGIN_CHUNKS; cz++)
			{
				level.fstest$forceChunk(cx, cz);
			}
		}
		for (int attempt = 0; attempt < MAX_PROMOTION_ATTEMPTS; attempt++)
		{
			if (allTicking(level, cMin, cMax))
			{
				return;
			}
			grantTaskBudget();
			this.runAllTasks();
			drainChunkSource(level);
			level.getChunkSource().tick(() -> true, false);
			((ServerLevelEntityManagerAccessor) (ServerLevel) level).fstest$entityManager().tick();
		}
		FstestMod.LOGGER.warn("[fstest] simulated region [{}, {}]..[{}, {}] did not become ticking within {} attempts",
				cMin.x, cMin.z, cMax.x, cMax.z, MAX_PROMOTION_ATTEMPTS);
	}

	private static boolean allTicking(SimLevel level, ChunkPos cMin, ChunkPos cMax)
	{
		for (int cx = cMin.x; cx <= cMax.x; cx++)
		{
			for (int cz = cMin.z; cz <= cMax.z; cz++)
			{
				long key = ChunkPos.asLong(cx, cz);
				if (!level.areEntitiesLoaded(key) || !level.getChunkSource().isPositionTicking(key))
				{
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Releases every chunk ticket of one box and lets the pipeline unload it.
	 * A distant P/PD box would otherwise stay resident until the analysis ends,
	 * so a long batch would keep one region's worth of chunks alive per run.
	 */
	public void releaseBox(SimLevel level, BlockPos min, BlockPos max)
	{
		ChunkPos cMin = new ChunkPos(min);
		ChunkPos cMax = new ChunkPos(max);
		for (int cx = cMin.x - TICKING_MARGIN_CHUNKS; cx <= cMax.x + TICKING_MARGIN_CHUNKS; cx++)
		{
			for (int cz = cMin.z - TICKING_MARGIN_CHUNKS; cz <= cMax.z + TICKING_MARGIN_CHUNKS; cz++)
			{
				level.fstest$releaseChunk(cx, cz);
			}
		}
		pumpUnloads(level);
	}

	/** Releases every chunk ticket the analysis took and pumps the unloads. */
	public void releaseAll()
	{
		for (SimLevel level : this.simLevels())
		{
			level.fstest$releaseAllChunks();
		}
		for (SimLevel level : this.simLevels())
		{
			pumpUnloads(level);
		}
	}

	/**
	 * Ticks the chunk pipeline far enough for the unqueued chunks to actually
	 * be dropped: unloading only happens in {@code ChunkMap#tick} ->
	 * {@code processUnloads}, so draining the task queues alone (as the old
	 * release path did) released the tickets but never freed the memory.
	 */
	private void pumpUnloads(SimLevel level)
	{
		for (int i = 0; i < UNLOAD_PUMP_ATTEMPTS; i++)
		{
			grantTaskBudget();
			this.runAllTasks();
			drainChunkSource(level);
			level.getChunkSource().tick(() -> true, false);
		}
	}

	private List<SimLevel> simLevels()
	{
		List<SimLevel> levels = new ArrayList<>();
		for (ServerLevel level : this.getAllLevels())
		{
			if (level instanceof SimLevel simLevel)
			{
				levels.add(simLevel);
			}
		}
		return levels;
	}

	private void grantTaskBudget()
	{
		MinecraftServerTickAccessor budget = (MinecraftServerTickAccessor) (Object) this;
		budget.fstest$setTickCount(budget.fstest$getTickCount() + 1);
		budget.fstest$setNextTickTimeNanos(Util.getNanos() + TICK_BUDGET_NANOS);
	}

	/** Bounded so a task that keeps queueing more work cannot hang the real server thread. */
	private static void drainChunkSource(SimLevel level)
	{
		for (int i = 0; i < MAX_CHUNK_TASKS_PER_PUMP && level.getChunkSource().pollTask(); i++)
		{
			// pollTask does the work; the loop only bounds it.
		}
	}

	// ------------------------------------------------------------------
	// MinecraftServer plumbing (nothing real is wired up)
	// ------------------------------------------------------------------

	@Override
	protected boolean initServer()
	{
		return true;
	}

	@Override
	public LevelBasedPermissionSet operatorUserPermissions()
	{
		return LevelBasedPermissionSet.GAMEMASTER;
	}

	@Override
	public PermissionSet getFunctionCompilationPermissions()
	{
		return LevelBasedPermissionSet.GAMEMASTER;
	}

	@Override
	public boolean shouldRconBroadcast()
	{
		return false;
	}

	@Override
	protected LocalSampleLogger getTickTimeLogger()
	{
		return this.tickTimeLogger;
	}

	@Override
	public boolean isTickTimeLoggingEnabled()
	{
		return false;
	}

	@Override
	public SystemReport fillServerSystemReport(SystemReport systemReport)
	{
		systemReport.setDetail("Type", "fstest simulation server");
		return systemReport;
	}

	@Override
	public boolean isDedicatedServer()
	{
		return false;
	}

	@Override
	public int getRateLimitPacketsPerSecond()
	{
		return 0;
	}

	@Override
	public boolean useNativeTransport()
	{
		return false;
	}

	@Override
	public boolean isPublished()
	{
		return false;
	}

	@Override
	public boolean shouldInformAdmins()
	{
		return false;
	}

	@Override
	public boolean isSingleplayerOwner(NameAndId nameAndId)
	{
		return false;
	}

	@Override
	public int getMaxPlayers()
	{
		return 0;
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static void deleteRecursively(Path path)
	{
		if (!Files.exists(path))
		{
			return;
		}
		try (Stream<Path> walk = Files.walk(path))
		{
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try
				{
					Files.delete(p);
				}
				catch (IOException ignored)
				{
				}
			});
		}
		catch (IOException e)
		{
			FstestMod.LOGGER.warn("[fstest] could not clear the simulation scratch world at {}", path, e);
		}
	}

	private static final class NoopLevelLoadListener implements LevelLoadListener
	{
		static final NoopLevelLoadListener INSTANCE = new NoopLevelLoadListener();

		@Override
		public void start(LevelLoadListener.Stage stage, int i)
		{
		}

		@Override
		public void update(LevelLoadListener.Stage stage, int i, int j)
		{
		}

		@Override
		public void finish(LevelLoadListener.Stage stage)
		{
		}

		@Override
		public void updateFocus(ResourceKey<Level> resourceKey, ChunkPos chunkPos)
		{
		}
	}

	private static final class SimPlayerList extends PlayerList
	{
		SimPlayerList(MinecraftServer server, LevelStorageSource.LevelStorageAccess storage)
		{
			super(server, server.registries(), storage.createPlayerStorage(), new EmptyNotificationService());
		}
	}
}
