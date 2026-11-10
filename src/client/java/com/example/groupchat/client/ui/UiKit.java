package com.example.groupchat.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Small drawing helpers that give the hub its flat, dark "chat app" look using only vanilla fills.
 */
public final class UiKit {
	public static final int PANEL = 0xF214141B;
	public static final int PANEL_RAISED = 0xFF1C1C26;
	public static final int PANEL_SOFT = 0xB01E1E28;
	public static final int BORDER = 0xFF31313F;
	public static final int ACCENT = 0xFF8B7BFF;
	public static final int ACCENT_SOFT = 0x408B7BFF;
	public static final int ACCENT_DARK = 0xFF5B4FD6;
	public static final int TEXT = 0xFFF3F3F8;
	public static final int TEXT_DIM = 0xFF9C9CAA;
	public static final int TEXT_FAINT = 0xFF6E6E7C;
	public static final int GOOD = 0xFF54D18C;
	public static final int BAD = 0xFFE06C75;
	public static final int MINE = 0xFF322A5A;

	private UiKit() {
	}

	public static void fill(GuiGraphics graphics, int x, int y, int width, int height, int color) {
		graphics.fill(x, y, x + width, y + height, color);
	}

	public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
		fill(graphics, x, y, width, height, PANEL);
		outline(graphics, x, y, width, height, BORDER);
	}

	public static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
		graphics.fill(x, y, x + width, y + 1, color);
		graphics.fill(x, y + height - 1, x + width, y + height, color);
		graphics.fill(x, y, x + 1, y + height, color);
		graphics.fill(x + width - 1, y, x + width, y + height, color);
	}

	public static boolean hovered(int x, int y, int width, int height, double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	/** Draws a small button and returns whether the mouse is over it. */
	public static boolean button(GuiGraphics graphics, Font font, int x, int y, int width, int height,
			Component label, int mouseX, int mouseY, boolean primary) {
		boolean hover = hovered(x, y, width, height, mouseX, mouseY);
		int background;
		if (primary) {
			background = hover ? ACCENT : ACCENT_DARK;
		} else {
			background = hover ? 0xFF2C2C3A : PANEL_RAISED;
		}
		fill(graphics, x, y, width, height, background);
		outline(graphics, x, y, width, height, primary ? ACCENT : BORDER);
		int color = primary ? 0xFFFFFFFF : (hover ? TEXT : TEXT_DIM);
		graphics.drawCenteredString(font, label, x + width / 2, y + (height - 8) / 2, color);
		return hover;
	}

	/** Draws a button that is only enabled when {@code enabled}. */
	public static boolean button(GuiGraphics graphics, Font font, int x, int y, int width, int height,
			Component label, int mouseX, int mouseY, boolean primary, boolean enabled) {
		if (enabled) {
			return button(graphics, font, x, y, width, height, label, mouseX, mouseY, primary);
		}
		fill(graphics, x, y, width, height, 0xFF1A1A22);
		outline(graphics, x, y, width, height, 0xFF262632);
		graphics.drawCenteredString(font, label, x + width / 2, y + (height - 8) / 2, TEXT_FAINT);
		return false;
	}

	/**
	 * Draws a whole standalone texture scaled into the given rectangle.
	 *
	 * <p>The shorthand {@code GuiGraphics.blit} overloads assume one texture pixel per screen pixel,
	 * so a scaled draw has to pass the source size explicitly.
	 */
	public static void blitTexture(GuiGraphics graphics, net.minecraft.resources.Identifier texture,
			int x, int y, int width, int height, int textureWidth, int textureHeight) {
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F,
				width, height, textureWidth, textureHeight, textureWidth, textureHeight);
	}

	public static String trim(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int budget = maxWidth - font.width(ellipsis);
		StringBuilder builder = new StringBuilder();
		for (char character : text.toCharArray()) {
			if (font.width(builder.toString() + character) > budget) {
				break;
			}
			builder.append(character);
		}
		return builder + ellipsis;
	}

	public static void chip(GuiGraphics graphics, Font font, int x, int y, String text, int color) {
		int width = font.width(text) + 10;
		fill(graphics, x, y, width, 13, 0x33000000);
		outline(graphics, x, y, width, 13, 0x33FFFFFF);
		graphics.drawString(font, text, x + 5, y + 3, color, false);
	}

	public static String formatTime(long millis) {
		long minutes = (millis / 60_000L) % 60;
		long hours = (millis / 3_600_000L) % 24;
		return String.format("%02d:%02d", hours, minutes);
	}
}
