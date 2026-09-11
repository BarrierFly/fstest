package fstest.transform;

import fstest.config.FstestConfig;
import fstest.config.PStrategy;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.core.BlockPos;

/**
 * Samples positional offsets for P / PD runs.
 *
 * Every produced offset keeps the transformed contraption inside coordinates
 * that can actually exist in the target dimension (horizontal world bounds,
 * vertical dimension range), as required by the spec.
 */
public final class OffsetSampler
{
	public static final int WORLD_H_BOUND = 30_000_000; // x,z in [-30M, 30M)

	private OffsetSampler()
	{
	}

	/**
	 * @param random  dedicated sampler RNG (never the world's shared random)
	 * @param dim     dimension type of the tested world (legal envelope)
	 * @param anchor  the real operation position
	 * @return offset to add to the anchor
	 */
	public static BlockPos sample(PStrategy strategy, RandomSource random, DimensionType dim, BlockPos anchor)
	{
		return switch (strategy)
		{
			case UNIFORM -> sampleUniform(random, dim, anchor);
			case CHUNK_BORDER -> sampleChunkBorder(random, dim, anchor);
			case HASH_DELTA -> BlockPos.ZERO; // v2 planned; not reachable in v1
		};
	}

	private static BlockPos sampleUniform(RandomSource random, DimensionType dim, BlockPos anchor)
	{
		int amp = FstestConfig.UNIFORM_H_AMPLITUDE;
		int dx = random.nextInt(-amp, amp + 1);
		int dz = random.nextInt(-amp, amp + 1);
		int dyMin = dim.minY() - anchor.getY();
		int dyMax = dim.minY() + dim.height() - 1 - anchor.getY();
		int dy = dyMax > dyMin ? random.nextInt(dyMin, dyMax + 1) : 0;
		return clampToWorld(new BlockPos(dx, dy, dz), dim, anchor);
	}

	private static BlockPos sampleChunkBorder(RandomSource random, DimensionType dim, BlockPos anchor)
	{
		// multiples of 16 plus a small jitter: probes chunk border / light section sensitivity
		int span = FstestConfig.UNIFORM_H_AMPLITUDE >> 4;
		int dx = random.nextInt(-span, span + 1) * 16 + jitter(random);
		int dz = random.nextInt(-span, span + 1) * 16 + jitter(random);
		int dyMin = dim.minY() - anchor.getY();
		int dyMax = dim.minY() + dim.height() - 1 - anchor.getY();
		int dy = random.nextInt(Math.max(dyMin, -15), Math.min(dyMax, 15) + 1);
		return clampToWorld(new BlockPos(dx, dy, dz), dim, anchor);
	}

	private static int jitter(RandomSource random)
	{
		return random.nextInt(-2, 3);
	}

	private static BlockPos clampToWorld(BlockPos offset, DimensionType dim, BlockPos anchor)
	{
		int x = anchor.getX() + offset.getX();
		int z = anchor.getZ() + offset.getZ();
		int y = anchor.getY() + offset.getY();
		x = Math.max(-WORLD_H_BOUND, Math.min(WORLD_H_BOUND - 1, x));
		z = Math.max(-WORLD_H_BOUND, Math.min(WORLD_H_BOUND - 1, z));
		y = Math.max(dim.minY(), Math.min(dim.minY() + dim.height() - 1, y));
		return new BlockPos(x - anchor.getX(), y - anchor.getY(), z - anchor.getZ());
	}
}
