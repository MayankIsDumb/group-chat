package com.example.groupchat.client.waypoint;

import com.example.groupchat.client.waypoint.WaypointManager.Waypoint;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
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
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(WaypointWorldRenderer::render);
	}

	/** Bakes the label glyphs during the tick so the level render pass never uploads fonts. */
	public static void warmLabels() {
		Minecraft client = Minecraft.getInstance();
		if (client.font == null) {
			return;
		}
		for (Waypoint waypoint : WaypointManager.INSTANCE.visible()) {
			client.font.width(waypoint.name() + "  0123456789m");
		}
	}

	private static void render(LevelRenderContext context) {
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
		MultiBufferSource consumers = context.bufferSource();
		SubmitNodeCollector collector = context.submitNodeCollector();
		PoseStack matrices = context.poseStack();
		VertexConsumer lines = null;
		float tick = (float) client.level.getGameTime()
				+ client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		for (Waypoint waypoint : waypoints) {
			if (!waypoint.dimension().equals(dimension)) {
				continue;
			}
			double dx = waypoint.x() - camera.x;
			double dy = waypoint.y() - camera.y;
			double dz = waypoint.z() - camera.z;
			double distanceSq = dx * dx + dy * dy + dz * dz;
			if (distanceSq > 512.0D * 512.0D) {
				continue;
			}
			int color = waypoint.color();
			if (lines == null) {
				lines = consumers.getBuffer(RenderTypes.lines());
			}
			// foot marker so it is obvious where to stand
			ShapeRenderer.renderShape(matrices, lines, Shapes.box(-0.5D, 0.0D, -0.5D, 0.5D, 1.5D, 0.5D),
					dx, dy, dz, color, 2.5F);
			// full beacon beam (same renderer/vanilla texture the beacon block uses)
			matrices.pushPose();
			matrices.translate(dx - 0.5D, dy, dz - 0.5D);
			BeaconRenderer.submitBeaconBeam(matrices, collector, BeaconRenderer.BEAM_LOCATION,
					1.0F, tick, 0, BEAM_HEIGHT, color, BEAM_CORE_RADIUS, BEAM_GLOW_RADIUS);
			matrices.popPose();

			double distance = Math.sqrt(distanceSq);
			if (distance < 96.0D) {
				Component label = Component.literal(waypoint.name() + "  " + Math.round(distance) + "m");
				matrices.pushPose();
				matrices.translate(dx, dy + 2.4D, dz);
				collector.submitNameTag(matrices, Vec3.ZERO, 0, label, true, 0xF000F0, distanceSq,
						context.levelState().cameraRenderState);
				matrices.popPose();
			}
		}
	}
}
