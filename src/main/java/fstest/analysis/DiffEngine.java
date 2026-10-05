package fstest.analysis;

import fstest.record.FstEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sequence diff between two event streams.
 *
 * Streams align element by element because game time is identical and both
 * sides share the deterministic collection order; an LCS walk extracts what
 * the simulation produced in excess ("+", relative to reality) and what it
 * failed to reproduce ("-", missing from the simulation).
 *
 * <p>The comparison keys are built once per stream by {@link #keys} and reused
 * by every diff against that stream. That matters because a batch of ~100 runs
 * diffs against one reference: rebuilding the reference's keys per run meant
 * re-rendering a full {@code BlockState.toString()} property dump for every
 * recorded event, every run.
 */
public final class DiffEngine
{
	/** The "no difference" result; {@link Diff} itself is immutable. */
	public static final Diff EMPTY = new Diff(List.of(), List.of());

	private DiffEngine()
	{
	}

	public record Diff(List<String> plus, List<String> minus)
	{
		public boolean isEmpty()
		{
			return this.plus.isEmpty() && this.minus.isEmpty();
		}

		/**
		 * True when the two streams hold the same events in a different order:
		 * the unmatched lines are identical as a multiset, so nothing is really
		 * extra or missing. Vanilla's position-hash dependent delivery order
		 * (e.g. the redstone wire evaluator's HashSet walk) makes this the common
		 * case for a legitimate position/orientation difference, and it must not
		 * be read as an extra/missing event.
		 */
		public boolean orderOnly()
		{
			if (this.isEmpty() || this.plus.size() != this.minus.size())
			{
				return false;
			}
			List<String> sortedPlus = new ArrayList<>(this.plus);
			List<String> sortedMinus = new ArrayList<>(this.minus);
			java.util.Collections.sort(sortedPlus);
			java.util.Collections.sort(sortedMinus);
			return sortedPlus.equals(sortedMinus);
		}

		public String key()
		{
			List<String> all = new ArrayList<>(this.plus.size() + this.minus.size());
			for (String p : this.plus)
			{
				all.add("+ " + p);
			}
			for (String m : this.minus)
			{
				all.add("- " + m);
			}
			java.util.Collections.sort(all);
			return String.join("\n", all);
		}

		/**
		 * True when two diffs describe the same outcome, i.e. what
		 * {@link #key()} would give the same string for: the same events in
		 * the same + / - buckets, regardless of the order within a bucket.
		 *
		 * <p>This is the grouping criterion the report uses, and it is what
		 * lets every order-only variant of a run collapse into one
		 * {@code Nx (...)} line. {@link #key()} computes the same thing by
		 * sorting and joining, which is too much work to repeat per run just
		 * to discover the outcome is already known - hence this.
		 */
		public boolean sameOutcome(Diff other)
		{
			return this.plus.size() == other.plus.size()
					&& this.minus.size() == other.minus.size()
					&& sameMultiset(this.plus, other.plus)
					&& sameMultiset(this.minus, other.minus);
		}
	}

	private static boolean sameMultiset(List<String> a, List<String> b)
	{
		if (a.isEmpty() && b.isEmpty())
		{
			return true;
		}
		List<String> sortedA = new ArrayList<>(a);
		List<String> sortedB = new ArrayList<>(b);
		java.util.Collections.sort(sortedA);
		java.util.Collections.sort(sortedB);
		return sortedA.equals(sortedB);
	}

	/**
	 * Comparison keys of one stream, in stream order. Build once, diff against
	 * many times.
	 */
	public static String[] keys(List<FstEvent> events)
	{
		String[] out = new String[events.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = display(events.get(i));
		}
		return out;
	}

	public static Diff diff(List<FstEvent> real, List<FstEvent> sim)
	{
		if (real == sim)
		{
			return EMPTY;
		}
		return diff(keys(real), keys(sim));
	}

	/**
	 * LCS over two pre-keyed streams. The matrix is a single flat {@code int[]}
	 * rather than {@code int[][]}: the inner loop reads the row above and the
	 * row to the right, and one contiguous allocation beats a million-element
	 * array of row references.
	 */
	public static Diff diff(String[] a, String[] b)
	{
		int n = a.length;
		int m = b.length;
		if (n == 0 && m == 0)
		{
			return EMPTY;
		}
		if (n == 0)
		{
			return new Diff(List.of(b), List.of());
		}
		if (m == 0)
		{
			return new Diff(List.of(), List.of(a));
		}
		if (Arrays.equals(a, b))
		{
			// the streams are identical: by far the most common outcome, and
			// the one that needs no matrix at all
			return EMPTY;
		}

		int width = m + 1;
		int[] dp = new int[(n + 1) * width];
		for (int i = n - 1; i >= 0; i--)
		{
			int row = i * width;
			int below = row + width;
			String ai = a[i];
			for (int j = m - 1; j >= 0; j--)
			{
				dp[row + j] = ai.equals(b[j])
						? dp[below + j + 1] + 1
						: Math.max(dp[below + j], dp[row + j + 1]);
			}
		}

		List<String> plus = new ArrayList<>();
		List<String> minus = new ArrayList<>();
		int i = 0;
		int j = 0;
		while (i < n && j < m)
		{
			if (a[i].equals(b[j]))
			{
				i++;
				j++;
			}
			else if (dp[(i + 1) * width + j] >= dp[i * width + j + 1])
			{
				minus.add(a[i++]);
			}
			else
			{
				plus.add(b[j++]);
			}
		}
		while (i < n)
		{
			minus.add(a[i++]);
		}
		while (j < m)
		{
			plus.add(b[j++]);
		}
		return new Diff(plus, minus);
	}

	private static String display(FstEvent event)
	{
		return event.pos().toShortString() + " | " + event.signature();
	}
}
