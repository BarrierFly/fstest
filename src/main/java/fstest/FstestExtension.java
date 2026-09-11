package fstest;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.mojang.brigadier.CommandDispatcher;
import fstest.command.FstestCommand;
import fstest.config.FstestConfig;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

/**
 * Carpet extension hook. All functionality lives behind {@code /fstest},
 * registered through Carpet's command registration phase.
 */
public class FstestExtension implements CarpetExtension
{
	@Override
	public void onGameStarted()
	{
		// sticky configuration lives in memory only; restart falls back to defaults (v1)
		FstestConfig.reset();
	}

	@Override
	public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext)
	{
		FstestCommand.register(dispatcher);
	}

	@Override
	public String version()
	{
		return "fstest";
	}
}
