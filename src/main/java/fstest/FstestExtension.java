package fstest;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.mojang.brigadier.CommandDispatcher;
import fstest.command.FstestCommand;
import fstest.config.FstestConfig;
import fstest.config.FstestPersistence;
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
		FstestConfig.reset();
		// sticky configuration, persisted to <config>/fstest.json
		FstestPersistence.initialize();
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
