package com.example.groupchat.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.example.groupchat.client.media.StickerAssets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Grid of the bundled sticker pack.
 */
public class StickerPickerScreen extends Screen {
	private static final int CELL = 74;

	private record Click(int x, int y, int width, int height, Runnable action) {
	}

	private final Screen parent;
	private final Consumer<String> onPick;
	private final List<Click> clicks = new ArrayList<>();

	public StickerPickerScreen(Screen parent, Consumer<String> onPick) {
		super(Component.literal("Stickers"));
		this.parent = parent;
		this.onPick = onPick;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xD008080C);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.clicks.clear();
		int columns = 5;
		int width = columns * CELL + 20;
		int rows = (StickerAssets.IDS.size() + columns - 1) / columns;
		int height = rows * CELL + 52;
		int x = (this.width - width) / 2;
		int y = (this.height - height) / 2;
		UiKit.panel(graphics, x, y, width, height);
		graphics.text(this.font, "Stickers", x + 10, y + 9, UiKit.TEXT, false);

		int index = 0;
		for (String id : StickerAssets.IDS) {
			int column = index % columns;
			int row = index / columns;
			int cellX = x + 10 + column * CELL;
			int cellY = y + 26 + row * CELL;
			boolean hover = UiKit.hovered(cellX, cellY, CELL - 6, CELL - 6, mouseX, mouseY);
			UiKit.fill(graphics, cellX, cellY, CELL - 6, CELL - 6, hover ? 0x50FFFFFF : 0x22000000);
			UiKit.outline(graphics, cellX, cellY, CELL - 6, CELL - 6, hover ? UiKit.ACCENT : UiKit.BORDER);
			UiKit.blitTexture(graphics, StickerAssets.texture(id), cellX + 3, cellY + 3, 62, 62, 128, 128);
			this.clicks.add(new Click(cellX, cellY, CELL - 6, CELL - 6, () -> {
				this.onPick.accept(id);
				this.minecraft.setScreenAndShow(this.parent);
			}));
			index++;
		}

		graphics.text(this.font, "Want your own? Send any PNG with the Img button.",
				x + 10, y + height - 14, UiKit.TEXT_FAINT, false);
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
		if (event.key() == InputConstants.KEY_ESCAPE) {
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
