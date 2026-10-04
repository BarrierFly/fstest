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

	/** Upper bound for one side of a named test area, per axis. */
	public static final int MAX_AREA_SIDE = 256;
	/** Upper bound for the timed-mode simulation duration in game ticks. */
	public static final int MAX_SIM_TICKS = 100;
	/** Default timed-mode duration when switching to {@code sim timed} without a prior setting. */
	public static final int DEFAULT_SIM_TICKS = 10;

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
	 * Simulation mode (instant vs timed); independent of the test mode.
	 * Exactly one is active - they are never run together.
	 */
	private FstestSimMode simMode = FstestSimMode.INSTANT;
	/**
	 * Timed-mode simulation duration in game ticks (1..{@value #MAX_SIM_TICKS}).
	 * Only meaningful while {@code simMode == TIMED}.
	 */
	private int simTicks = DEFAULT_SIM_TICKS;
	/**
	 * Scope selection by NAME of a registered test area; when set it takes
	 * precedence over the numeric radius ({@code /fstest scope <name>}).
	 */
	private String scopeArea;
	/**
	 * Named test areas (fstest's own MTR-style selections, distinct from the
	 * MicroTimingReplay mod's profiles). Insertion order is preserved so
	 * {@code /fstest mtrarea list} is stable.
	 */
	private final Map<String, Area> areas = new LinkedHashMap<>();
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
	 * Immutable copy of the registered areas plus the active scope name,
	 * republished on every mutation (and on {@link #reset}). The client
	 * renderer reads this instead of the live maps: those are owned by the
	 * server thread, and iterating them from the render thread could hit a
	 * concurrent modification.
	 */
	private volatile SelectionSnapshot selection = SelectionSnapshot.EMPTY;

	/** Immutable view of the selection state, safe to read from any thread. */
	public record SelectionSnapshot(Map<String, Area> areas, String scope)
	{
		static final SelectionSnapshot EMPTY = new SelectionSnapshot(Map.of(), null);
	}

	/** The current selection state; safe to call from the render thread. */
	public SelectionSnapshot selection()
	{
		return this.selection;
	}

	private void publishSelection()
	{
		this.selection = new SelectionSnapshot(Map.copyOf(this.areas), this.scopeArea);
	}

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
		INSTANCE.simMode = FstestSimMode.INSTANT;
		INSTANCE.simTicks = DEFAULT_SIM_TICKS;
		INSTANCE.scopeArea = null;
		INSTANCE.areas.clear();
		INSTANCE.targets.clear();
		INSTANCE.publishSelection();
	}

	public void setChangeListener(Runnable listener)
	{
		this.changeListener = listener != null ? listener : () -> {
		};
	}

	/** Fires the persistence hook after a successful mutation; suppressed while loading. */
	private void changed()
	{
		this.publishSelection();
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

	/** Instant or timed; never both at once. */
	public FstestSimMode simMode()
	{
		return this.simMode;
	}

	public boolean isTimed()
	{
		return this.simMode == FstestSimMode.TIMED;
	}

	public void setSimMode(FstestSimMode simMode)
	{
		this.simMode = simMode;
		this.changed();
	}

	/** Timed-mode duration in game ticks (1..{@value #MAX_SIM_TICKS}). */
	public int simTicks()
	{
		return this.simTicks;
	}

	/** n must be within 1..{@value #MAX_SIM_TICKS}. */
	public boolean setSimTicks(int n)
	{
		if (n < 1 || n > MAX_SIM_TICKS)
		{
			return false;
		}
		this.simTicks = n;
		this.changed();
		return true;
	}

	/** Name of the registered test area used as the snapshot/simulation scope; null = radius mode. */
	public String scopeArea()
	{
		return this.scopeArea;
	}

	public void setScopeArea(String name)
	{
		this.scopeArea = name;
		this.changed();
	}

	/** The registered area the scope currently points at, if any. */
	public Optional<Area> scopedArea()
	{
		return this.scopeArea == null ? Optional.empty() : Optional.ofNullable(this.areas.get(this.scopeArea));
	}

	/** Insertion-ordered, read-only view of the named test areas. */
	public Map<String, Area> areas()
	{
		return Collections.unmodifiableMap(this.areas);
	}

	public Area area(String name)
	{
		return this.areas.get(name);
	}

	/** Registers (or replaces) a named test area. */
	public void putArea(String name, Area area)
	{
		this.areas.put(name, area);
		this.changed();
	}

	/** @return the removed area, if any */
	public Optional<Area> removeArea(String name)
	{
		Area removed = this.areas.remove(name);
		if (name.equals(this.scopeArea))
		{
			this.scopeArea = null;
		}
		this.changed();
		return Optional.ofNullable(removed);
	}

	/** @return how many areas were removed */
	public int clearAreas()
	{
		int before = this.areas.size();
		this.areas.clear();
		this.scopeArea = null;
		this.changed();
		return before;
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
			// both corners must be read from the ORIGINAL arguments: reassigning
			// pos1 first and then deriving pos2 from it would always yield the
			// second argument, collapsing the box whenever the second corner is
			// the smaller one (which is just a matter of the order the player
			// happened to pick them in)
			int ax = pos1.getX();
			int ay = pos1.getY();
			int az = pos1.getZ();
			int bx = pos2.getX();
			int by = pos2.getY();
			int bz = pos2.getZ();
			pos1 = new BlockPos(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz));
			pos2 = new BlockPos(Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
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
