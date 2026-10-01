package fstest.sim;

import fstest.FstestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes the block-removal side effects ({@code affectNeighborsAfterRemoval})
 * of redstone components INSIDE the simulated world's own multi-tick
 * execution, where no captured reality dispatch exists to re-issue.
 *
 * Background: the hook's signature demands a {@code ServerLevel}, so the
 * simulated space can never call it. For removals replayed from the operation
 * window the hook's real dispatches are captured from reality and re-issued
 * verbatim (see {@link FstestSimWorld#setBlockInternal}). Removals that happen
 * LATER inside simulated ticks (a piston push destroying a wire, a shape
 * update dropping a rail) have no reality counterpart to capture - the MTR
 * mode never records reality beyond the instant window. For those, the hook's
 * entry body is replicated here (uniformly a few lines, dispatch-only for
 * 1.21.11's redstone components) and the dispatch work is again done by the
 * block's own vanilla {@code Level}-typed helpers via reflection.
 *
 * Blocks without a curated replica issue nothing, exactly like the removal
 * hooks of blocks that have no override at all; the residual gap is documented
 * in the README. A reflection failure degrades to "no dispatch" with a
 * one-time warning.
 */
public final class SimRemovalExecutors
{
	private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();

	private SimRemovalExecutors()
	{
	}

	/**
	 * Issues the removal-hook dispatches for one removal inside simulated
	 * ticks, at the point of the simulated {@code setBlock} flow where vanilla
	 * would run the hook (after the state is stored, before {@code onPlace}).
	 *
	 * @param oldState the state that was just removed/replaced
	 * @return true when the hook was executed (block is in the curated set)
	 */
	public static boolean affectNeighborsAfterRemoval(FstestSimWorld world, BlockState oldState,
	                                                  BlockPos pos, boolean movedByPiston)
	{
		Block block = oldState.getBlock();
		if (block instanceof RedStoneWireBlock wire)
		{
			if (!movedByPiston)
			{
				for (Direction direction : Direction.values())
				{
					world.updateNeighborsAt(pos.relative(direction), wire);
				}
				if (!invokeVoid(wire, "updatePowerStrength",
						new Class<?>[]{Level.class, BlockPos.class, BlockState.class,
								net.minecraft.world.level.redstone.Orientation.class, boolean.class},
						world, pos, oldState, null, false))
				{
					return false;
				}
				if (!invokeVoid(wire, "updateNeighborsOfNeighboringWires",
						new Class<?>[]{Level.class, BlockPos.class}, world, pos))
				{
					return false;
				}
			}
			return true;
		}
		if (block instanceof RedstoneTorchBlock torch)
		{
			if (!movedByPiston)
			{
				return invokeVoid(torch, "notifyNeighbors",
						new Class<?>[]{Level.class, BlockPos.class, BlockState.class}, world, pos, oldState);
			}
			return true;
		}
		if (block instanceof DiodeBlock diode)
		{
			if (!movedByPiston)
			{
				return invokeVoid(diode, "updateNeighborsInFront",
						new Class<?>[]{Level.class, BlockPos.class, BlockState.class}, world, pos, oldState);
			}
			return true;
		}
		if (block instanceof LeverBlock lever)
		{
			if (!movedByPiston && oldState.getValue(LeverBlock.POWERED))
			{
				return invokeVoid(lever, "updateNeighbours",
						new Class<?>[]{BlockState.class, Level.class, BlockPos.class}, oldState, world, pos);
			}
			return true;
		}
		if (block instanceof ButtonBlock button)
		{
			if (!movedByPiston && oldState.getValue(ButtonBlock.POWERED))
			{
				return invokeVoid(button, "updateNeighbours",
						new Class<?>[]{BlockState.class, Level.class, BlockPos.class}, oldState, world, pos);
			}
			return true;
		}
		if (block instanceof BasePressurePlateBlock plate)
		{
			if (!movedByPiston)
			{
				Integer signal = invoke(plate, "getSignalForState", int.class,
						new Class<?>[]{BlockState.class}, oldState);
				if (signal == null)
				{
					return false;
				}
				if (signal > 0)
				{
					return invokeVoid(plate, "updateNeighbours",
							new Class<?>[]{Level.class, BlockPos.class}, world, pos);
				}
			}
			return true;
		}
		if (block instanceof ObserverBlock observer)
		{
			if (oldState.getValue(ObserverBlock.POWERED)
					&& world.getBlockTicks().hasScheduledTick(pos, observer))
			{
				return invokeVoid(observer, "updateNeighborsInFront",
						new Class<?>[]{Level.class, BlockPos.class, BlockState.class},
						world, pos, oldState.setValue(ObserverBlock.POWERED, false));
			}
			return true;
		}
		if (block instanceof BaseRailBlock rail)
		{
			// replica of BaseRailBlock#affectNeighborsAfterRemoval
			if (!movedByPiston)
			{
				if (oldState.getValue(rail.getShapeProperty()).isSlope())
				{
					world.updateNeighborsAt(pos.above(), rail);
				}
				if (rail.isStraight())
				{
					world.updateNeighborsAt(pos, rail);
					world.updateNeighborsAt(pos.below(), rail);
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * Whether the block's removal hook is in the curated re-execution set.
	 * 1.21.11's redstone components' hooks dispatch only (verified against the
	 * vanilla sources); anything outside this set is treated the same way -
	 * nothing is re-issued for it.
	 */
	public static boolean isCurated(BlockState state)
	{
		Block block = state.getBlock();
		return block instanceof RedStoneWireBlock
				|| block instanceof RedstoneTorchBlock
				|| block instanceof DiodeBlock
				|| block instanceof LeverBlock
				|| block instanceof ButtonBlock
				|| block instanceof BasePressurePlateBlock
				|| block instanceof ObserverBlock
				|| block instanceof BaseRailBlock;
	}

	private static <T> T invoke(Object receiver, String name, Class<T> returnType,
	                            Class<?>[] paramTypes, Object... args)
	{
		Optional<Method> method = findMethod(receiver.getClass(), name, paramTypes);
		if (method.isEmpty())
		{
			return null;
		}
		try
		{
			return returnType.cast(method.get().invoke(receiver, args));
		}
		catch (Exception e)
		{
			warnOnce(name + Arrays.toString(paramTypes));
			return null;
		}
	}

	private static boolean invokeVoid(Object receiver, String name, Class<?>[] paramTypes, Object... args)
	{
		Optional<Method> method = findMethod(receiver.getClass(), name, paramTypes);
		if (method.isEmpty())
		{
			return false;
		}
		try
		{
			method.get().invoke(receiver, args);
			return true;
		}
		catch (Exception e)
		{
			warnOnce(name + Arrays.toString(paramTypes));
			return false;
		}
	}

	private static Optional<Method> findMethod(Class<?> start, String name, Class<?>[] paramTypes)
	{
		String key = name + Arrays.toString(paramTypes);
		return METHODS.computeIfAbsent(key, k -> {
			for (Class<?> type = start; type != null && type != Object.class; type = type.getSuperclass())
			{
				try
				{
					Method m = type.getDeclaredMethod(name, paramTypes);
					m.setAccessible(true);
					return Optional.of(m);
				}
				catch (NoSuchMethodException e)
				{
					// keep walking up
				}
				catch (ReflectiveOperationException e)
				{
					break;
				}
			}
			warnOnce(key);
			return Optional.empty();
		});
	}

	private static void warnOnce(String what)
	{
		FstestMod.LOGGER.warn("[fstest] could not resolve vanilla hook {}; affected behaviour counts as unexecuted", what);
	}
}
