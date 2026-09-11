package fstest.record;

import fstest.FstestMod;
import fstest.analysis.DiffEngine;
import fstest.analysis.ReplayEngine;
import fstest.analysis.ReportFormatter;
import fstest.analysis.ReplayEngine.RunOutcome;
import fstest.capture.TriggerCapture;
import fstest.config.FstestConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persists one text report per accepted analysis window under
 * {@code <runDir>/fstest-logs/}. Every event recorded on the monitored
 * positions is written, with consecutive duplicates collapsed to a single
 * line plus a {@code \u00d7N} count. JSONL output is planned for v2.
 */
public final class SimulationLog
{
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT);
	private static final int MAX_HEADER_LABELS = 6;
	/** How many mismatch positions the per-run stats line spells out before eliding. */
	private static final int MAX_MISMATCH_POSITIONS = 4;

	private SimulationLog()
	{
	}

	/**
	 * Write a single analysis window to disk. The file is closed before this
	 * method returns; failures are logged and swallowed (writing a log must
	 * never crash the analysis path).
	 */
	public static void write(ServerLevel level, @Nullable ServerPlayer player, TriggerCapture.OpKind kind,
	                         BlockPos anchor, CaptureSession realSession, FstestConfig cfg, String snapshotSource,
	                         @Nullable RunOutcome baseline, List<RunOutcome> runs)
	{
		Path file;
		try
		{
			Path dir = baseDir();
			Files.createDirectories(dir);
			String playerTag = player != null ? sanitize(player.getName().getString()) : "console";
			String stamp = LocalDateTime.now().format(STAMP);
			String name = String.format(Locale.ROOT, "%s_%s_at_%d_%d_%d_%s.txt",
					stamp, kind, anchor.getX(), anchor.getY(), anchor.getZ(), playerTag);
			file = dir.resolve(name);
		}
		catch (IOException e)
		{
			FstestMod.LOGGER.error("[fstest] could not prepare simulation log directory", e);
			return;
		}

		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
		{
			writeHeader(w, level, player, kind, anchor, cfg, realSession, snapshotSource, baseline, runs);
			writeEvents(w, "REAL", realSession.events);
			if (baseline != null)
			{
				writeEvents(w, "BASELINE (identity, 0\u00b0 no mirror)", baseline.events());
				writeDiff(w, "BASELINE vs REAL", realSession.events, baseline.events());
			}
			for (ReplayEngine.RunOutcome run : runs)
			{
				writeEvents(w, "RUN: " + run.label(), run.events());
				writeRunStats(w, run);
				writeDiff(w, "RUN " + run.label() + " vs REAL", realSession.events, run.events());
			}
			ReportFormatter.Aggregation agg = ReportFormatter.aggregate(realSession.events, runs);
			writeSummary(w, agg);
			w.flush();
		}
		catch (IOException e)
		{
			FstestMod.LOGGER.error("[fstest] failed to write simulation log " + file, e);
			return;
		}

		if (player != null)
		{
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
					"[fstest] full log: " + file.toAbsolutePath())
					.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		}
		FstestMod.LOGGER.info("[fstest] simulation log written to {}", file.toAbsolutePath());
	}

	private static Path baseDir()
	{
		return Paths.get(System.getProperty("user.dir", "."), "fstest-logs");
	}

	private static String sanitize(String s)
	{
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-')
			{
				out.append(c);
			}
			else
			{
				out.append('_');
			}
		}
		return out.length() == 0 ? "player" : out.toString();
	}

	private static void writeHeader(BufferedWriter w, ServerLevel level, @Nullable ServerPlayer player,
	                                TriggerCapture.OpKind kind, BlockPos anchor, FstestConfig cfg,
	                                CaptureSession realSession, String snapshotSource,
	                                @Nullable RunOutcome baseline,
	                                List<ReplayEngine.RunOutcome> runs) throws IOException
	{
		String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		w.write("=== Fengshui Tester simulation log ==="); w.newLine();
		w.write("mod version      : " + modVersion()); w.newLine();
		w.write("timestamp        : " + ts); w.newLine();
		w.write("player           : " + (player != null ? player.getName().getString() : "<console>")); w.newLine();
		w.write("operation        : " + kind); w.newLine();
		w.write("anchor           : " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ()); w.newLine();
		w.write("dimension        : " + level.dimension().identifier()); w.newLine();
		w.write("game time        : " + level.getGameTime()); w.newLine();
		w.write("mode             : " + cfg.mode()); w.newLine();
		w.write("color filter     : " + (cfg.color().isEmpty() ? "all" : cfg.color().get().getName())); w.newLine();
		w.write("count d/p/pd     : " + cfg.countD() + " / " + cfg.countP() + " / " + cfg.countPd()); w.newLine();
		w.write("range            : " + (cfg.isRangeUnlimited() ? "unlimited" : Integer.toString(cfg.range()))); w.newLine();
		w.write("pstrategy        : " + cfg.strategy()); w.newLine();
		w.write("updates          : " + (cfg.updates() ? "on" : "off")); w.newLine();
		w.write("duplications     : " + (cfg.duplications() ? "on" : "off")); w.newLine();
		w.write("targets          : " + cfg.targets().size()); w.newLine();
		w.write("real events      : " + realSession.events.size()); w.newLine();
		w.write("monitored roots  : " + realSession.rootChanges.size()); w.newLine();
		w.write("tick attempts    : " + realSession.createdTicks.size()
				+ " (" + recordedOf(realSession.events, FstEvent.SchedTickCreate.class) + " recorded)"); w.newLine();
		w.write("event attempts   : " + realSession.createdBlockEvents.size()
				+ " (" + recordedOf(realSession.events, FstEvent.BlockEventCreate.class) + " recorded)"); w.newLine();
		w.write("snapshot         : " + snapshotSource); w.newLine();
		if (baseline != null)
		{
			w.write("baseline replay  : " + baseline.stats().rootsApplied() + "/" + baseline.stats().rootsTotal()
					+ " roots, setBlocks " + baseline.stats().setBlockCalls()
					+ ", nuDispatches " + baseline.stats().neighborUpdateDispatches()
					+ ", eventAttempts " + baseline.stats().createdBlockEvents()
					+ ", tickAttempts " + baseline.stats().createdTicks()
					+ ", rawEvents " + baseline.stats().rawEvents()); w.newLine();
			DiffEngine.Diff baselineDiff = DiffEngine.diff(realSession.events, baseline.events());
			w.write("baseline match   : " + (baselineDiff.isEmpty() ? "OK" : "DISTORTION (see BASELINE vs REAL)")); w.newLine();
		}
		w.write("simulations      : " + runs.size()); w.newLine();
		w.newLine();
	}

	private static String modVersion()
	{
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("fstest")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	private static void writeEvents(BufferedWriter w, String sectionTitle, List<FstEvent> events) throws IOException
	{
		w.write("--- " + sectionTitle + " (" + events.size() + " event(s)) ---");
		w.newLine();
		Map<String, DedupEntry> byKey = new LinkedHashMap<>();
		for (FstEvent e : events)
		{
			String key = e.key();
			DedupEntry entry = byKey.computeIfAbsent(key, k -> new DedupEntry(formatEvent(e)));
			entry.count++;
		}
		for (DedupEntry entry : byKey.values())
		{
			w.write(entry.count > 1 ? (entry.line + "  \u00d7" + entry.count) : entry.line);
			w.newLine();
		}
		w.newLine();
	}

	private static void writeRunStats(BufferedWriter w, ReplayEngine.RunOutcome run) throws IOException
	{
		ReplayEngine.RunStats s = run.stats();
		StringBuilder line = new StringBuilder(96);
		line.append("    stats: roots ").append(s.rootsApplied()).append('/').append(s.rootsTotal())
				.append(", setBlocks ").append(s.setBlockCalls())
				.append(", nuDispatches ").append(s.neighborUpdateDispatches())
				.append(", eventAttempts ").append(s.createdBlockEvents())
				.append(", tickAttempts ").append(s.createdTicks())
				.append(", rawEvents ").append(s.rawEvents());
		if (!run.offset().equals(BlockPos.ZERO))
		{
			line.append(", offset ").append(run.offset().getX())
					.append(' ').append(run.offset().getY())
					.append(' ').append(run.offset().getZ());
		}
		if (!s.sideEffectContextMismatches().isEmpty())
		{
			// the removal side-effect replay landed in a different cascade context
			// than the captured one, so its relative timing is not guaranteed
			line.append(", sideEffectCtxMismatch ").append(s.sideEffectContextMismatches().size()).append(" @");
			int shown = 0;
			for (BlockPos pos : s.sideEffectContextMismatches())
			{
				if (shown++ >= MAX_MISMATCH_POSITIONS)
				{
					line.append(" ...");
					break;
				}
				line.append(' ').append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ());
			}
		}
		w.write(line.toString());
		w.newLine();
	}

	private static void writeDiff(BufferedWriter w, String sectionTitle, List<FstEvent> real, List<FstEvent> sim) throws IOException
	{
		DiffEngine.Diff diff = DiffEngine.diff(real, sim);
		if (diff.isEmpty())
		{
			w.write("--- " + sectionTitle + " (matches reality exactly) ---");
			w.newLine();
			w.newLine();
			return;
		}
		if (diff.orderOnly())
		{
			// same events, different order: a legitimate position/orientation
			// dependent delivery order, not an extra/missing event
			w.write("--- " + sectionTitle + " (order change only: "
					+ diff.plus().size() + " event(s) in a different order) ---");
			w.newLine();
		}
		else
		{
			w.write("--- " + sectionTitle + " (+ " + diff.plus().size()
					+ " sim extra, - " + diff.minus().size() + " sim missing) ---");
			w.newLine();
		}
		for (String p : diff.plus())
		{
			w.write("+ " + p); w.newLine();
		}
		for (String m : diff.minus())
		{
			w.write("- " + m); w.newLine();
		}
		w.newLine();
	}

	private static long recordedOf(List<FstEvent> events, Class<? extends FstEvent> type)
	{
		return events.stream().filter(type::isInstance).count();
	}

	private static void writeSummary(BufferedWriter w, ReportFormatter.Aggregation agg) throws IOException
	{
		w.write("--- aggregated summary ---"); w.newLine();
		w.write("total simulations : " + agg.total()); w.newLine();
		w.write("matched exactly   : " + agg.identical()); w.newLine();
		w.write("distinct outcomes : " + agg.outcomes().size()); w.newLine();
		w.newLine();
		// deterministic order: insertion order (which mirrors runs order)
		int shown = 0;
		for (Map.Entry<String, ReportFormatter.OutcomeAgg> e : agg.outcomes().entrySet())
		{
			ReportFormatter.OutcomeAgg o = e.getValue();
			List<String> labels = o.labels();
			String labelField = labels.size() < MAX_HEADER_LABELS
					? String.join(", ", labels)
					: String.join(", ", labels.subList(0, MAX_HEADER_LABELS)) + ", ...";
			w.write("=== " + o.count() + "x [" + labelField + "] ==="); w.newLine();
			if (o.sample().orderOnly())
			{
				w.write("(order change only: the same events in a different order)"); w.newLine();
			}
			for (String p : o.sample().plus())
			{
				w.write("+ " + p); w.newLine();
			}
			for (String m : o.sample().minus())
			{
				w.write("- " + m); w.newLine();
			}
			w.newLine();
			shown++;
		}
	}

	private static String formatEvent(FstEvent event)
	{
		return event.pos().toShortString() + " | " + event.signature();
	}

	private static final class DedupEntry
	{
		final String line;
		int count = 0;

		DedupEntry(String line)
		{
			this.line = line;
		}
	}

	/** Public so server commands (planned v2) can resolve the same directory. */
	public static Path resolveLogDir()
	{
		return baseDir();
	}
}
