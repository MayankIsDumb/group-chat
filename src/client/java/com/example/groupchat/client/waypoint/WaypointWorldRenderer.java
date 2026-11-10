package com.example.groupchat.client.waypoint;

import com.example.groupchat.client.waypoint.WaypointManager.Waypoint;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Draws waypoints in the world itself: a coloured marker column at the exact spot plus a floating
 * name tag with the distance. Replaces the old corner-of-the-screen list.
 */
public final class WaypointWorldRenderer {
	/** How tall the light column is, so a waypoint can be spotted from far away. */
	private static final double BEAM_HEIGHT = 64.0D;

	private WaypointWorldRenderer() {
	}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(WaypointWorldRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			return;
		}
		List<Waypoint> waypoints = WaypointManager.INSTANCE.visible();
		if (waypoints.isEmpty()) {
			return;
		}
		String dimension = client.level.dimension().identifier().toString();
		Vec3 camera = client.gameRenderer.getMainCamera().position();
		MultiBufferSource consumers = context.consumers();
		PoseStack matrices = context.matrices();
		VertexConsumer lines = null;

		for (Waypoint waypoint : waypoints) {
			if (!waypoint.dimension().equals(dimension)) {
				continue;
			}
			if (lines == null) {
				lines = consumers.getBuffer(RenderTypes.lines());
			}
			double dx = waypoint.x() - camera.x;
			double dy = waypoint.y() - camera.y;
			double dz = waypoint.z() - camera.z;
			if (dx * dx + dy * dy + dz * dz > 512.0D * 512.0D) {
				continue;
			}
			int color = waypoint.color();
			// marker cube around the spot
			ShapeRenderer.renderShape(matrices, lines, Shapes.box(-0.4D, 0.0D, -0.4D, 0.4D, 1.8D, 0.4D),
					dx, dy, dz, color, 2.5F);
			// light column so it is visible across the landscape
			ShapeRenderer.renderShape(matrices, lines, Shapes.box(-0.1D, 1.8D, -0.1D, 0.1D, BEAM_HEIGHT, 0.1D),
					dx, dy, dz, (color & 0x00FFFFFF) | 0x55000000, 1.5F);

			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance < 96.0D) {
				Component label = Component.literal(waypoint.name() + "  " + Math.round(distance) + "m");
				matrices.pushPose();
				matrices.translate(dx, dy + 2.4D, dz);
				context.commandQueue().submitNameTag(matrices, null, 0, label, true, 0xF000F0,
						distance * distance, context.worldState().cameraRenderState);
				matrices.popPose();
			}
		}
	}
}
