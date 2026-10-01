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

	/** Upper bound for one side of the MTR test area, per axis. */
	public static final int MAX_AREA_SIDE = 256;
	/** Upper bound for the MTR simulation duration in game ticks. */
	public static final int MAX_MTR_TICKS = 100;

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
	 * MTR (multi-tick replay) mode: 0 = off (instant-window analysis as in v1),
	 * 1..{@value #MAX_MTR_TICKS} = the simulated space executes this many game
	 * ticks after the operation replay (scheduled ticks, block events, block
	 * entity ticking). Never touched on the real world.
	 */
	private int mtrTicks = 0;
	/**
	 * MTR-style explicit test area (two corners); when set, MTR-mode snapshots
	 * capture exactly this box instead of the {@code /fstest range} cube.
	 */
	private Area mtrArea;
	/**
	 * Manually registered subscription markers, keyed by the monitored block
	 * POSITION itself (not a wool anchor): a target subscribes the block it sits
	 * on for block changes / scheduled ticks / block events and for that block's
	 * own outgoing block-update dispatches, bypassing the wool geometry. Insertion
	 * order is preserved so {@code /fstest targets query} is stable.
	 */
	private final Map<BlockPos, DyeColor> targets = new LinkedHashMap<>();

	public static final FstestConfig INSTANCE = new FstestConfig();

	/**
	 * Invoked after every successful mutation (commands, persistence writes
	 * excepted) so the sticky configuration can be persisted. Registered by the
	 * persistence layer at server start; {@code reset()} deliberately does not
	 * fire it.
	 */
	private Runnable changeListener = () -> {
	};

	/** True while the persistence layer is applying a loaded file, suppressing save-back. */
	private boolean loading;

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
		INSTANCE.mtrTicks = 0;
		INSTANCE.mtrArea = null;
		INSTANCE.targets.clear();
	}

	public void setChangeListener(Runnable listener)
	{
		this.changeListener = listener != null ? listener : () -> {
		};
	}

	/** Fires the persistence hook after a successful mutation; suppressed while loading. */
	private void changed()
	{
		if (!this.loading)
		{
			this.changeListener.run();
		}
	}

	/** Marks the config as being bulk-loaded from disk (no save-back). */
	public void setLoading(boolean loading)
	{
		this.loading = loading;
	}

	public FstestMode mode()
	{
		return this.mode;
	}

	public void setMode(FstestMode mode)
	{
		this.mode = mode;
			this.changed();
	}

	/** Empty means "all colors". */
	public Optional<DyeColor> color()
	{
		return this.color;
	}

	public void setColor(Optional<DyeColor> color)
	{
		this.color = color;
			this.changed();
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
		this.changed();
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
			this.changed();
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
			this.changed();
	}

	/** Whether block-update dispatches enter the event stream. */
	public boolean updates()
	{
		return this.updates;
	}

	public void setUpdates(boolean updates)
	{
		this.updates = updates;
			this.changed();
	}

	/** Whether creation attempts that failed by duplication enter the event stream. */
	public boolean duplications()
	{
		return this.duplications;
	}

	public void setDuplications(boolean duplications)
	{
		this.duplications = duplications;
			this.changed();
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
		Optional<DyeColor> previous = Optional.ofNullable(this.targets.put(pos.immutable(), color));
		this.changed();
		return previous;
	}

	/** @return the colour that was registered at pos, if any */
	public Optional<DyeColor> removeTarget(BlockPos pos)
	{
		Optional<DyeColor> removed = Optional.ofNullable(this.targets.remove(pos));
		this.changed();
		return removed;
	}

	/** @return how many targets of the given colour were removed */
	public int removeTargetsOfColor(DyeColor color)
	{
		int before = this.targets.size();
		this.targets.values().removeIf(c -> c == color);
		int removed = before - this.targets.size();
		this.changed();
		return removed;
	}

	/** @return how many targets were removed */
	public int clearTargets()
	{
		int before = this.targets.size();
		this.targets.clear();
		this.changed();
		return before;
	}

	/** 0 = off (instant-window analysis); 1..{@value #MAX_MTR_TICKS} = multi-tick replay simulation. */
	public int mtrTicks()
	{
		return this.mtrTicks;
	}

	public boolean isMtrEnabled()
	{
		return this.mtrTicks > 0;
	}

	/** n must be 0 (off) or within 1..{@value #MAX_MTR_TICKS}. */
	public boolean setMtrTicks(int n)
	{
		if (n < 0 || n > MAX_MTR_TICKS)
		{
			return false;
		}
		this.mtrTicks = n;
		this.changed();
		return true;
	}

	/** Nullable: when unset, MTR mode falls back to the {@code /fstest range} cube. */
	public Area mtrArea()
	{
		return this.mtrArea;
	}

	public void setMtrArea(Area area)
	{
		this.mtrArea = area;
		this.changed();
	}

	/**
	 * An axis-aligned test area defined by two corners (MTR-style selection).
	 * Corners are normalized on creation; every side may span at most
	 * {@link #MAX_AREA_SIDE} blocks.
	 */
	public record Area(BlockPos pos1, BlockPos pos2)
	{
		public static Area of(BlockPos a, BlockPos b)
		{
			return new Area(a.immutable(), b.immutable());
		}

		public Area
		{
			pos1 = new BlockPos(
					Math.min(pos1.getX(), pos2.getX()),
					Math.min(pos1.getY(), pos2.getY()),
					Math.min(pos1.getZ(), pos2.getZ()));
			pos2 = new BlockPos(
					Math.max(pos1.getX(), pos2.getX()),
					Math.max(pos1.getY(), pos2.getY()),
					Math.max(pos1.getZ(), pos2.getZ()));
		}

		/** All six side lengths; longest one must be validated against {@link #MAX_AREA_SIDE}. */
		public int sideX()
		{
			return this.pos2.getX() - this.pos1.getX();
		}

		public int sideY()
		{
			return this.pos2.getY() - this.pos1.getY();
		}

		public int sideZ()
		{
			return this.pos2.getZ() - this.pos1.getZ();
		}

		public boolean contains(BlockPos pos)
		{
			return pos.getX() >= this.pos1.getX() && pos.getX() <= this.pos2.getX()
					&& pos.getY() >= this.pos1.getY() && pos.getY() <= this.pos2.getY()
					&& pos.getZ() >= this.pos1.getZ() && pos.getZ() <= this.pos2.getZ();
		}
	}
}
