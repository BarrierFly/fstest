package fstest.sim;

import fstest.mixin.LevelTicksAccessor;
import fstest.mixin.ServerLevelBlockEventsAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
 */
public final class RegionSnapshot
{
	public static final int WORLD_H_BOUND = 30_000_000;

	public final BlockPos anchor;
	public final int range;
	/** Relative positions -> states (air omitted). */
	public final Map<BlockPos, BlockState> states = new HashMap<>();
	/** Relative positions -> frozen BE NBT. */
	public final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
	public final List<ScheduledTick<Block>> blockTicks = new ArrayList<>();
	public final List<ScheduledTick<Fluid>> fluidTicks = new ArrayList<>();
	public final List<BlockEventData> blockEvents = new ArrayList<>();
	public long realRandomSeed;
	public boolean seedKnown;

	private final int minY;
	private final int maxY;

	private RegionSnapshot(BlockPos anchor, int range, int minY, int maxY)
	{
		this.anchor = anchor.immutable();
		this.range = range;
		this.minY = minY;
		this.maxY = maxY;
	}

	public boolean containsReal(BlockPos pos)
	{
		return Math.abs(pos.getX() - this.anchor.getX()) <= this.range
				&& Math.abs(pos.getZ() - this.anchor.getZ()) <= this.range
				&& pos.getY() >= this.minY && pos.getY() <= this.maxY;
	}

	public static RegionSnapshot capture(ServerLevel level, MinecraftServer server, BlockPos anchor, int range)
	{
		DimensionType dim = level.dimensionType();
		int minY = Math.max(dim.minY(), anchor.getY() - range);
		int maxY = Math.min(dim.minY() + dim.height() - 1, anchor.getY() + range);
		RegionSnapshot snap = new RegionSnapshot(anchor, range, minY, maxY);

		int minX = Math.max(-WORLD_H_BOUND + 1, anchor.getX() - range);
		int maxX = Math.min(WORLD_H_BOUND - 1, anchor.getX() + range);
		int minZ = Math.max(-WORLD_H_BOUND + 1, anchor.getZ() - range);
		int maxZ = Math.min(WORLD_H_BOUND - 1, anchor.getZ() + range);

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

		snap.realRandomSeed = FstestSimWorld.tryCaptureRealRandomSeed(level);
		snap.seedKnown = true; // best effort; an unreadable seed falls back to a fixed base
		return snap;
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
