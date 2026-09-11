package fstest.config;

/**
 * Offset sampling strategy for positionality tests ({@code /fstest pstrategy}).
 */
public enum PStrategy
{
	/** Uniform random XYZ translation (default). */
	UNIFORM,
	/** Offsets snapped to multiples of 16 plus a small jitter, probing chunk border / light section sensitivity. */
	CHUNK_BORDER,
	/** v2 planned strategy: intercept positional hash inputs instead of moving geometry. Not implemented in v1. */
	HASH_DELTA
}
