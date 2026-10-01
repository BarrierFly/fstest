package fstest.sim;

import fstest.FstestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes, inside the chunkless simulated space, the vanilla scheduled-tick
 * behaviours whose entry points demand a {@code ServerLevel} the simulated
 * world cannot be.
 *
 * The pattern: the vanilla entry body is replicated (it is uniformly a few
 * lines), and everything the body delegates to is the block's OWN vanilla
 * helper - invoked reflectively whenever that helper's signature is
 * {@code Level}-typed, so the real vanilla logic runs against the simulated
 * world with zero behavioural duplication.
 *
 * Curated set (the redstone-relevant scheduled-tick consumers): diodes
 * (repeaters and comparators), buttons, observers, redstone torches and
 * pressure plates. Every other block's scheduled tick, and every fluid tick,
 * is counted as unexecuted (surfaced in the per-run stats) and removed from
 * the queue exactly like a tick vanilla would have run.
 *
 * A reflection failure (signature drift in a future MC version) degrades the
 * affected block to "unexecuted" with a one-time warning instead of crashing
 * the analysis.
 */
public final class SimTickExecutors
{
	private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
	private static final Map<String, Optional<Field>> FIELDS = new ConcurrentHashMap<>();

	private SimTickExecutors()
	{
	}

	/**
	 * Runs one block scheduled tick. The caller has already verified
	 * {@code state.is(block)} (vanilla {@code ServerLevel#tickBlock}).
	 *
	 * @return true when executed, false when the block type is outside the
	 *         curated set or a vanilla hook could not be resolved (caller
	 *         counts it as unexecuted)
	 */
	public static boolean executeBlockTick(FstestSimWorld world, BlockState state, BlockPos pos, Block block)
	{
		if (block instanceof DiodeBlock diode)
		{
			return tickDiode(world, state, pos, diode);
		}
		if (block instanceof ButtonBlock button)
		{
			// ButtonBlock#tick: if (POWERED) checkPressed(state, level, pos)
			return state.getValue(ButtonBlock.POWERED)
					&& invokeVoid(button, "checkPressed",
							new Class<?>[]{BlockState.class, Level.class, BlockPos.class}, state, world, pos);
		}
		if (block instanceof ObserverBlock observer)
		{
			return tickObserver(world, state, pos, observer);
		}
		if (block instanceof RedstoneTorchBlock torch)
		{
			return tickRedstoneTorch(world, state, pos, torch);
		}
		if (block instanceof BasePressurePlateBlock plate)
		{
			return tickPressurePlate(world, state, pos, plate);
		}
		return false;
	}

	private static boolean tickDiode(FstestSimWorld world, BlockState state, BlockPos pos, DiodeBlock diode)
	{
		// replica of DiodeBlock#tick; isLocked is public (LevelReader), the rest
		// of the behaviour is vanilla through protected Level-typed helpers
		if (diode.isLocked(world, pos, state))
		{
			return true;
		}
		Boolean shouldTurnOn = invoke(diode, "shouldTurnOn", boolean.class,
				new Class<?>[]{Level.class, BlockPos.class, BlockState.class}, world, pos, state);
		if (shouldTurnOn == null)
		{
			return false;
		}
		boolean powered = state.getValue(DiodeBlock.POWERED);
		if (powered && !shouldTurnOn)
		{
			world.setBlock(pos, state.setValue(DiodeBlock.POWERED, false), 2);
		}
		else if (!powered)
		{
			world.setBlock(pos, state.setValue(DiodeBlock.POWERED, true), 2);
			if (!shouldTurnOn)
			{
				Integer delay = invoke(diode, "getDelay", int.class,
						new Class<?>[]{BlockState.class}, state);
				if (delay == null)
				{
					return false;
				}
				world.scheduleTick(pos, diode, delay, TickPriority.VERY_HIGH);
			}
		}
		return true;
	}

	private static boolean tickObserver(FstestSimWorld world, BlockState state, BlockPos pos, ObserverBlock observer)
	{
		// replica of ObserverBlock#tick
		if (state.getValue(ObserverBlock.POWERED))
		{
			world.setBlock(pos, state.setValue(ObserverBlock.POWERED, false), 2);
		}
		else
		{
			world.setBlock(pos, state.setValue(ObserverBlock.POWERED, true), 2);
			world.scheduleTick(pos, observer, 2);
		}
		return invokeVoid(observer, "updateNeighborsInFront",
				new Class<?>[]{Level.class, BlockPos.class, BlockState.class}, world, pos, state);
	}

