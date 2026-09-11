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

	public record Aggregation(Map<String, OutcomeAgg> outcomes, int identical, int total)
	{
	}

	private ReportFormatter()
	{
	}

	public static Aggregation aggregate(List<FstEvent> realEvents, List<ReplayEngine.RunOutcome> runs)
	{
		Map<String, OutcomeAgg> outcomes = new LinkedHashMap<>();
		int identical = 0;
		for (ReplayEngine.RunOutcome run : runs)
		{
			DiffEngine.Diff diff = DiffEngine.diff(realEvents, run.events());
			if (diff.isEmpty())
			{
				identical++;
				continue;
			}
			String key = diff.key();
			OutcomeAgg existing = outcomes.get(key);
			if (existing == null)
			{
				outcomes.put(key, new OutcomeAgg(1, new ArrayList<>(List.of(run.label())), diff));
			}
			else if (existing.labels().size() < MAX_LABELS_PER_OUTCOME)
			{
				existing.labels().add(run.label());
				outcomes.put(key, new OutcomeAgg(existing.count() + 1, existing.labels(), existing.sample()));
			}
			else
			{
				outcomes.put(key, new OutcomeAgg(existing.count() + 1, existing.labels(), existing.sample()));
			}
		}
		return new Aggregation(outcomes, identical, runs.size());
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
	                   List<FstEvent> realEvents, List<ReplayEngine.RunOutcome> runs)
	{
		Aggregation agg = aggregate(realEvents, runs);
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("fstest.report.header").withStyle(ChatFormatting.GOLD));
		lines.add(Component.translatable("fstest.report.runs",
				runs.size(), agg.identical(), realEvents.size()).withStyle(ChatFormatting.GRAY));

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
			lines.add(Component.translatable("fstest.report.all_match").withStyle(ChatFormatting.GREEN));
		}

		deliver(player, lines, false);
	}

	private static void appendOps(List<Component> lines, DiffEngine.Diff diff)
	{
		int limit = MAX_OPS_PER_OUTCOME * 2;
		int displayed = 0;
		for (String plus : diff.plus())
		{
			if (displayed >= limit)
			{
				break;
			}
			lines.add(Component.literal("+ ").withStyle(ChatFormatting.AQUA)
					.append(Component.literal(plus).withStyle(ChatFormatting.WHITE)));
			displayed++;
		}
		for (String minus : diff.minus())
		{
			if (displayed >= limit)
			{
				break;
			}
			lines.add(Component.literal("- ").withStyle(ChatFormatting.RED)
					.append(Component.literal(minus).withStyle(ChatFormatting.WHITE)));
			displayed++;
		}
		int remaining = diff.plus().size() + diff.minus().size() - displayed;
		if (remaining > 0)
		{
			lines.add(Component.translatable("fstest.report.more", remaining).withStyle(ChatFormatting.DARK_GRAY));
		}
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
