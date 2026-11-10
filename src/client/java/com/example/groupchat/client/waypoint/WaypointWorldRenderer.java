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
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Draws waypoints in the world itself: a coloured marker column at the exact spot plus a floating
 * name tag with the distance. Replaces the old corner-of-the-screen list.
 */
public final class WaypointWorldRenderer {
	/** How tall the beacon beam is, so a waypoint can be spotted from far away. */
	private static final int BEAM_HEIGHT = 320;
	/** Beam cross-section, slightly thicker than a real beacon so it reads as a waypoint. */
	private static final float BEAM_CORE_RADIUS = 0.6F;
	private static final float BEAM_GLOW_RADIUS = 0.95F;

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
			// foot marker so it is obvious where to stand
			ShapeRenderer.renderShape(matrices, lines, Shapes.box(-0.5D, 0.0D, -0.5D, 0.5D, 1.5D, 0.5D),
					dx, dy, dz, color, 2.5F);
			// full beacon beam (same renderer/vanilla texture the beacon block uses)
			matrices.pushPose();
			matrices.translate(dx - 0.5D, dy, dz - 0.5D);
			BeaconRenderer.submitBeaconBeam(matrices, context.commandQueue(), BeaconRenderer.BEAM_LOCATION,
					1.0F, (float) client.level.getGameTime() + client.getDeltaTracker().getGameTimeDeltaPartialTick(false),
					0, BEAM_HEIGHT, color, BEAM_CORE_RADIUS, BEAM_GLOW_RADIUS);
			matrices.popPose();

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
