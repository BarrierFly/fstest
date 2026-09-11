package fstest.record;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One recording session bound to a specific world (either the real world
 * during a player operation, or the simulated space during a replay).
 *
 * The same session type serves both worlds; which one it belongs to is
 * decided by whoever pushes it onto the {@link RecorderHub} stack.
 */
public final class CaptureSession
{
	public final Level world;
	/** Ordered micro-timing style events recorded in this session. */
	public final List<FstEvent> events = new ArrayList<>();
	/**
	 * Block transitions applied directly by the operation itself (setBlock
	 * call nesting level 0). These "root actions" are what gets replayed
	 * inside the simulation; cascade-produced transitions are regenerated
	 * there naturally instead of being replayed.
	 */
	public final List<FstEvent.BlockChange> rootChanges = new ArrayList<>();
	/** Scheduled ticks created during this session (for queue reconciliation). */
	public final List<FstEvent.SchedTickCreate> createdTicks = new ArrayList<>();
	/** Block events created during this session (for queue reconciliation). */
	public final List<FstEvent.BlockEventCreate> createdBlockEvents = new ArrayList<>();
	/**
	 * Top-level neighbour-update dispatches performed by the operation's code
	 * outside any setBlock scope (see {@link RootDispatch}); replayed right
	 * after their root setBlock so out-of-band cascades can regenerate.
	 */
	public final List<RootDispatch> rootDispatches = new ArrayList<>();
	/**
	 * Dispatches performed by block-removal side-effect hooks
	 * ({@code affectNeighborsAfterRemoval}), keyed implicitly by removal
	 * position; the simulation re-issues each list at its matching removal.
	 */
	public final List<RemovalSideEffect> removalSideEffects = new ArrayList<>();

	private final Map<BlockPos, Markers.Subscription> markerCache = new HashMap<>();
	private long seqCounter;

	/** Read-only view of the wool-subscription cache, for sharing with sibling sessions. */
	public Map<BlockPos, Markers.Subscription> getMarkerCacheView()
	{
		return java.util.Collections.unmodifiableMap(this.markerCache);
	}

	/**
	 * Seed the cache from another session's results so that this session's
	 * subscription decisions stay aligned with the source session even if the
	 * underlying world has drifted. Intended for use by the simulator, which
	 * must subscribe to the exact same positions the real world did, regardless
	 * of how the sim's snapshot diverges during setBlock cascades.
	 */
	public void prePopulateMarkerCache(Map<BlockPos, Markers.Subscription> source)
	{
		this.markerCache.putAll(source);
	}

	public CaptureSession(Level world)
	{
		this.world = world;
	}

	public long nextSeq()
	{
		return this.seqCounter++;
	}

	/** Subscribed for block changes / scheduled ticks / block events (TIS defaultColorGetter). */
	public boolean isSubscribed(BlockPos pos)
	{
		return this.subscription(pos).subscribesOperations();
	}

	/** Subscribed for neighbour-update receipts (TIS blockUpdateColorGetter: end-rod rule only). */
	public boolean isUpdateSubscribed(BlockPos pos)
	{
		return this.subscription(pos).subscribesBlockUpdates();
	}

	/** Marker subscription lookup (TIS microTiming rules + manual targets), cached per position. */
	public Markers.Subscription subscription(BlockPos pos)
	{
		return this.markerCache.computeIfAbsent(pos.immutable(), p -> Markers.subscriptionAt(this.world, p));
	}

	public void record(FstEvent event)
	{
		this.events.add(event);
	}
}
