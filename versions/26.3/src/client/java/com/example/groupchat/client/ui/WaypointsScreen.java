package com.example.groupchat.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.example.groupchat.client.waypoint.WaypointManager;
import com.example.groupchat.client.waypoint.WaypointManager.Waypoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Waypoint list: shows everything collected from shared coordinates, with distance, dimension and
 * per-waypoint visibility. Opened with {@code B}.
 */
public class WaypointsScreen extends Screen {
	private record Click(int x, int y, int width, int height, Runnable action) {
	}

	private final Screen parent;
	private final List<Click> clicks = new ArrayList<>();
	private String status;

	public WaypointsScreen(Screen parent) {
		super(Component.literal("Waypoints"));
		this.parent = parent;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xC808080C);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.clicks.clear();
		int x = 8;
		int y = 8;
		int width = this.width - 16;
		int height = this.height - 16;
		UiKit.panel(graphics, x, y, width, height);
		graphics.text(this.font, "Waypoints", x + 10, y + 9, UiKit.TEXT, false);
		graphics.text(this.font, "Click a row to show/hide it · X deletes", x + 10, y + 21, UiKit.TEXT_FAINT, false);

		List<Waypoint> waypoints = new ArrayList<>(WaypointManager.INSTANCE.all());
		if (this.minecraft != null && this.minecraft.player != null) {
			waypoints.sort(Comparator.comparingDouble(waypoint -> {
				double distance = waypoint.location().distanceFrom(this.minecraft);
				return distance < 0 ? Double.MAX_VALUE : distance;
			}));
		}

		int rowY = y + 34;
		if (waypoints.isEmpty()) {
			graphics.text(this.font, "Nothing yet. Share coordinates in a group chat, then click the",
					x + 12, rowY + 6, UiKit.TEXT_FAINT, false);
			graphics.text(this.font, "message to turn it into a waypoint.", x + 12, rowY + 18, UiKit.TEXT_FAINT, false);
		}
		for (Waypoint waypoint : waypoints) {
			if (rowY + 22 > y + height - 34) {
				break;
			}
			boolean hover = UiKit.hovered(x + 6, rowY, width - 12, 20, mouseX, mouseY);
			UiKit.fill(graphics, x + 6, rowY, width - 12, 20, hover ? 0x30FFFFFF : 0x22000000);
			int color = waypoint.visible() ? waypoint.color() : UiKit.TEXT_FAINT;
			UiKit.fill(graphics, x + 10, rowY + 7, 6, 6, color);
			String name = UiKit.trim(this.font, waypoint.name(), width - 240);
			graphics.text(this.font, name, x + 22, rowY + 6, waypoint.visible() ? UiKit.TEXT : UiKit.TEXT_DIM, false);
			String coords = waypoint.location().pretty() + "  " + waypoint.location().dimensionLabel();
			graphics.text(this.font, coords, x + 22 + this.font.width(name) + 10, rowY + 6, UiKit.TEXT_FAINT, false);
			String distance = "-";
			if (this.minecraft != null) {
				double meters = waypoint.location().distanceFrom(this.minecraft);
				distance = meters < 0 ? "other dim" : Math.round(meters) + " m";
			}
			graphics.text(this.font, distance, x + width - 14 - this.font.width(distance) - 20, rowY + 6,
					UiKit.TEXT_DIM, false);
			String id = waypoint.id();
			this.clicks.add(new Click(x + 6, rowY, width - 40, 20, () -> WaypointManager.INSTANCE.toggle(id)));
			boolean deleteHover = UiKit.hovered(x + width - 30, rowY + 2, 16, 16, mouseX, mouseY);
			UiKit.fill(graphics, x + width - 30, rowY + 2, 16, 16, deleteHover ? 0x60E06C75 : 0x22000000);
			graphics.centeredText(this.font, "x", x + width - 22, rowY + 6, deleteHover ? 0xFFFFFFFF : UiKit.BAD);
			this.clicks.add(new Click(x + width - 30, rowY + 2, 16, 16, () -> WaypointManager.INSTANCE.remove(id)));
			rowY += 22;
		}

		int buttonY = y + height - 26;
		UiKit.button(graphics, this.font, x + 10, buttonY, 88, 16, Component.literal("Add here"), mouseX, mouseY, true);
		this.clicks.add(new Click(x + 10, buttonY, 88, 16, () -> {
			Waypoint added = WaypointManager.INSTANCE.addHere(this.minecraft == null ? null : this.minecraft.player);
			this.status = added == null ? "Could not add a waypoint here" : "Added waypoint at your position";
		}));
		UiKit.button(graphics, this.font, x + 104, buttonY, 76, 16, Component.literal("Delete all"), mouseX, mouseY, false);
		this.clicks.add(new Click(x + 104, buttonY, 76, 16, () -> {
			WaypointManager.INSTANCE.clear();
			this.status = "All waypoints removed";
		}));
		UiKit.button(graphics, this.font, x + width - 70, buttonY, 60, 16, Component.literal("Done"), mouseX, mouseY, false);
		this.clicks.add(new Click(x + width - 70, buttonY, 60, 16, () -> this.minecraft.setScreenAndShow(this.parent)));

		if (this.status != null) {
			graphics.text(this.font, this.status, x + width - 14 - this.font.width(this.status), buttonY, UiKit.GOOD, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = this.clicks.size() - 1; i >= 0; i--) {
			Click click = this.clicks.get(i);
			if (UiKit.hovered(click.x(), click.y(), click.width(), click.height(), event.x(), event.y())) {
				click.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_B) {
			this.minecraft.setScreenAndShow(this.parent);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(this.parent);
	}
}
