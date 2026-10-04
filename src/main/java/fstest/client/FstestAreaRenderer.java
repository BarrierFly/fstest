package fstest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fstest.FstestMod;
import fstest.config.FstestConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * Draws the MTR selection areas in world space: every registered area as a
 * grey box, the one currently scoped as a green box with a faint translucent
 * fill so a large selection is still readable from a distance.
 *
 * <p>The data comes from {@link FstestConfig#selection()}, which publishes an
 * immutable copy of the area list on every change, so the render thread never
 * touches the server-owned maps. That copy is only reachable when a server runs
 * in this JVM: on a remote server nothing is drawn, since the selection is not
 * replicated to clients.
 */
public final class FstestAreaRenderer
{
	private static final float LINE_WIDTH = 2.0F;
	private static final int COLOR_INACTIVE = 0xFF9E9E9E;
	private static final int COLOR_ACTIVE = 0xFF39FF14;
	private static final int COLOR_ACTIVE_FILL = 0x2639FF14;
	/** One confirmation per session, so "I see nothing" can be told apart in the log. */
	private static boolean announced;

	private FstestAreaRenderer()
	{
	}

	public static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffers, Vec3 cameraPos)
	{
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive())
		{
			return;
		}
		FstestConfig.SelectionSnapshot selection = FstestConfig.INSTANCE.selection();
		// Announced before every early return below: "the renderer ran and saw
		// nothing" must be distinguishable from "the renderer never ran at all".
		if (!announced)
		{
			announced = true;
			FstestMod.LOGGER.info("[fstest] selection overlay renderer active; {} area(s) registered, scoped='{}'",
					selection.areas().size(), selection.scope() == null ? "-" : selection.scope());
		}
		if (selection.areas().isEmpty())
		{
			return;
		}
		String scoped = selection.scope();
		VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
		VertexConsumer filled = buffers.getBuffer(RenderTypes.debugFilledBox());
		for (Map.Entry<String, FstestConfig.Area> entry : selection.areas().entrySet())
		{
			FstestConfig.Area area = entry.getValue();
			boolean active = entry.getKey().equals(scoped);
			// an unscoped area the player cannot see would only add noise
			if (!active && !nearCamera(area, cameraPos))
			{
				continue;
			}
			// the box spans whole blocks: from the min corner's corner to the far
			// corner of the max block, so the outline encloses the selection exactly
			VoxelShape box = Shapes.box(0.0D, 0.0D, 0.0D,
					area.sideX() + 1.0D, area.sideY() + 1.0D, area.sideZ() + 1.0D);
			double x = area.pos1().getX() - cameraPos.x;
			double y = area.pos1().getY() - cameraPos.y;
			double z = area.pos1().getZ() - cameraPos.z;
			if (active)
			{
				ShapeRenderer.renderShape(poseStack, filled, box, x, y, z, COLOR_ACTIVE_FILL, LINE_WIDTH);
			}
			ShapeRenderer.renderShape(poseStack, lines, box, x, y, z,
					active ? COLOR_ACTIVE : COLOR_INACTIVE, LINE_WIDTH);
		}
	}

	/** Whether any part of the area is within rendering reach of the camera. */
	private static boolean nearCamera(FstestConfig.Area area, Vec3 cameraPos)
	{
		BlockPos min = area.pos1();
		BlockPos max = area.pos2();
		double cx = (min.getX() + max.getX() + 1) / 2.0D;
		double cy = (min.getY() + max.getY() + 1) / 2.0D;
		double cz = (min.getZ() + max.getZ() + 1) / 2.0D;
		double reach = Math.max(area.sideX(), Math.max(area.sideY(), area.sideZ()));
		double limit = 96.0D + reach;
		return cameraPos.distanceToSqr(cx, cy, cz) <= limit * limit;
	}
}