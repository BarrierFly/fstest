package fstest.analysis;

import fstest.record.FstEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Sequence diff between two event streams.
 *
 * Streams align element by element because game time is identical and both
 * sides share the deterministic collection order; an LCS walk extracts what
 * the simulation produced in excess ("+", relative to reality) and what it
 * failed to reproduce ("-", missing from the simulation).
 */
public final class DiffEngine
{
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
	}

	public static Diff diff(List<FstEvent> real, List<FstEvent> sim)
	{
		String[] a = real.stream().map(DiffEngine::display).toArray(String[]::new);
		String[] b = sim.stream().map(DiffEngine::display).toArray(String[]::new);

		int[][] dp = new int[a.length + 1][b.length + 1];
		for (int i = a.length - 1; i >= 0; i--)
		{
			for (int j = b.length - 1; j >= 0; j--)
			{
				dp[i][j] = a[i].equals(b[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
			}
		}

		List<String> plus = new ArrayList<>();
		List<String> minus = new ArrayList<>();
		int i = 0, j = 0;
		while (i < a.length && j < b.length)
		{
			if (a[i].equals(b[j]))
			{
				i++;
				j++;
			}
			else if (dp[i + 1][j] >= dp[i][j + 1])
			{
				minus.add(a[i++]);
			}
			else
			{
				plus.add(b[j++]);
			}
		}
		while (i < a.length)
		{
			minus.add(a[i++]);
		}
		while (j < b.length)
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
