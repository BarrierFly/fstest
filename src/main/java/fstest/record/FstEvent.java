package fstest.record;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

/**
 * Micro-timing style event records, shared by the real-world capture and the
 * simulated-space capture. Collection logic is modelled after Carpet TIS
 * Addition's microTiming logger (LGPL-3.0), reduced to the event kinds that
 * matter for directionality/positionality diffs in v1.
 *
 * Events carry a per-session sequence number; because game time is identical
 * between reality and simulation, streams align element by element.
 */
public sealed interface FstEvent
{
	BlockPos pos();

	long seq();

	/** A block state transition observed through the world's setBlock path. */
	record BlockChange(long seq, BlockPos pos, BlockState oldState, BlockState newState, int flags) implements FstEvent
	{
		@Override
		public String signature()
		{
			return "BC|" + this.oldState + ">" + this.newState + "|" + this.flags;
		}
	}

	/**
	 * A scheduled (tile) tick creation attempt. {@code success} mirrors TIS's
	 * flag: false when the attempt changed nothing because an identical tick
	 * (same type + position) was already queued and vanilla's dedup set
	 * rejected it, true when a new entry was actually added.
	 */
	record SchedTickCreate(long seq, BlockPos pos, Block block, int delay, TickPriority priority,
	                       boolean success) implements FstEvent
	{
		@Override
		public String signature()
		{
			return "ST+" + this.delay + "|" + this.priority + "|" + this.block + "|" + (this.success ? "ok" : "dup");
		}
	}

	/**
	 * A block event creation attempt (e.g. piston actions). {@code success}
	 * mirrors TIS's flag: false when the pending-event set already held an
	 * identical (pos, block, paramA, paramB) entry and deduplicated the
	 * attempt away, true when it was actually queued.
	 */
	record BlockEventCreate(long seq, BlockPos pos, Block block, int type, int data,
	                        boolean success) implements FstEvent
	{
		@Override
		public String signature()
		{
			return "BE|" + this.type + "," + this.data + "|" + this.block + "|" + (this.success ? "ok" : "dup");
		}
	}

	/**
	 * A block-update dispatch performed by the block at {@code pos} (the TIS
	 * microTiming block-update semantics: the event belongs to the sender;
	 * updates merely received by a block are not recorded). {@code kind} is the
	 * TIS-style update subtype; {@code exceptDir} carries the skipped side of a
	 * {@link UpdateKind#BLOCK_UPDATE_EXCEPT} dispatch.
	 */
	record NeighborUpdate(long seq, BlockPos pos, Block fromBlock, UpdateKind kind,
	                      @Nullable Direction exceptDir) implements FstEvent
	{
		@Override
		public String signature()
		{
			String base = "NU|" + this.kind.label();
			if (this.exceptDir != null)
			{
				base += "|except:" + this.exceptDir.getName();
			}
			return base + "|" + this.fromBlock;
		}
	}

	/**
	 * Position-independent comparison key (sequence numbers are stripped so
	 * that streams align by content and order, not by bookkeeping).
	 */
	default String signature()
	{
		return this.getClass().getSimpleName();
	}

	/** Combined grouping key (pos + signature) used to collapse duplicates in log output. */
	default String key()
	{
		return this.pos() + " | " + this.signature();
	}
}
