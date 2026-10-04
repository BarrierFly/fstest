package fstest.config;

/**
 * Test mode selected by {@code /fstest mode}.
 *
 * <p>The three test modes are alternatives: {@link #DIRECTIONALITY} runs the
 * rotation/mirror set, {@link #POSITIONALITY} the translation set, and
 * {@link #BOTH} (the {@code dp}/{@code pd} aliases) runs the combined set that
 * draws a random symmetry and a random offset per run. {@link #BOTH} does not
 * run the other two sets on top of it.
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
}
