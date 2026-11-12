package com.example.groupchat.client.waypoint;

import com.example.groupchat.client.ui.UiKit;
import com.example.groupchat.client.waypoint.WaypointManager.Waypoint;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * Distance labels that sit in the direction of each waypoint.
 *
 * <p>On screen the label is drawn next to the spot itself; when the waypoint is off-screen or behind
 * the player the label is pushed to the screen edge in that direction, so it always reads as
 * "waypoint is that way, N metres".
 */
public final class WaypointOverlay implements HudElement {
	private static final int EDGE_INSET = 26;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null || client.options.hideGui) {
			return;
		}
		List<Waypoint> waypoints = WaypointManager.INSTANCE.visible();
		if (waypoints.isEmpty()) {
			return;
		}
		String dimension = client.level.dimension().identifier().toString();
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		float tanHalfFov = (float) Math.tan(Math.toRadians(client.options.fov().get()) / 2.0D);
		float aspect = (float) width / (float) height;
		float tanHalfFovX = tanHalfFov * aspect;

		for (Waypoint waypoint : waypoints) {
			if (!waypoint.dimension().equals(dimension)) {
				continue;
			}
			double dx = waypoint.x() - client.player.getX();
			double dy = waypoint.y() + 1.0D - client.player.getEyeY();
			double dz = waypoint.z() - client.player.getZ();
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance > 2000.0D) {
				continue;
			}
			// MC yaw: 0 = +Z, 90 = -X. pitch: negative = up.
			double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
			double targetPitch = Math.toDegrees(-Math.atan2(dy, Math.max(0.01D, horizontal)));
			double deltaYaw = Mth.wrapDegrees(targetYaw - client.player.getYRot());
			double deltaPitch = Mth.wrapDegrees(targetPitch - client.player.getXRot());

			float ndcX = (float) (Math.tan(Math.toRadians(deltaYaw)) / tanHalfFovX);
			float ndcY = (float) (Math.tan(Math.toRadians(deltaPitch)) / tanHalfFov);
			boolean onScreen = Math.abs(deltaYaw) < 80.0D && Math.abs(deltaPitch) < 80.0D
					&& ndcX > -0.95F && ndcX < 0.95F && ndcY > -0.9F && ndcY < 0.9F;
			if (!onScreen) {
				// push the label to the edge in the direction of the waypoint
				if (Math.abs(deltaYaw) >= 80.0D) {
					ndcX = deltaYaw > 0 ? 1.15F : -1.15F;
				}
				if (Math.abs(deltaPitch) >= 80.0D) {
					ndcY = deltaPitch > 0 ? 1.15F : -1.15F;
				}
				float scale = Math.max(Math.abs(ndcX) / 0.92F, Math.abs(ndcY) / 0.88F);
				if (scale > 1.0F) {
					ndcX /= scale;
					ndcY /= scale;
				}
			}

			int screenX = Math.round((ndcX * 0.5F + 0.5F) * width);
			int screenY = Math.round((0.5F - ndcY * 0.5F) * height);
			int labelX = Mth.clamp(screenX, EDGE_INSET, width - EDGE_INSET);
			int labelY = Mth.clamp(screenY, EDGE_INSET, height - EDGE_INSET);
			String distanceText = formatDistance(distance);
			String text = onScreen ? waypoint.name() + "  " + distanceText : distanceText;

			if (onScreen) {
				// small diamond right at the spot, label underneath
				int color = waypoint.color();
				graphics.fill(labelX - 2, labelY - 2, labelX + 2, labelY + 2, color);
				graphics.fill(labelX - 1, labelY - 5, labelX + 1, labelY - 3, color);
				UiKit.tag(graphics, client.font, text, labelX, labelY + 10, color);
			} else {
				int color = waypoint.color();
				this.drawEdgeArrow(graphics, labelX, labelY, ndcX, ndcY, color);
				UiKit.tag(graphics, client.font, text, labelX, labelY, color);
			}
		}
	}

	/** Small triangle pointing outwards, drawn with plain fills. */
	private void drawEdgeArrow(GuiGraphicsExtractor graphics, int x, int y, float ndcX, float ndcY, int color) {
		boolean horizontal = Math.abs(ndcX) * 1.2F >= Math.abs(ndcY);
		if (horizontal) {
			int direction = ndcX >= 0 ? 1 : -1;
			for (int step = 0; step < 4; step++) {
				int offset = direction * (4 + step);
				int half = 4 - step;
				graphics.fill(x + offset, y - half, x + offset + direction, y + half, color);
			}
		} else {
			int direction = ndcY >= 0 ? -1 : 1;
			for (int step = 0; step < 4; step++) {
				int offset = direction * (4 + step);
				int half = 4 - step;
				graphics.fill(x - half, y + offset, x + half, y + offset + direction, color);
			}
		}
	}

	private static String formatDistance(double distance) {
		if (distance >= 1000.0D) {
			return String.format("%.1f km", distance / 1000.0D);
		}
		return Math.round(distance) + " m";
	}
}