	private static boolean tickRedstoneTorch(FstestSimWorld world, BlockState state, BlockPos pos,
	                                         RedstoneTorchBlock torch)
	{
		// replica of RedstoneTorchBlock#tick; the burnout bookkeeping
		// (RECENT_TOGGLES, keyed by the world instance) is vanilla code reached
		// reflectively, and every simulated run starts with a fresh world, hence
		// a fresh toggle history
		Boolean hasNeighborSignal = invoke(torch, "hasNeighborSignal", boolean.class,
				new Class<?>[]{Level.class, BlockPos.class, BlockState.class}, world, pos, state);
		if (hasNeighborSignal == null)
		{
			return false;
		}
		Field togglesField = field(RedstoneTorchBlock.class, "RECENT_TOGGLES");
		if (togglesField == null)
		{
			return false;
		}
		try
		{
			Map<?, List<?>> recentToggles = (Map<?, List<?>>) togglesField.get(null);
			List<?> toggles = recentToggles.get(world);
			// vanilla only prunes when an entry exists; entries are created
			// exclusively inside isToggledTooFrequently
			while (toggles != null && !toggles.isEmpty()
					&& world.getGameTime() - toggleWhen(toggles.get(0)) > 60L)
			{
				toggles.remove(0);
			}
		}
		catch (ReflectiveOperationException | ClassCastException e)
		{
			warnOnce("RedstoneTorchBlock.RECENT_TOGGLES");
			return false;
		}
		if (state.getValue(RedstoneTorchBlock.LIT))
		{
			if (hasNeighborSignal)
			{
				world.setBlock(pos, state.setValue(RedstoneTorchBlock.LIT, false), 3);
				Boolean tooFrequently = invokeStatic(RedstoneTorchBlock.class, "isToggledTooFrequently", boolean.class,
						new Class<?>[]{Level.class, BlockPos.class, boolean.class}, world, pos, true);
				if (tooFrequently == null)
				{
					return false;
				}
				if (tooFrequently)
				{
					world.levelEvent(1502, pos, 0); // suppressed output, vanilla call order kept
					world.scheduleTick(pos, world.getBlockState(pos).getBlock(), 160);
				}
			}
		}
		else if (!hasNeighborSignal)
		{
			Boolean tooFrequently = invokeStatic(RedstoneTorchBlock.class, "isToggledTooFrequently", boolean.class,
					new Class<?>[]{Level.class, BlockPos.class, boolean.class}, world, pos, false);
			if (tooFrequently == null)
			{
				return false;
			}
			if (!tooFrequently)
			{
				world.setBlock(pos, state.setValue(RedstoneTorchBlock.LIT, true), 3);
			}
		}
		return true;
	}

	private static boolean tickPressurePlate(FstestSimWorld world, BlockState state, BlockPos pos,
	                                         BasePressurePlateBlock plate)
	{
		// replica of BasePressurePlateBlock#tick
		Integer signal = invoke(plate, "getSignalForState", int.class,
				new Class<?>[]{BlockState.class}, state);
		if (signal == null)
		{
			return false;
		}
		if (signal > 0)
		{
			return invokeVoid(plate, "checkPressed",
					new Class<?>[]{net.minecraft.world.entity.Entity.class, Level.class, BlockPos.class, BlockState.class, int.class},
					null, world, pos, state, signal);
		}
		return true;
	}

	private static long toggleWhen(Object toggle)
	{
		Field field = field(RedstoneTorchBlock.Toggle.class, "when");
		if (field == null)
		{
			return Long.MAX_VALUE; // never pruned; harmless for diff purposes
		}
		try
		{
			return field.getLong(toggle);
		}
		catch (ReflectiveOperationException e)
		{
			return Long.MAX_VALUE;
		}
	}

	// ------------------------------------------------------------------
	// Reflective plumbing
	// ------------------------------------------------------------------

	private static Field field(Class<?> owner, String name)
	{
		return fieldOpt(owner, name).orElse(null);
	}

	private static Optional<Field> fieldOpt(Class<?> owner, String name)
	{
		String key = owner.getSimpleName() + "." + name;
		return FIELDS.computeIfAbsent(key, k -> {
			try
			{
				Field f = owner.getDeclaredField(name);
				f.setAccessible(true);
				return Optional.of(f);
			}
			catch (ReflectiveOperationException e)
			{
				warnOnce(k);
				return Optional.empty();
			}
		});
	}

	/**
	 * Reflective call on an instance method, walking the class hierarchy for
	 * helpers declared on a superclass. Null (with a one-time warning) when the
	 * hook cannot be resolved or the invocation itself fails.
	 */
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

	/** Reflective call on a void instance method; true when it actually ran. */
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

	/** Reflective call on a private static helper; null (with a one-time warning) on failure. */
	private static <T> T invokeStatic(Class<?> owner, String name, Class<T> returnType,
	                                  Class<?>[] paramTypes, Object... args)
	{
		Optional<Method> method = findMethod(owner, name, paramTypes);
		if (method.isEmpty())
		{
			return null;
		}
		try
		{
			return returnType.cast(method.get().invoke(null, args));
		}
		catch (Exception e)
		{
			warnOnce(name + Arrays.toString(paramTypes));
			return null;
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
		FstestMod.LOGGER.warn("[fstest] could not resolve vanilla hook {}; affected ticks count as unexecuted", what);
	}
}
