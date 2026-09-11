package fstest.transform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

/**
 * One orthogonal symmetry of the D (directionality) test set:
 * an optional mirror followed by a rotation, exactly matching the semantics
 * vanilla uses for structure placement ({@code state.mirror(m).rotate(r)},
 * positions via {@link StructureTemplate#transform}).
 */
public record Symmetry(Mirror mirror, Rotation rotation)
{
	public static final Symmetry IDENTITY = new Symmetry(Mirror.NONE, Rotation.NONE);

	/** The eight orthogonal symmetries: 4 rotations x with/without flip. */
	public static Symmetry[] fullSet()
	{
		return new Symmetry[]{
				new Symmetry(Mirror.NONE, Rotation.NONE),
				new Symmetry(Mirror.NONE, Rotation.CLOCKWISE_90),
				new Symmetry(Mirror.NONE, Rotation.CLOCKWISE_180),
				new Symmetry(Mirror.NONE, Rotation.COUNTERCLOCKWISE_90),
				new Symmetry(Mirror.LEFT_RIGHT, Rotation.NONE),
				new Symmetry(Mirror.LEFT_RIGHT, Rotation.CLOCKWISE_90),
				new Symmetry(Mirror.LEFT_RIGHT, Rotation.CLOCKWISE_180),
				new Symmetry(Mirror.LEFT_RIGHT, Rotation.COUNTERCLOCKWISE_90),
		};
	}

	/** Maps a position relative to the transform anchor. */
	public BlockPos applyToPos(BlockPos relative)
	{
		return StructureTemplate.transform(relative, this.mirror, this.rotation, BlockPos.ZERO);
	}

	/** Vanilla order: mirror first, then rotate. */
	public BlockState applyToState(BlockState state)
	{
		return state.mirror(this.mirror).rotate(this.rotation);
	}

	/** Forward direction transform (real space -> simulation space); null passes through. */
	public @Nullable Direction applyToDirection(@Nullable Direction direction)
	{
		if (direction == null)
		{
			return null;
		}
		return this.rotation.rotate(this.mirror.mirror(direction));
	}

	// ----------------------------------------------------------------------
	// Inverse mapping (simulation space -> real space), used to canonicalize
	// simulated events so every run diffs against reality in real coordinates
	// ----------------------------------------------------------------------

	/** Inverse of {@link #applyToPos}: un-rotate first, then un-mirror. */
	public BlockPos applyToPosInverse(BlockPos relative)
	{
		BlockPos unrotated = StructureTemplate.transform(relative, Mirror.NONE, inverseRotation(), BlockPos.ZERO);
		return StructureTemplate.transform(unrotated, this.mirror, Rotation.NONE, BlockPos.ZERO);
	}

	/** Inverse of {@link #applyToState}: un-rotate first, then un-mirror. */
	public BlockState applyToStateInverse(BlockState state)
	{
		return state.rotate(inverseRotation()).mirror(this.mirror);
	}

	/** Inverse of the direction transform (event fromDir fields); null passes through. */
	public @Nullable Direction applyToDirectionInverse(@Nullable Direction direction)
	{
		if (direction == null)
		{
			return null;
		}
		return this.mirror.mirror(inverseRotation().rotate(direction));
	}

	/** The forward transform is mirror-then-rotate, so its inverse rotation is the opposite quarter turn. */
	private Rotation inverseRotation()
	{
		return switch (this.rotation)
		{
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> this.rotation; // NONE and CLOCKWISE_180 are their own inverses
		};
	}

	public String label()
	{
		String rot = switch (this.rotation)
		{
			case NONE -> "0";
			case CLOCKWISE_90 -> "90cw";
			case CLOCKWISE_180 -> "180";
			case COUNTERCLOCKWISE_90 -> "90ccw";
		};
		if (this.mirror == Mirror.NONE)
		{
			return rot;
		}
		return switch (this.mirror)
		{
			case LEFT_RIGHT -> "flipNS+" + rot;
			case FRONT_BACK -> "flipEW+" + rot;
			default -> "mirror+" + rot;
		};
	}
}
