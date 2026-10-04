package fstest.config;

/**
 * Simulation mode selected by {@code /fstest sim}: how the simulated space
 * behaves after replaying the captured operation. Orthogonal to the test mode
 * ({@link FstestMode}); exactly one simulation mode is active at a time.
 */
public enum FstestSimMode
{
	/**
	 * The original v1 behaviour: only the operation's synchronous window is
	 * simulated; scheduled ticks / block events / block entities are recorded
	 * but never executed.
	 */
	INSTANT,
	/**
	 * Timed mode (formerly "MTR"): after the operation replay, the simulated
	 * space executes {@code simTicks} game ticks (scheduled ticks, fluid
	 * ticks, block events, block entity ticking) inside a simulatica-style
	 * real ServerLevel. The real world is never advanced.
	 */
	TIMED;

	/** Parses user input including common aliases. */
	public static FstestSimMode parse(String input)
	{
		String s = input.trim().toLowerCase(java.util.Locale.ROOT);
		return switch (s)
		{
			case "instant", "i" -> INSTANT;
			case "timed", "t", "mtr" -> TIMED;
			default -> null;
		};
	}
}
