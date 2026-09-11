package fstest.sim;

import fstest.record.RecorderHub;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Standalone scheduled-tick queue reproducing vanilla semantics:
 * drain-order priority queue plus a (type identity, position) dedupe set.
 *
 * Newly scheduled ticks are recorded through {@link RecorderHub} exactly like
 * their real-world counterparts (creation attempts are events, per the spec),
 * while pre-existing ticks copied in from the real queue are injected
 * silently - they stay queryable but are never executed inside the operation
 * window, matching vanilla's synchronous processing behaviour.
 */
public final class SimTickQueue<T> implements LevelTickAccess<T>
{
	private final PriorityQueue<ScheduledTick<T>> queue = new PriorityQueue<>(ScheduledTick.DRAIN_ORDER);
	private final Set<UniqueKey> unique = new HashSet<>();
	private final FstestSimWorld world;
	private final boolean recordToHub;

	public SimTickQueue(FstestSimWorld world, boolean recordToHub)
	{
		this.world = world;
		this.recordToHub = recordToHub;
	}

	@Override
	public void schedule(ScheduledTick<T> tick)
	{
		// Vanilla's per-chunk container deduplicates a (type, position) that is
		// already queued; the attempt is still observable and is recorded with
		// its success flag, exactly like the real-world hook.
		boolean success = this.unique.add(new UniqueKey(tick.type(), tick.pos()));
		if (success)
		{
			this.queue.add(tick);
		}
		if (this.recordToHub && tick.type() instanceof Block block)
		{
			int delay = (int) Math.max(0, tick.triggerTick() - this.world.getGameTime());
			RecorderHub.onSchedTickCreate(this.world, tick.pos(), block, delay, tick.priority(), success);
		}
	}

	@Override
	public boolean hasScheduledTick(BlockPos pos, T type)
	{
		return this.unique.contains(new UniqueKey(type, pos));
	}

	@Override
	public int count()
	{
		return this.queue.size();
	}

	@Override
	public boolean willTickThisTick(BlockPos pos, T type)
	{
		return false; // queued ticks are never executed inside the simulated window
	}

	/**
	 * Copies a pre-existing tick from the real world without recording it.
	 *
	 * @return whether the queue accepted it (false if the same type + position
	 *         was already present)
	 */
	public boolean inject(ScheduledTick<T> tick)
	{
		if (this.unique.add(new UniqueKey(tick.type(), tick.pos())))
		{
			this.queue.add(tick);
			return true;
		}
		return false;
	}

	/** Read-only view for reconciliation against the real capture. */
	public java.util.List<ScheduledTick<T>> snapshotEntries()
	{
		return new java.util.ArrayList<>(this.queue);
	}

	/** Emulates ScheduledTick.UNIQUE_TICK_HASH: type identity + position equality. */
	private record UniqueKey(Object type, BlockPos pos)
	{
		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof UniqueKey other))
			{
				return false;
			}
			return this.type == other.type && this.pos.equals(other.pos);
		}

		@Override
		public int hashCode()
		{
			return System.identityHashCode(this.type) * 31 + this.pos.hashCode();
		}
	}
}
