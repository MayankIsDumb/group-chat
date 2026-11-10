package com.example.groupchat.client.waypoint;

import com.example.groupchat.client.ui.UiKit;
import com.example.groupchat.client.waypoint.WaypointManager.Waypoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Compact waypoint list in the top-right corner: colour, name, distance and compass direction to
 * each shared spot that is in the player's current dimension.
 */
public final class WaypointHud implements HudElement {
	private static final int MAX_ENTRIES = 5;

	@Override
	public void render(GuiGraphics graphics, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null || client.options.hideGui) {
			return;
		}
		List<Waypoint> waypoints = new ArrayList<>(WaypointManager.INSTANCE.visible());
		if (waypoints.isEmpty()) {
			return;
		}
		String dimension = client.level.dimension().identifier().toString();
		waypoints.removeIf(waypoint -> !waypoint.dimension().equals(dimension));
		waypoints.sort(Comparator.comparingDouble(waypoint -> waypoint.location().distanceFrom(client)));
		if (waypoints.isEmpty()) {
			return;
		}
		int shown = Math.min(MAX_ENTRIES, waypoints.size());
		int width = 0;
		for (int i = 0; i < shown; i++) {
			width = Math.max(width, this.lineWidth(client, waypoints.get(i)));
		}
		int x = graphics.guiWidth() - width - 8;
		int y = 6;
		for (int i = 0; i < shown; i++) {
			this.drawEntry(graphics, client, waypoints.get(i), x, y, width);
			y += 11;
		}
		if (waypoints.size() > shown) {
			graphics.drawString(client.font, "+" + (waypoints.size() - shown) + " more (B)", x, y, UiKit.TEXT_FAINT, false);
		}
	}

	private int lineWidth(Minecraft client, Waypoint waypoint) {
		double distance = waypoint.location().distanceFrom(client);
		String text = waypoint.name() + "  " + formatDistance(distance) + " " + compass(client, waypoint);
		return client.font.width(text) + 14;
	}

	private void drawEntry(GuiGraphics graphics, Minecraft client, Waypoint waypoint, int x, int y, int width) {
		double distance = waypoint.location().distanceFrom(client);
		boolean reached = distance >= 0 && distance < 8.0D;
		int color = reached ? UiKit.GOOD : waypoint.color();
		UiKit.fill(graphics, x - 2, y - 1, width + 4, 10, 0x70000000);
		UiKit.fill(graphics, x, y + 2, 4, 4, color);
		String name = UiKit.trim(client.font, waypoint.name(), width - 66);
		graphics.drawString(client.font, name, x + 7, y, UiKit.TEXT, false);
		String tail = formatDistance(distance) + " " + compass(client, waypoint);
		graphics.drawString(client.font, tail, x + width - client.font.width(tail), y, reached ? UiKit.GOOD : UiKit.TEXT_DIM, false);
	}

	private static String formatDistance(double distance) {
		if (distance < 0) {
			return "--";
		}
		if (distance >= 1000) {
			return String.format("%.1fkm", distance / 1000.0D);
		}
		return Math.round(distance) + "m";
	}

	/** Eight-way compass letter from the player to the waypoint. */
	private static String compass(Minecraft client, Waypoint waypoint) {
		if (client.player == null) {
			return "";
		}
		double dx = waypoint.x() - client.player.getX();
		double dz = waypoint.z() - client.player.getZ();
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		int index = (int) Math.round(((angle + 360.0D) % 360.0D) / 45.0D) % 8;
		return names[index];
	}
}
