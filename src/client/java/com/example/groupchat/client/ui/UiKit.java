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

	/**
	 * Draws one region of a texture scaled into the given rectangle.
	 *
	 * <p>This is what an animated GIF needs: all frames live in one sheet and the source rectangle
	 * picks the frame that should be on screen.
	 */
	public static void blitTextureRegion(GuiGraphics graphics, net.minecraft.resources.Identifier texture,
			int x, int y, int width, int height,
			int sourceX, int sourceY, int sourceWidth, int sourceHeight,
			int textureWidth, int textureHeight) {
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture,
				x, y, (float) sourceX, (float) sourceY,
				width, height, sourceWidth, sourceHeight, textureWidth, textureHeight);
	}

	/**
	 * A check mark, drawn from pixels so it never depends on the font having the glyph.
	 *
	 * @param size the height of the mark in pixels
	 */
	public static void check(GuiGraphics graphics, int x, int y, int size, int color) {
		line(graphics, x, y + size / 2, x + size / 3, y + size - 1, color);
		line(graphics, x + size / 3, y + size - 1, x + size - 1, y, color);
	}

	/** A cross, the counterpart of {@link #check}. */
	public static void cross(GuiGraphics graphics, int x, int y, int size, int color) {
		line(graphics, x, y, x + size - 1, y + size - 1, color);
		line(graphics, x + size - 1, y, x, y + size - 1, color);
	}

	/**
	 * A row of pips, where {@code filled} of them are lit. Used to show a setting with more than
	 * two states without writing the states out.
	 */
	public static void pips(GuiGraphics graphics, int x, int y, int filled, int total, int color) {
		for (int i = 0; i < total; i++) {
			int pipX = x + i * 6;
			if (i < filled) {
				fill(graphics, pipX, y, 4, 4, color);
			} else {
				outline(graphics, pipX, y, 4, 4, color);
			}
		}
	}

	/** Draws a one pixel wide line. */
	private static void line(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
		int dx = Math.abs(x1 - x0);
		int sx = x0 < x1 ? 1 : -1;
		int dy = -Math.abs(y1 - y0);
		int sy = y0 < y1 ? 1 : -1;
		int error = dx + dy;
		while (true) {
			fill(graphics, x0, y0, 1, 1, color);
			if (x0 == x1 && y0 == y1) {
				return;
			}
			int doubled = 2 * error;
			if (doubled >= dy) {
				error += dy;
				x0 += sx;
			}
			if (doubled <= dx) {
				error += dx;
				y0 += sy;
			}
		}
	}

	/** Centred text with a dark rounded-ish backdrop, used for waypoint labels. */
	public static void tag(GuiGraphics graphics, Font font, String text, int centerX, int y, int accent) {
		int width = font.width(text) + 8;
		int x = centerX - width / 2;
		graphics.fill(x, y - 1, x + width, y + 9, 0x90000000);
		graphics.fill(x, y - 1, x + width, y, (accent & 0x00FFFFFF) | 0x60000000);
		graphics.drawString(font, text, x + 4, y, 0xFFFFFFFF, false);
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

	/**
	 * Splits text into lines that each fit into {@code maxWidth}.
	 *
	 * <p>Words longer than the limit are kept whole, so a long word is never cut in half.
	 */
	public static java.util.List<String> wrap(Font font, String text, int maxWidth) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+")) {
			if (word.isEmpty()) {
				continue;
			}
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (font.width(candidate) <= maxWidth) {
				line = new StringBuilder(candidate);
				continue;
			}
			if (!line.isEmpty()) {
				lines.add(line.toString());
			}
			line = new StringBuilder(word);
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
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
