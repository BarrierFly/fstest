package fstest.config;

/**
 * Test mode selected by {@code /fstest mode}.
 */
public enum FstestMode
{
	NONE,
	DIRECTIONALITY,
	POSITIONALITY,
	BOTH;

	/** Parses user input including the aliases defined by the spec. */
	public static FstestMode parse(String input)
	{
		String s = input.trim().toLowerCase();
		return switch (s)
		{
			case "none" -> NONE;
			case "directionality", "directional", "direction", "d" -> DIRECTIONALITY;
			case "positionality", "positional", "position", "p" -> POSITIONALITY;
			case "both", "dp", "pd" -> BOTH;
			default -> null;
		};
	}

	public boolean testsDirectionality()
	{
		return this == DIRECTIONALITY || this == BOTH;
	}

	public boolean testsPositionality()
	{
		return this == POSITIONALITY || this == BOTH;
	}
}
