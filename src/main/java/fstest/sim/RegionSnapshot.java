package fstest.sim;

import fstest.config.FstestConfig;
import fstest.mixin.LevelTicksAccessor;
import fstest.mixin.ServerLevelBlockEventsAccessor;
import fstest.record.Markers;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw (untransformed, template-space relative) snapshot of the region around
 * a tested operation: block states, frozen block entity NBT, pre-existing
 * scheduled ticks / block events, and the real world's random sequence state.
 *
 * The region is either the anchor-centred range cube (instant mode and MTR
 * mode without a selection) or the explicit {@link FstestConfig.Area} selection
 * (MTR mode); block positions are stored relative to the anchor either way.
 */
public final class RegionSnapshot
{
	public static final int WORLD_H_BOUND = 30_000_000;

	public final BlockPos anchor;
	public final int range;
	/** Real-space selection rectangle; null = anchor-centred range cube. */
	public final FstestConfig.Area area;
	/** Relative positions -> states (air omitted). */
	public final Map<BlockPos, BlockState> states = new HashMap<>();
	/** Relative positions -> frozen BE NBT. */
	public final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
	public final List<ScheduledTick<Block>> blockTicks = new ArrayList<>();
	public final List<ScheduledTick<Fluid>> fluidTicks = new ArrayList<>();
	public final List<BlockEventData> blockEvents = new ArrayList<>();
	public long realRandomSeed;
	public boolean seedKnown;
	/**
	 * Whether the captured region holds at least one monitored block (a wool
	 * marker accepted by the colour filter, or a registered target). Timed mode
	 * uses this as its trigger gate: an operation whose effect only shows up
	 * after a few simulated ticks records nothing in the instant window, so
	 * "the region has something to observe" is what decides whether a run is
	 * worth simulating.
	 */
	public boolean hasMonitoredBlock;
	/** Real world's game/day time at snapshot time (the simulated clock starts here). */
	public final long gameTime;
	public final long dayTime;

	/** Selection-space bounds, clamped to the dimension/world envelope (real coordinates). */
	private final int minX;
	private final int minY;
	private final int minZ;
	private final int maxX;
	private final int maxY;
	private final int maxZ;

	private RegionSnapshot(BlockPos anchor, int range, FstestConfig.Area area,
	                       int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
	                       long gameTime, long dayTime)
	{
		this.anchor = anchor.immutable();
		this.range = range;
		this.area = area;
		this.gameTime = gameTime;
		this.dayTime = dayTime;
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
	}

	/** Anchor-centred cube (instant window / MTR fallback). */
	public static RegionSnapshot capture(ServerLevel level, MinecraftServer server, BlockPos anchor, int range)
	{
		DimensionType dim = level.dimensionType();
		int minY = Math.max(dim.minY(), anchor.getY() - range);
		int maxY = Math.min(dim.minY() + dim.height() - 1, anchor.getY() + range);
		int minX = Math.max(-WORLD_H_BOUND + 1, anchor.getX() - range);
		int maxX = Math.min(WORLD_H_BOUND - 1, anchor.getX() + range);
		int minZ = Math.max(-WORLD_H_BOUND + 1, anchor.getZ() - range);
		int maxZ = Math.min(WORLD_H_BOUND - 1, anchor.getZ() + range);
		return captureBoxed(level, server, anchor, range, null, minX, minY, minZ, maxX, maxY, maxZ,
				level.getGameTime(), level.getDayTime());
	}

	/** Explicit area selection (MTR mode). The area is clamped to the dimension's legal envelope. */
	public static RegionSnapshot capture(ServerLevel level, MinecraftServer server, BlockPos anchor, FstestConfig.Area area)
	{
		DimensionType dim = level.dimensionType();
		int minY = Math.max(dim.minY(), area.pos1().getY());
		int maxY = Math.min(dim.minY() + dim.height() - 1, area.pos2().getY());
		int minX = Math.max(-WORLD_H_BOUND + 1, area.pos1().getX());
		int maxX = Math.min(WORLD_H_BOUND - 1, area.pos2().getX());
		int minZ = Math.max(-WORLD_H_BOUND + 1, area.pos1().getZ());
		int maxZ = Math.min(WORLD_H_BOUND - 1, area.pos2().getZ());
		if (minX > maxX || minZ > maxZ || minY > maxY)
		{
			throw new IllegalArgumentException("test area is empty after clamping to the dimension");
		}
		return captureBoxed(level, server, anchor, -1, area, minX, minY, minZ, maxX, maxY, maxZ,
				level.getGameTime(), level.getDayTime());
	}

	private static RegionSnapshot captureBoxed(ServerLevel level, MinecraftServer server, BlockPos anchor,
	                                           int range, FstestConfig.Area area,
	                                           int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
	                                           long gameTime, long dayTime)
	{
		RegionSnapshot snap = new RegionSnapshot(anchor, range, area, minX, minY, minZ, maxX, maxY, maxZ,
				gameTime, dayTime);
		for (int x = minX; x <= maxX; x++)
		{
			for (int z = minZ; z <= maxZ; z++)
			{
				for (int y = minY; y <= maxY; y++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.isAir())
					{
						continue;
					}
					BlockPos rel = subtract(pos, anchor);
					snap.states.put(rel, state);
					if (state.hasBlockEntity())
					{
						BlockEntity be = level.getBlockEntity(pos);
						if (be != null)
						{
							snap.blockEntities.put(rel, be.saveWithFullMetadata(server.registryAccess()));
						}
					}
				}
			}
		}

