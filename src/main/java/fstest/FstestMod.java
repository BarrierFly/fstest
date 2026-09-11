package fstest;

import carpet.CarpetServer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fengshui Tester (fstest) - mod entry point.
 *
 * A Carpet extension that tests redstone contraptions for directionality
 * (behaviour under rotation/mirror) and positionality (behaviour under
 * translation) by replaying accepted player operations inside an isolated
 * simulated space and diffing micro-timing style event streams against the
 * real world recording.
 */
public class FstestMod implements ModInitializer
{
	public static final String MOD_ID = "fstest";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize()
	{
		CarpetServer.manageExtension(new FstestExtension());
		LOGGER.info("[Fengshui Tester] extension registered");
	}
}
