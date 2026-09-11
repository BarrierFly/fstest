package fstest.config;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sticky runtime configuration of the Fengshui Tester.
 *
 * All values live in memory only; after a server restart the initial values
 * defined here are restored again (persistence is planned for v2).
 */
public final class FstestConfig
{
	public static final int DEFAULT_COUNT_D = 1;
	public static final int DEFAULT_COUNT_P = 100;
	public static final int DEFAULT_COUNT_PD = 100;
	/** Horizontal amplitude of uniform random offsets, ±2^23 covers common overworld coordinates and is always legal. */
	public static final int UNIFORM_H_AMPLITUDE = 1 << 23;
	/** Upper bound for {@code /fstest range}; keeps snapshots bounded even in "unlimited" mode. */
	public static final int MAX_RANGE = 128;
	/** Default snapshot radius used when range is "unlimited". */
	public static final int DEFAULT_RANGE_UNLIMITED = 48;

	private FstestMode mode = FstestMode.NONE;
	private Optional<DyeColor> color = Optional.empty();
	private int countD = DEFAULT_COUNT_D;
	private int countP = DEFAULT_COUNT_P;
	private int countPd = DEFAULT_COUNT_PD;
	private int range = -1; // -1 = unlimited
	private PStrategy strategy = PStrategy.UNIFORM;
	private boolean updates = true;
	private boolean duplications = true;
	/**
	 * Manually registered subscription markers, keyed by the monitored block
	 * POSITION itself (not a wool anchor): a target subscribes the block it sits
	 * on for block changes / scheduled ticks / block events and for that block's
	 * own outgoing block-update dispatches, bypassing the wool geometry. Insertion
	 * order is preserved so {@code /fstest targets query} is stable.
	 */
	private final Map<BlockPos, DyeColor> targets = new LinkedHashMap<>();

	public static final FstestConfig INSTANCE = new FstestConfig();

	private FstestConfig()
	{
	}

	public static void reset()
	{
		INSTANCE.mode = FstestMode.NONE;
		INSTANCE.color = Optional.empty();
		INSTANCE.countD = DEFAULT_COUNT_D;
		INSTANCE.countP = DEFAULT_COUNT_P;
		INSTANCE.countPd = DEFAULT_COUNT_PD;
		INSTANCE.range = -1;
		INSTANCE.strategy = PStrategy.UNIFORM;
		INSTANCE.updates = true;
		INSTANCE.duplications = true;
		INSTANCE.targets.clear();
	}

	public FstestMode mode()
	{
		return this.mode;
	}

	public void setMode(FstestMode mode)
	{
		this.mode = mode;
	}

	/** Empty means "all colors". */
	public Optional<DyeColor> color()
	{
		return this.color;
	}

	public void setColor(Optional<DyeColor> color)
	{
		this.color = color;
	}

	public boolean acceptsColor(DyeColor color)
	{
		return this.color.isEmpty() || this.color.get() == color;
	}

	public int countD()
	{
		return this.countD;
	}

	public int countP()
	{
		return this.countP;
	}

	public int countPd()
	{
		return this.countPd;
	}

	/** Returns true when valid; n must be non-negative. */
	public boolean setCount(String which, int n)
	{
		if (n < 0)
		{
			return false;
		}
		switch (which.toLowerCase())
		{
			case "d" -> this.countD = n;
			case "p" -> this.countP = n;
			case "pd", "dp" -> this.countPd = n;
			default -> {
				return false;
			}
		}
		return true;
	}

	/** -1 means unlimited; otherwise a positive radius. */
	public int range()
	{
		return this.range;
	}

	public boolean isRangeUnlimited()
	{
		return this.range < 0;
	}

	/** Effective snapshot radius in blocks. */
	public int effectiveRange()
	{
		return this.range > 0 ? Math.min(this.range, MAX_RANGE) : DEFAULT_RANGE_UNLIMITED;
	}

	/** r must be positive, or -1 for unlimited. */
	public boolean setRange(int r)
	{
		if (r == -1 || (r > 0 && r <= MAX_RANGE))
		{
			this.range = r;
			return true;
		}
		return false;
	}

	public PStrategy strategy()
	{
		return this.strategy;
	}

	public void setStrategy(PStrategy strategy)
	{
		this.strategy = strategy;
	}

	/** Whether block-update dispatches enter the event stream. */
	public boolean updates()
	{
		return this.updates;
	}

	public void setUpdates(boolean updates)
	{
		this.updates = updates;
	}

	/** Whether creation attempts that failed by duplication enter the event stream. */
	public boolean duplications()
	{
		return this.duplications;
	}

	public void setDuplications(boolean duplications)
	{
		this.duplications = duplications;
	}

	/**
	 * Whether a scheduled-tick / block-event creation attempt belongs in the
	 * event stream. Every successful creation is recorded; duplicates only when
	 * the {@code duplications} toggle is on.
	 */
	public boolean recordsCreation(boolean success)
	{
		return success || this.duplications;
	}

	/** The manually registered marker colour at pos, if any. */
	public Optional<DyeColor> targetColor(BlockPos pos)
	{
		return Optional.ofNullable(this.targets.get(pos));
	}

	/** Insertion-ordered, read-only view of the registered targets. */
	public Map<BlockPos, DyeColor> targets()
	{
		return Collections.unmodifiableMap(this.targets);
	}

	/**
	 * Registers (or replaces) the marker at pos.
	 *
	 * @return the colour that was previously registered there, if any
	 */
	public Optional<DyeColor> putTarget(BlockPos pos, DyeColor color)
	{
		return Optional.ofNullable(this.targets.put(pos.immutable(), color));
	}

	/** @return the colour that was registered at pos, if any */
	public Optional<DyeColor> removeTarget(BlockPos pos)
	{
		return Optional.ofNullable(this.targets.remove(pos));
	}

	/** @return how many targets of the given colour were removed */
	public int removeTargetsOfColor(DyeColor color)
	{
		int before = this.targets.size();
		this.targets.values().removeIf(c -> c == color);
		return before - this.targets.size();
	}

	/** @return how many targets were removed */
	public int clearTargets()
	{
		int before = this.targets.size();
		this.targets.clear();
		return before;
	}
}
