package fstest.analysis;

import fstest.FstestMod;
import fstest.capture.TriggerCapture;
import fstest.record.FstEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human-readable aggregation of run diffs, following the plan's output format:
 * N simulations may produce e.g. "50x (+... -...)", "20x (...)", grouped by
 * distinct outcome, with a count of runs that matched reality exactly.
 */
public final class ReportFormatter
{
	private static final int MAX_DISTINCT_OUTCOMES = 4;
	private static final int MAX_LABELS_PER_OUTCOME = 3;
	private static final int MAX_OPS_PER_OUTCOME = 6;

	public record OutcomeAgg(int count, List<String> labels, DiffEngine.Diff sample)
	{
	}

	/**
	 * Group the runs by distinct diff against the reference. The per-run diffs
	 * are returned alongside the aggregation so the file writer can reuse them
	 * instead of diffing every run a second time.
	 *
	 * <p>The reference's comparison keys are built once for the whole batch, and
	 * a run is matched against the known outcomes by
	 * {@link DiffEngine.Diff#sameOutcome} - the same criterion {@code key()}
	 * encodes, minus the sorted-and-joined string it would cost to build once
	 * per run just to learn "already seen".
	 */
	public record Aggregation(Map<String, OutcomeAgg> outcomes, int identical, int total,
	                          List<DiffEngine.Diff> perRun)
	{
	}

	private ReportFormatter()
	{
	}

	public static Aggregation aggregate(List<FstEvent> realEvents, List<ReplayEngine.RunOutcome> runs)
	{
		String[] referenceKeys = DiffEngine.keys(realEvents);
		Map<String, OutcomeAgg> outcomes = new LinkedHashMap<>();
		List<DiffEngine.Diff> perRun = new ArrayList<>(runs.size());
		int identical = 0;
		for (ReplayEngine.RunOutcome run : runs)
		{
			DiffEngine.Diff diff = DiffEngine.diff(referenceKeys, DiffEngine.keys(run.events()));
			perRun.add(diff);
			if (diff.isEmpty())
			{
				identical++;
				continue;
			}
			String key = keyOfExistingOutcome(outcomes, diff);
			if (key == null)
			{
				key = diff.key();
				outcomes.put(key, new OutcomeAgg(1, new ArrayList<>(List.of(run.label())), diff));
				continue;
			}
			OutcomeAgg existing = outcomes.get(key);
			List<String> labels = existing.labels();
			if (labels.size() < MAX_LABELS_PER_OUTCOME)
			{
				labels = new ArrayList<>(labels);
				labels.add(run.label());
			}
			outcomes.put(key, new OutcomeAgg(existing.count() + 1, labels, existing.sample()));
		}
		return new Aggregation(outcomes, identical, runs.size(), List.copyOf(perRun));
	}