		copyScheduledTicks(level, snap);
		copyBlockEvents(level, snap);
		snap.hasMonitoredBlock = detectMonitoredBlock(level, snap);

		snap.realRandomSeed = FstestSimWorld.tryCaptureRealRandomSeed(level);
		snap.seedKnown = true; // best effort; an unreadable seed falls back to a fixed base
		return snap;
	}

	/**
	 * Scans the already-captured region for monitored blocks. Reuses
	 * {@link #states} instead of walking the box again, and pays the colour
	 * lookups only for positions that can host a subscription at all.
	 *
	 * <p>Covers all three ways a position becomes monitored: a wool-marked
	 * component, a registered target, and the end-rod rule, which subscribes the
	 * block an end rod planted on wool points at - that neighbour is often air
	 * and therefore absent from {@link #states}, so rods are resolved by their
	 * pointed-at position instead.
	 */
	private static boolean detectMonitoredBlock(ServerLevel level, RegionSnapshot snap)
	{
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (Map.Entry<BlockPos, BlockState> entry : snap.states.entrySet())
		{
			BlockPos real = entry.getKey().offset(snap.anchor);
			Block block = entry.getValue().getBlock();
			if (Markers.canHostSubscription(block) && Markers.subscriptionAt(level, real).subscribesOperations())
			{
				return true;
			}
			if (block == Blocks.END_ROD)
			{
				Direction facing = entry.getValue().getValue(BlockStateProperties.FACING);
				// the rod subscribes what it faces, when it stands on accepted wool
				cursor.set(real.getX() + facing.getStepX(), real.getY() + facing.getStepY(),
						real.getZ() + facing.getStepZ());
				if (snap.containsReal(cursor)
						&& Markers.woolColorAt(level, real.relative(facing.getOpposite())) != null)
				{
					return true;
				}
			}
		}
		for (BlockPos target : FstestConfig.INSTANCE.targets().keySet())
		{
			if (snap.containsReal(target) && Markers.targetSubscription(target).isPresent())
			{
				return true;
			}
		}
		return false;
	}

	/** Real-space (clamped) lower corner of the captured region. */
	public BlockPos boundsMin()
	{
		return new BlockPos(this.minX, this.minY, this.minZ);
	}

	/** Real-space (clamped) upper corner of the captured region. */
	public BlockPos boundsMax()
	{
		return new BlockPos(this.maxX, this.maxY, this.maxZ);
	}

	public boolean containsReal(BlockPos pos)
	{
		return pos.getX() >= this.minX && pos.getX() <= this.maxX
				&& pos.getZ() >= this.minZ && pos.getZ() <= this.maxZ
				&& pos.getY() >= this.minY && pos.getY() <= this.maxY;
	}

	private static void copyScheduledTicks(ServerLevel level, RegionSnapshot snap)
	{
		Long2ObjectMap<LevelChunkTicks<?>> blockContainers =
				((LevelTicksAccessor) level.getBlockTicks()).fstest$getAllContainers();
		collectFrom(blockContainers, snap, true);

		Long2ObjectMap<LevelChunkTicks<?>> fluidContainers =
				((LevelTicksAccessor) level.getFluidTicks()).fstest$getAllContainers();
		collectFrom(fluidContainers, snap, false);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void collectFrom(Long2ObjectMap<LevelChunkTicks<?>> containers, RegionSnapshot snap, boolean isBlock)
	{
		for (Object value : containers.values())
		{
			LevelChunkTicks<?> chunkTicks = (LevelChunkTicks<?>) value;
			for (Object tickObj : chunkTicks.getAll().toArray())
			{
				ScheduledTick<?> tick = (ScheduledTick<?>) tickObj;
				BlockPos pos = tick.pos();
				if (!snap.containsReal(pos))
				{
					continue;
				}
				ScheduledTick<?> copied = new ScheduledTick<>(
						tick.type(), subtract(pos, snap.anchor), tick.triggerTick(), tick.priority(), tick.subTickOrder());
				if (isBlock)
				{
					snap.blockTicks.add((ScheduledTick<Block>) copied);
				}
				else
				{
					snap.fluidTicks.add((ScheduledTick<Fluid>) copied);
				}
			}
		}
	}

	private static void copyBlockEvents(ServerLevel level, RegionSnapshot snap)
	{
		ServerLevelBlockEventsAccessor accessor = (ServerLevelBlockEventsAccessor) level;
		for (Object obj : accessor.fstest$getBlockEvents())
		{
			BlockEventData event = (BlockEventData) obj;
			if (!snap.containsReal(event.pos()))
			{
				continue;
			}
			snap.blockEvents.add(new BlockEventData(
					subtract(event.pos(), snap.anchor), event.block(), event.paramA(), event.paramB()));
		}
	}

	public static BlockPos subtract(BlockPos pos, BlockPos other)
	{
		return new BlockPos(pos.getX() - other.getX(), pos.getY() - other.getY(), pos.getZ() - other.getZ());
	}
}
