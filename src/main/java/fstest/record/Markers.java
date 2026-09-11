package fstest.record;

import fstest.config.FstestConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Wool-marker subscription rules for the recorder.
 *
 * Collection logic modelled after Carpet TIS Addition's microTiming logger
 * (https://github.com/TISUnion/Carpet-TIS-Addition, LGPL-3.0); re-implemented
 * here under the same license without any code dependency on that mod.
 *
 * A redstone component is subscribed when the wool block at its associated
 * anchor position carries a color accepted by {@link FstestConfig#acceptsColor}.
 * A manually registered target ({@code /fstest targets add}) instead marks the
 * monitored block POSITION itself and bypasses the wool geometry entirely: the
 * marked block is subscribed for block changes / scheduled ticks / block events
 * and for its own outgoing block-update dispatches. The target's colour still
 * passes through the same {@code color} filter as placed wool.
 */
public final class Markers
{
	private static volatile Map<Block, DyeColor> woolBlocks;

	private Markers()
	{
	}

	/** Color of the wool at pos, or null; null result also covers color-filtered-out wools. */
	public static DyeColor woolColorAt(Level world, BlockPos pos)
	{
		BlockState state = world.getBlockState(pos);
		DyeColor color = woolMap().get(state.getBlock());
		if (color != null && FstestConfig.INSTANCE.acceptsColor(color))
		{
			return color;
		}
		return null;
	}

	private static Map<Block, DyeColor> woolMap()
	{
		Map<Block, DyeColor> map = woolBlocks;
		if (map == null)
		{
			synchronized (Markers.class)
			{
				map = woolBlocks;
				if (map == null)
				{
					map = buildWoolMap();
					woolBlocks = map;
				}
			}
		}
		return map;
	}

	private static Map<Block, DyeColor> buildWoolMap()
	{
		Map<Block, DyeColor> map = new HashMap<>();
		map.put(Blocks.WHITE_WOOL, DyeColor.WHITE);
		map.put(Blocks.ORANGE_WOOL, DyeColor.ORANGE);
		map.put(Blocks.MAGENTA_WOOL, DyeColor.MAGENTA);
		map.put(Blocks.LIGHT_BLUE_WOOL, DyeColor.LIGHT_BLUE);
		map.put(Blocks.YELLOW_WOOL, DyeColor.YELLOW);
		map.put(Blocks.LIME_WOOL, DyeColor.LIME);
		map.put(Blocks.PINK_WOOL, DyeColor.PINK);
		map.put(Blocks.GRAY_WOOL, DyeColor.GRAY);
		map.put(Blocks.LIGHT_GRAY_WOOL, DyeColor.LIGHT_GRAY);
		map.put(Blocks.CYAN_WOOL, DyeColor.CYAN);
		map.put(Blocks.PURPLE_WOOL, DyeColor.PURPLE);
		map.put(Blocks.BLUE_WOOL, DyeColor.BLUE);
		map.put(Blocks.BROWN_WOOL, DyeColor.BROWN);
		map.put(Blocks.GREEN_WOOL, DyeColor.GREEN);
		map.put(Blocks.RED_WOOL, DyeColor.RED);
		map.put(Blocks.BLACK_WOOL, DyeColor.BLACK);
		return map;
	}

	/** Component -> wool anchor mapping, mirroring TIS microTiming's subscription geometry. */
	public static Optional<DyeColor> dyeColorOf(Level world, BlockPos pos)
	{
		BlockState state = world.getBlockState(pos);
		Block block = state.getBlock();
		BlockPos woolPos;

		if (block instanceof ObserverBlock || block instanceof EndRodBlock ||
				block instanceof PistonBaseBlock || block instanceof MovingPistonBlock)
		{
			woolPos = pos.relative(state.getValue(BlockStateProperties.FACING).getOpposite());
		}
		else if (block instanceof ButtonBlock || block instanceof LeverBlock)
		{
			AttachFace face = state.getValue(BlockStateProperties.ATTACH_FACE);
			Direction facing = face == AttachFace.FLOOR ? Direction.UP :
					face == AttachFace.CEILING ? Direction.DOWN :
							state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			woolPos = pos.relative(facing.getOpposite());
		}
		else if (block instanceof RedstoneWallTorchBlock || block instanceof TripWireHookBlock)
		{
			woolPos = pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
		}
		else if (block instanceof BaseRailBlock || block instanceof DiodeBlock ||
				block instanceof RedstoneTorchBlock || block instanceof RedStoneWireBlock ||
				block instanceof BasePressurePlateBlock) // mounted on the block below
		{
			woolPos = pos.below();
		}
		else
		{
			return Optional.empty();
		}

		DyeColor color = woolColorAt(world, woolPos);
		return color == null ? Optional.empty() : Optional.of(color);
	}

	/** End-of-line end-rod rule subscribing neighbouring blocks to block-update events (TIS semantics). */
	public static Optional<DyeColor> endRodColorOf(Level world, BlockPos pos)
	{
		for (Direction facing : Direction.values())
		{
			BlockPos rodPos = pos.relative(facing);
			BlockState rodState = world.getBlockState(rodPos);
			if (rodState.getBlock() == Blocks.END_ROD &&
					rodState.getValue(DirectionalBlock.FACING).getOpposite() == facing)
			{
				DyeColor color = woolColorAt(world, rodPos.relative(facing));
				if (color != null)
				{
					return Optional.of(color);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * The subscription verdict for one position, mirroring TIS microTiming's
	 * two colour getters:
	 *
	 * - component colour ({@code defaultColorGetter}): the position hosts a
	 *   monitored component whose wool anchor carries a colour, or an end rod
	 *   points at it - gates block changes, scheduled-tick creations and
	 *   block-event creations;
	 * - end-rod colour ({@code blockUpdateColorGetter}): an end rod planted on
	 *   wool points at this position - the ONLY wool rule gating outgoing
	 *   neighbour-update dispatches (TIS does not subscribe block updates via
	 *   the component rule).
	 *
	 * A manually targeted position ({@code /fstest targets}) fills BOTH channels
	 * with its colour and is resolved before the wool rules (see
	 * {@link #subscriptionAt}).
	 */
	public record Subscription(Optional<DyeColor> componentColor, Optional<DyeColor> endRodColor)
	{
		public boolean subscribesOperations()
		{
			return this.componentColor.isPresent() || this.endRodColor.isPresent();
		}

		public boolean subscribesBlockUpdates()
		{
			return this.endRodColor.isPresent();
		}

		public Optional<DyeColor> color()
		{
			return this.componentColor.isPresent() ? this.componentColor : this.endRodColor;
		}
	}

	/**
	 * The subscription a manual target at {@code pos} implies, or empty when the
	 * position is not targeted. Both channels get the target colour (filtered by
	 * {@link FstestConfig#acceptsColor}). Exposed so the replay can seed the
	 * simulated marker cache with every registered target: targets live in real
	 * coordinates, so a transformed run's own lookup would miss them, and that
	 * would hide exactly the divergences a target is meant to catch.
	 */
	public static Optional<Subscription> targetSubscription(BlockPos pos)
	{
		return FstestConfig.INSTANCE.targetColor(pos).map(color ->
		{
			Optional<DyeColor> effective = FstestConfig.INSTANCE.acceptsColor(color)
					? Optional.of(color)
					: Optional.empty();
			return new Subscription(effective, effective);
		});
	}

	public static Subscription subscriptionAt(Level world, BlockPos pos)
	{
		Optional<Subscription> target = targetSubscription(pos);
		if (target.isPresent())
		{
			// A manual target marks this exact block: it is subscribed on both
			// channels (its own changes/creations and its own outgoing updates)
			// without consulting the wool geometry. A filtered-out target colour
			// leaves the position unsubscribed - the target overrides the wool
			// rules rather than falling back to them.
			return target.get();
		}
		return new Subscription(dyeColorOf(world, pos), endRodColorOf(world, pos));
	}
}
