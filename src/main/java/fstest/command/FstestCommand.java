package fstest.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import fstest.config.FstestConfig;
import fstest.config.FstestMode;
import fstest.config.PStrategy;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * /fstest command tree (permission level 2, matching carpet's /log):
 *
 * /fstest mode <none|directionality|positionality|both>
 * /fstest color <all|dye color>
 * /fstest count <d|p|pd> <n>
 * /fstest range <unlimited|r>
 * /fstest pstrategy <uniform|chunkborder|hashdelta>
 * /fstest updates <on|off>
 * /fstest duplications <on|off>
 * /fstest mtr <ticks|off>
 * /fstest mtrarea set <pos1> <pos2>
 * /fstest mtrarea clear
 * /fstest mtrarea query
 * /fstest targets add <color> <x> <y> <z>
 * /fstest targets remove <x> <y> <z>
 * /fstest targets remove color <color>
 * /fstest targets clear
 * /fstest targets query
 * /fstest query
 *
 * Every subcommand (and every partially filled argument chain) prints its own
 * usage when required arguments are missing; bare /fstest prints the command list.
 */
public final class FstestCommand
{
	private FstestCommand()
	{
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
	{
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("fstest")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> usage(ctx, "fstest.usage"))
				.then(Commands.literal("mode")
						.executes(ctx -> usage(ctx, "fstest.mode.usage"))
						.then(Commands.argument("value", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										List.of("none", "directionality", "positionality", "both"), builder))
								.executes(ctx -> setMode(ctx, StringArgumentType.getString(ctx, "value")))))
				.then(Commands.literal("color")
						.executes(ctx -> usage(ctx, "fstest.color.usage"))
						.then(Commands.argument("value", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										colorSuggestions(true), builder))
								.executes(ctx -> setColor(ctx, StringArgumentType.getString(ctx, "value")))))
				.then(Commands.literal("count")
						.executes(ctx -> usage(ctx, "fstest.count.usage"))
						.then(Commands.argument("which", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										List.of("d", "p", "pd"), builder))
								.executes(ctx -> usage(ctx, "fstest.count.usage"))
								.then(Commands.argument("n", IntegerArgumentType.integer(0))
										.executes(ctx -> setCount(ctx,
												StringArgumentType.getString(ctx, "which"),
												IntegerArgumentType.getInteger(ctx, "n"))))))
				.then(Commands.literal("range")
						.executes(ctx -> usage(ctx, "fstest.range.usage"))
						.then(Commands.argument("value", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										List.of("unlimited"), builder))
								.executes(ctx -> setRange(ctx, StringArgumentType.getString(ctx, "value")))))
				.then(Commands.literal("pstrategy")
						.executes(ctx -> usage(ctx, "fstest.pstrategy.usage"))
						.then(Commands.argument("value", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										List.of("uniform", "chunkborder", "hashdelta"), builder))
								.executes(ctx -> setPStrategy(ctx, StringArgumentType.getString(ctx, "value")))))
				.then(Commands.literal("updates")
						.executes(ctx -> usage(ctx, "fstest.updates.usage"))
						.then(toggleArgument().executes(ctx -> setUpdates(ctx,
								StringArgumentType.getString(ctx, "value")))))
				.then(Commands.literal("duplications")
						.executes(ctx -> usage(ctx, "fstest.duplications.usage"))
						.then(toggleArgument().executes(ctx -> setDuplications(ctx,
								StringArgumentType.getString(ctx, "value")))))
				.then(Commands.literal("mtr")
						.executes(ctx -> usage(ctx, "fstest.mtr.usage"))
						.then(Commands.argument("value", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										List.of("off"), builder))
								.executes(ctx -> setMtr(ctx, StringArgumentType.getString(ctx, "value")))))
				.then(Commands.literal("mtrarea")
						.executes(ctx -> usage(ctx, "fstest.mtrarea.usage"))
						.then(Commands.literal("set")
								.executes(ctx -> usage(ctx, "fstest.mtrarea.set.usage"))
								.then(Commands.argument("pos1", BlockPosArgument.blockPos())
										.executes(ctx -> usage(ctx, "fstest.mtrarea.set.usage"))
										.then(Commands.argument("pos2", BlockPosArgument.blockPos())
												.executes(FstestCommand::mtrAreaSet))))
						.then(Commands.literal("clear").executes(FstestCommand::mtrAreaClear))
						.then(Commands.literal("query").executes(FstestCommand::mtrAreaQuery)))
				.then(Commands.literal("targets")
						.executes(ctx -> usage(ctx, "fstest.targets.usage"))
						.then(Commands.literal("add")
								.executes(ctx -> usage(ctx, "fstest.targets.add.usage"))
								.then(Commands.argument("color", StringArgumentType.word())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
												colorSuggestions(false), builder))
										.executes(ctx -> usage(ctx, "fstest.targets.add.usage"))
										.then(Commands.argument("pos", BlockPosArgument.blockPos())
												.executes(FstestCommand::targetsAdd))))
						.then(Commands.literal("remove")
								.executes(ctx -> usage(ctx, "fstest.targets.remove.usage"))
								.then(Commands.argument("pos", BlockPosArgument.blockPos())
										.executes(FstestCommand::targetsRemove))
								.then(Commands.literal("color")
										.executes(ctx -> usage(ctx, "fstest.targets.remove_color.usage"))
										.then(Commands.argument("color", StringArgumentType.word())
												.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
														colorSuggestions(false), builder))
												.executes(FstestCommand::targetsRemoveColor))))
						.then(Commands.literal("clear").executes(FstestCommand::targetsClear))
						.then(Commands.literal("query").executes(FstestCommand::targetsQuery)))
				.then(Commands.literal("query").executes(FstestCommand::query));

		dispatcher.register(root);
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> toggleArgument()
	{
		return Commands.argument("value", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off"), builder));
	}

	/** Colour names for suggestions; {@code includeAll} adds the "all" sentinel. */
	private static List<String> colorSuggestions(boolean includeAll)
	{
		List<String> suggestions = new ArrayList<>();
		if (includeAll)
		{
			suggestions.add("all");
		}
		for (DyeColor color : DyeColor.values())
		{
			suggestions.add(color.getName());
		}
		return suggestions;
	}

	/** Parses a wool colour name; "all" is handled by the caller, never here. */
	private static DyeColor parseColor(String value)
	{
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (DyeColor color : DyeColor.values())
		{
			if (color.getName().equals(normalized) || color.name().toLowerCase(Locale.ROOT).equals(normalized))
			{
				return color;
			}
		}
		return null;
	}

	private static Boolean parseToggle(String value)
	{
		return switch (value.trim().toLowerCase(Locale.ROOT))
		{
			case "on", "true", "yes", "1" -> Boolean.TRUE;
			case "off", "false", "no", "0" -> Boolean.FALSE;
			default -> null;
		};
	}

	private static int setMode(CommandContext<CommandSourceStack> ctx, String value)
	{
		FstestMode mode = FstestMode.parse(value);
		if (mode == null)
		{
			return fail(ctx, "fstest.mode.invalid", yellow(value));
		}
		FstestConfig.INSTANCE.setMode(mode);
		return ok(ctx, "fstest.mode.set", modeLabel(mode));
	}

	private static int setColor(CommandContext<CommandSourceStack> ctx, String value)
	{
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("all"))
		{
			FstestConfig.INSTANCE.setColor(Optional.empty());
			return ok(ctx, "fstest.color.set", Component.translatable("fstest.color.all").withStyle(ChatFormatting.YELLOW));
		}
		DyeColor color = parseColor(value);
		if (color == null)
		{
			return fail(ctx, "fstest.color.unknown", yellow(value));
		}
		FstestConfig.INSTANCE.setColor(Optional.of(color));
		return ok(ctx, "fstest.color.set", yellow(color.getName()));
	}

	private static int setCount(CommandContext<CommandSourceStack> ctx, String which, int n)
	{
		if (!FstestConfig.INSTANCE.setCount(which, n))
		{
			return fail(ctx, "fstest.count.usage");
		}
		return ok(ctx, "fstest.count.set", yellow(which.toLowerCase(Locale.ROOT)), yellow(String.valueOf(n)));
	}

	private static int setRange(CommandContext<CommandSourceStack> ctx, String value)
	{
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		int r;
		if (normalized.equals("unlimited"))
		{
			r = -1;
		}
		else
		{
			try
			{
				r = Integer.parseInt(normalized);
			}
			catch (NumberFormatException e)
			{
				return fail(ctx, "fstest.range.usage");
			}
		}
		if (!FstestConfig.INSTANCE.setRange(r))
		{
			return fail(ctx, "fstest.range.usage");
		}
		Component shown = r == -1
				? Component.translatable("fstest.range.unlimited").withStyle(ChatFormatting.YELLOW)
				: yellow(String.valueOf(r));
		return ok(ctx, "fstest.range.set", shown);
	}

	private static int setPStrategy(CommandContext<CommandSourceStack> ctx, String value)
	{
		PStrategy strategy = switch (value.trim().toLowerCase(Locale.ROOT))
		{
			case "uniform" -> PStrategy.UNIFORM;
			case "chunkborder" -> PStrategy.CHUNK_BORDER;
			case "hashdelta" -> PStrategy.HASH_DELTA;
			default -> null;
		};
		if (strategy == null)
		{
			return fail(ctx, "fstest.pstrategy.invalid", yellow(value));
		}
		if (strategy == PStrategy.HASH_DELTA)
		{
			return fail(ctx, "fstest.pstrategy.v2only");
		}
		FstestConfig.INSTANCE.setStrategy(strategy);
		return ok(ctx, "fstest.pstrategy.set", strategyLabel(strategy));
	}

	private static int setUpdates(CommandContext<CommandSourceStack> ctx, String value)
	{
		Boolean parsed = parseToggle(value);
		if (parsed == null)
		{
			return fail(ctx, "fstest.updates.usage");
		}
		FstestConfig.INSTANCE.setUpdates(parsed);
		return ok(ctx, "fstest.updates.set", toggleLabel(parsed));
	}

	private static int setDuplications(CommandContext<CommandSourceStack> ctx, String value)
	{
		Boolean parsed = parseToggle(value);
		if (parsed == null)
		{
			return fail(ctx, "fstest.duplications.usage");
		}
		FstestConfig.INSTANCE.setDuplications(parsed);
		return ok(ctx, "fstest.duplications.set", toggleLabel(parsed));
	}

	private static int setMtr(CommandContext<CommandSourceStack> ctx, String value)
	{
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("off") || normalized.equals("0"))
		{
			FstestConfig.INSTANCE.setMtrTicks(0);
			return ok(ctx, "fstest.mtr.off");
		}
		int ticks;
		try
		{
			ticks = Integer.parseInt(normalized);
		}
		catch (NumberFormatException e)
		{
			return fail(ctx, "fstest.mtr.usage");
		}
		if (!FstestConfig.INSTANCE.setMtrTicks(ticks))
		{
			return fail(ctx, "fstest.mtr.usage");
		}
		return ok(ctx, "fstest.mtr.set", yellow(String.valueOf(ticks)));
	}

	private static int mtrAreaSet(CommandContext<CommandSourceStack> ctx)
	{
		BlockPos pos1 = BlockPosArgument.getBlockPos(ctx, "pos1");
		BlockPos pos2 = BlockPosArgument.getBlockPos(ctx, "pos2");
		FstestConfig.Area area = FstestConfig.Area.of(pos1, pos2);
		if (area.sideX() >= FstestConfig.MAX_AREA_SIDE || area.sideY() >= FstestConfig.MAX_AREA_SIDE
				|| area.sideZ() >= FstestConfig.MAX_AREA_SIDE)
		{
			return fail(ctx, "fstest.mtrarea.too_large", yellow(String.valueOf(FstestConfig.MAX_AREA_SIDE)));
		}
		FstestConfig.INSTANCE.setMtrArea(area);
		return ok(ctx, "fstest.mtrarea.set",
				posText(area.pos1()), posText(area.pos2()));
	}

	private static int mtrAreaClear(CommandContext<CommandSourceStack> ctx)
	{
		if (FstestConfig.INSTANCE.mtrArea() == null)
		{
			return fail(ctx, "fstest.mtrarea.clear_empty");
		}
		FstestConfig.INSTANCE.setMtrArea(null);
		return ok(ctx, "fstest.mtrarea.cleared");
	}

	private static int mtrAreaQuery(CommandContext<CommandSourceStack> ctx)
	{
		FstestConfig.Area area = FstestConfig.INSTANCE.mtrArea();
		Component message = area == null
				? Component.translatable("fstest.mtrarea.query.empty").withStyle(ChatFormatting.GRAY)
				: Component.translatable("fstest.mtrarea.query.set",
						posText(area.pos1()), posText(area.pos2())).withStyle(ChatFormatting.GOLD);
		ctx.getSource().sendSuccess(() -> message, false);
		return com.mojang.brigadier.Command.SINGLE_SUCCESS;
	}

	private static int targetsAdd(CommandContext<CommandSourceStack> ctx)
	{
		String colorArg = StringArgumentType.getString(ctx, "color");
		DyeColor color = parseColor(colorArg);
		if (color == null)
		{
			return fail(ctx, "fstest.color.unknown", yellow(colorArg));
		}
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		Optional<DyeColor> previous = FstestConfig.INSTANCE.putTarget(pos, color);
		if (previous.isPresent())
		{
			return ok(ctx, "fstest.targets.overridden", posText(pos), yellow(previous.get().getName()), yellow(color.getName()));
		}
		return ok(ctx, "fstest.targets.added", posText(pos), yellow(color.getName()));
	}

	private static int targetsRemove(CommandContext<CommandSourceStack> ctx)
	{
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		Optional<DyeColor> removed = FstestConfig.INSTANCE.removeTarget(pos);
		if (removed.isEmpty())
		{
			return fail(ctx, "fstest.targets.remove_missing", posText(pos));
		}
		return ok(ctx, "fstest.targets.removed", posText(pos), yellow(removed.get().getName()));
	}

	private static int targetsRemoveColor(CommandContext<CommandSourceStack> ctx)
	{
		String colorArg = StringArgumentType.getString(ctx, "color");
		DyeColor color = parseColor(colorArg);
		if (color == null)
		{
			return fail(ctx, "fstest.color.unknown", yellow(colorArg));
		}
		int removed = FstestConfig.INSTANCE.removeTargetsOfColor(color);
		if (removed == 0)
		{
			return fail(ctx, "fstest.targets.none_of_color", yellow(color.getName()));
		}
		return ok(ctx, "fstest.targets.removed_color", yellow(String.valueOf(removed)), yellow(color.getName()));
	}

	private static int targetsClear(CommandContext<CommandSourceStack> ctx)
	{
		int removed = FstestConfig.INSTANCE.clearTargets();
		if (removed == 0)
		{
			return fail(ctx, "fstest.targets.clear_empty");
		}
		return ok(ctx, "fstest.targets.cleared", yellow(String.valueOf(removed)));
	}

	private static int targetsQuery(CommandContext<CommandSourceStack> ctx)
	{
		Map<BlockPos, DyeColor> targets = FstestConfig.INSTANCE.targets();
		Component message;
		if (targets.isEmpty())
		{
			message = Component.translatable("fstest.targets.query.empty").withStyle(ChatFormatting.GRAY);
		}
		else
		{
			Component body = Component.empty()
					.append(Component.translatable("fstest.targets.query.header", targets.size()).withStyle(ChatFormatting.GOLD))
					.append("\n");
			for (Map.Entry<BlockPos, DyeColor> entry : targets.entrySet())
			{
				Component entryLine = Component.translatable("fstest.targets.query.entry",
						posText(entry.getKey()), yellow(entry.getValue().getName()));
				if (!FstestConfig.INSTANCE.acceptsColor(entry.getValue()))
				{
					entryLine = entryLine.copy().append(Component.translatable("fstest.targets.query.filtered")
							.withStyle(ChatFormatting.DARK_GRAY));
				}
				body = body.copy().append(entryLine).append("\n");
			}
			message = body;
		}
		ctx.getSource().sendSuccess(() -> message, false);
		return com.mojang.brigadier.Command.SINGLE_SUCCESS;
	}

	private static int query(CommandContext<CommandSourceStack> ctx)
	{
		FstestConfig cfg = FstestConfig.INSTANCE;
		Optional<DyeColor> color = cfg.color();
		Component message = Component.empty()
				.append(Component.translatable("fstest.query.header").withStyle(ChatFormatting.GOLD)).append("\n")
				.append(line("fstest.query.mode", modeLabel(cfg.mode())))
				.append(line("fstest.query.color", color.isEmpty()
						? Component.translatable("fstest.color.all").withStyle(ChatFormatting.YELLOW)
						: yellow(color.get().getName())))
				.append(line("fstest.query.count_d", yellow(String.valueOf(cfg.countD()))))
				.append(line("fstest.query.count_p", yellow(String.valueOf(cfg.countP()))))
				.append(line("fstest.query.count_pd", yellow(String.valueOf(cfg.countPd()))))
				.append(line("fstest.query.range", cfg.isRangeUnlimited()
						? Component.translatable("fstest.range.unlimited").withStyle(ChatFormatting.YELLOW)
						: yellow(String.valueOf(cfg.range()))))
				.append(line("fstest.query.pstrategy", strategyLabel(cfg.strategy())))
				.append(line("fstest.query.updates", toggleLabel(cfg.updates())))
				.append(line("fstest.query.duplications", toggleLabel(cfg.duplications())))
				.append(line("fstest.query.mtr", cfg.isMtrEnabled()
						? yellow(cfg.mtrTicks() + " ticks")
						: Component.translatable("fstest.toggle.off").withStyle(ChatFormatting.RED)))
				.append(line("fstest.query.mtrarea", areaLabel(cfg.mtrArea())))
				.append(line("fstest.query.targets", yellow(String.valueOf(cfg.targets().size()))));
		ctx.getSource().sendSuccess(() -> message, false);
		return com.mojang.brigadier.Command.SINGLE_SUCCESS;
	}

	private static Component line(String key, Component value)
	{
		return Component.translatable(key).withStyle(ChatFormatting.GRAY)
				.append(Component.literal(": "))
				.append(value)
				.append("\n");
	}

	private static Component modeLabel(FstestMode mode)
	{
		return Component.translatable("fstest.mode." + mode.name().toLowerCase(Locale.ROOT))
				.withStyle(ChatFormatting.YELLOW);
	}

	private static Component strategyLabel(PStrategy strategy)
	{
		return Component.translatable("fstest.strategy." + strategy.name().toLowerCase(Locale.ROOT))
				.withStyle(ChatFormatting.YELLOW);
	}

	private static Component toggleLabel(boolean value)
	{
		return Component.translatable(value ? "fstest.toggle.on" : "fstest.toggle.off")
				.withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private static Component posText(BlockPos pos)
	{
		return yellow(pos.getX() + " " + pos.getY() + " " + pos.getZ());
	}

	private static Component areaLabel(FstestConfig.Area area)
	{
		if (area == null)
		{
			return Component.translatable("fstest.mtrarea.query.empty").withStyle(ChatFormatting.GRAY);
		}
		return yellow(area.pos1().getX() + " " + area.pos1().getY() + " " + area.pos1().getZ()
				+ " -> " + area.pos2().getX() + " " + area.pos2().getY() + " " + area.pos2().getZ());
	}

	private static Component yellow(String text)
	{
		return Component.literal(text).withStyle(ChatFormatting.YELLOW);
	}

	private static int usage(CommandContext<CommandSourceStack> ctx, String key)
	{
		return fail(ctx, key);
	}

	private static int ok(CommandContext<CommandSourceStack> ctx, String key, Object... args)
	{
		ctx.getSource().sendSuccess(() -> Component.translatable(key, args), false);
		return com.mojang.brigadier.Command.SINGLE_SUCCESS;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String key, Object... args)
	{
		ctx.getSource().sendFailure(Component.translatable(key, args));
		return 0;
	}
}