	/**
	 * The key of the already-recorded outcome whose sample diff has the same
	 * content, or null for a genuinely new outcome. There are only ever a
	 * handful of distinct outcomes, so comparing them is far cheaper than
	 * rebuilding each diff's sorted-and-joined key.
	 */
	private static @Nullable String keyOfExistingOutcome(Map<String, OutcomeAgg> outcomes, DiffEngine.Diff diff)
	{
		for (Map.Entry<String, OutcomeAgg> entry : outcomes.entrySet())
		{
			if (entry.getValue().sample().sameOutcome(diff))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	static void message(@Nullable ServerPlayer player, String key)
	{
		Component text = Component.translatable(key).withStyle(ChatFormatting.GRAY);
		if (player != null)
		{
			player.sendSystemMessage(text);
		}
		FstestMod.LOGGER.info("[fstest] {}", text.getString());
	}

	static void error(@Nullable ServerPlayer player, String key)
	{
		Component text = Component.translatable(key).withStyle(ChatFormatting.RED);
		if (player != null)
		{
			player.sendSystemMessage(text);
		}
		FstestMod.LOGGER.error("[fstest] {}", text.getString());
	}

	/** One-line notice that an analysis batch has begun. */
	static void analysisStart(@Nullable ServerPlayer player, int total)
	{
		notice(player, Component.translatable("fstest.analysis.start", total));
	}

	/** Throttled progress notice, emitted by the analysis loop. */
	static void analysisProgress(@Nullable ServerPlayer player, int done, int total)
	{
		notice(player, Component.translatable("fstest.analysis.progress", done, total));
	}

	private static void notice(@Nullable ServerPlayer player, Component text)
	{
		Component styled = text.copy().withStyle(ChatFormatting.GRAY);
		if (player != null)
		{
			player.sendSystemMessage(styled);
		}
		FstestMod.LOGGER.info("[fstest] {}", styled.getString());
	}

	static void reportDistortion(@Nullable ServerPlayer player, DiffEngine.Diff baselineDiff)
	{
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("fstest.report.distortion").withStyle(ChatFormatting.DARK_RED));
		lines.add(Component.translatable("fstest.report.baseline_ops").withStyle(ChatFormatting.GRAY));
		appendOps(lines, baselineDiff);
		deliver(player, lines, true);
	}

	static void report(@Nullable ServerPlayer player, TriggerCapture.OpKind kind,
	                   int referenceEventCount, List<ReplayEngine.RunOutcome> runs,
	                   Aggregation agg, boolean mtrMode)
	{
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable(mtrMode ? "fstest.report.header.timed" : "fstest.report.header")
				.withStyle(ChatFormatting.GOLD));
		lines.add(Component.translatable(mtrMode ? "fstest.report.runs.timed" : "fstest.report.runs",
				runs.size(), agg.identical(), referenceEventCount).withStyle(ChatFormatting.GRAY));

		List<OutcomeAgg> sorted = new ArrayList<>(agg.outcomes().values());
		sorted.sort(Comparator.comparingInt(OutcomeAgg::count).reversed());

		for (int index = 0; index < sorted.size(); index++)
		{
			OutcomeAgg a = sorted.get(index);
			if (index >= MAX_DISTINCT_OUTCOMES)
			{
				lines.add(Component.translatable("fstest.report.hidden", sorted.size() - MAX_DISTINCT_OUTCOMES)
						.withStyle(ChatFormatting.DARK_GRAY));
				break;
			}
			lines.add(Component.translatable("fstest.report.outcome",
					a.count(), String.join(", ", a.labels())).withStyle(ChatFormatting.YELLOW));
			if (a.sample().orderOnly())
			{
				lines.add(Component.translatable("fstest.report.order_only").withStyle(ChatFormatting.AQUA));
			}
			appendOps(lines, a.sample());
		}

		if (sorted.isEmpty())
		{
			lines.add(Component.translatable(mtrMode ? "fstest.report.all_match.timed" : "fstest.report.all_match")
					.withStyle(ChatFormatting.GREEN));
		}

		deliver(player, lines, false);
	}

	/**
	 * Appends the sample diff lines grouped by monitored position (the spec's
	 * "grouped by marker block" output): a gray position header followed by the
	 * +/- events recorded at that position. The overall line budget is
	 * unchanged; overflow is reported as "(+N more)".
	 */
	private static void appendOps(List<Component> lines, DiffEngine.Diff diff)
	{
		int limit = MAX_OPS_PER_OUTCOME * 2;
		// encounter-ordered grouping: entries are "x y z | signature" display lines
		Map<String, List<Component>> byPosition = new LinkedHashMap<>();
		for (String plus : diff.plus())
		{
			byPosition.computeIfAbsent(positionOf(plus), k -> new ArrayList<>())
					.add(Component.literal("+ ").withStyle(ChatFormatting.AQUA)
							.append(Component.literal(plus).withStyle(ChatFormatting.WHITE)));
		}
		for (String minus : diff.minus())
		{
			byPosition.computeIfAbsent(positionOf(minus), k -> new ArrayList<>())
					.add(Component.literal("- ").withStyle(ChatFormatting.RED)
							.append(Component.literal(minus).withStyle(ChatFormatting.WHITE)));
		}
		int displayed = 0;
		int entries = 0;
		for (Map.Entry<String, List<Component>> entry : byPosition.entrySet())
		{
			if (displayed >= limit)
			{
				break;
			}
			if (byPosition.size() > 1)
			{
				lines.add(Component.literal("@ " + entry.getKey()).withStyle(ChatFormatting.GRAY));
			}
			for (Component line : entry.getValue())
			{
				if (displayed >= limit)
				{
					break;
				}
				lines.add(line);
				displayed++;
			}
			entries += entry.getValue().size();
		}
		int remaining = entries - displayed;
		if (remaining > 0)
		{
			lines.add(Component.translatable("fstest.report.more", remaining).withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	/** Extracts the position prefix of a diff display line ("x y z | signature"). */
	private static String positionOf(String displayLine)
	{
		int separator = displayLine.indexOf(" | ");
		return separator >= 0 ? displayLine.substring(0, separator) : displayLine;
	}

	private static void deliver(@Nullable ServerPlayer player, List<Component> lines, boolean isError)
	{
		if (player != null)
		{
			for (Component line : lines)
			{
				player.sendSystemMessage(line);
			}
		}
		StringBuilder sb = new StringBuilder("[fstest]");
		for (Component line : lines)
		{
			sb.append(' ').append(line.getString()).append(';');
		}
		if (isError)
		{
			FstestMod.LOGGER.warn("{}", sb);
		}
		else
		{
			FstestMod.LOGGER.info("{}", sb);
		}
	}
}
