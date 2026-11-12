package com.example.groupchat.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.example.groupchat.Constants;
import com.example.groupchat.client.media.ImageUtil;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * A tiny built-in image browser (no native file dialog, no AWT window) for the two folders players
 * actually use: {@code .minecraft/screenshots} and {@code config/groupchat/images}.
 */
public class ImagePickerScreen extends Screen {
	private static final int CELL_W = 78;
	private static final int CELL_H = 86;
	private static final int THUMB = 64;

	private record Click(int x, int y, int width, int height, Runnable action) {
	}

	private final Screen parent;
	private final Consumer<Path> onPick;
	private final List<Click> clicks = new ArrayList<>();
	private final List<Path> folders = new ArrayList<>();
	private final List<Path> images = new ArrayList<>();
	private final Map<Path, Identifier> thumbnails = new LinkedHashMap<>();
	private final Map<Path, int[]> thumbSizes = new LinkedHashMap<>();
	private final Map<Path, String> thumbErrors = new LinkedHashMap<>();
	private Path directory;
	private int serial;
	private EditBox pathBox;
	private String status;

	public ImagePickerScreen(Screen parent, Consumer<Path> onPick) {
		super(Component.literal("Send image"));
		this.parent = parent;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		if (this.directory == null) {
			Path shots = this.screenshotsDir();
			this.directory = this.hasImages(shots) || !this.hasImages(this.imagesDir()) ? shots : this.imagesDir();
		}
		this.pathBox = new EditBox(this.font, 10, 10, 100, 16, Component.literal("Folder"));
		this.pathBox.setMaxLength(260);
		this.pathBox.setHint(Component.literal("paste a folder or image path, then press Enter"));
		this.addRenderableWidget(this.pathBox);
		this.refresh();
	}

	private boolean hasImages(Path dir) {
		if (dir == null || !Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> stream = Files.list(dir)) {
			return stream.anyMatch(ImageUtil::looksLikeImage);
		} catch (IOException exception) {
			return false;
		}
	}

	private Path imagesDir() {
		Path dir = this.minecraft.gameDirectory.toPath().resolve("config").resolve(Constants.MOD_ID).resolve("images");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
		return dir;
	}

	private Path screenshotsDir() {
		return this.minecraft.gameDirectory.toPath().resolve("screenshots");
	}

	private void refresh() {
		this.clicks.clear();
		if (this.pathBox != null && this.directory != null) {
			this.pathBox.setValue(this.directory.toString());
		}
		this.folders.clear();
		this.images.clear();
		if (this.directory == null || !Files.isDirectory(this.directory)) {
			return;
		}
		try (Stream<Path> stream = Files.list(this.directory)) {
			List<Path> all = stream.toList();
			for (Path path : all) {
				if (Files.isDirectory(path)) {
					this.folders.add(path);
				} else if (ImageUtil.looksLikeImage(path)) {
					this.images.add(path);
				}
			}
		} catch (IOException ignored) {
		}
		this.folders.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()));
		this.images.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xD008080C);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = 10;
		int y = 10;
		int width = this.width - 20;
		int height = this.height - 20;
		UiKit.panel(graphics, x, y, width, height);

		graphics.text(this.font, "Send an image", x + 10, y + 9, UiKit.TEXT, false);
		if (this.pathBox != null) {
			this.pathBox.setX(x + 10);
			this.pathBox.setY(y + 21);
			this.pathBox.setWidth(width - 12);
		}

		int buttonY = y + 6;
		UiKit.button(graphics, this.font, x + width - 200, buttonY, 60, 16, Component.literal("Shots"), mouseX, mouseY, true);
		this.addClick(x + width - 200, buttonY, 60, 16, () -> this.navigate(this.screenshotsDir()));
		UiKit.button(graphics, this.font, x + width - 136, buttonY, 60, 16, Component.literal("Images"), mouseX, mouseY, false);
		this.addClick(x + width - 136, buttonY, 60, 16, () -> this.navigate(this.imagesDir()));
		UiKit.button(graphics, this.font, x + width - 72, buttonY, 62, 16, Component.literal("Cancel"), mouseX, mouseY, false);
		this.addClick(x + width - 72, buttonY, 62, 16, () -> this.minecraft.setScreenAndShow(this.parent));

		int gridX = x + 8;
		int gridY = y + 46;
		int columns = Math.max(1, (width - 16) / CELL_W);
		int cellIndex = 0;

		Path up = this.directory == null ? null : this.directory.getParent();
		if (up != null) {
			this.drawCell(graphics, gridX, gridY, "..", null, null, mouseX, mouseY, () -> this.navigate(up));
			cellIndex++;
		}
		for (Path folder : this.folders) {
			int column = cellIndex % columns;
			int row = cellIndex / columns;
			int cellX = gridX + column * CELL_W;
			int cellY = gridY + row * CELL_H;
			if (cellY + CELL_H > y + height - 10) {
				break;
			}
			this.drawCell(graphics, cellX, cellY, folder.getFileName().toString(), null, null, mouseX, mouseY,
					() -> this.navigate(folder));
			cellIndex++;
		}
		for (Path image : this.images) {
			int column = cellIndex % columns;
			int row = cellIndex / columns;
			int cellX = gridX + column * CELL_W;
			int cellY = gridY + row * CELL_H;
			if (cellY + CELL_H > y + height - 10) {
				break;
			}
			this.ensureThumbnail(image);
			Identifier texture = this.thumbnails.get(image);
			String error = this.thumbErrors.get(image);
			this.drawCell(graphics, cellX, cellY, image.getFileName().toString(), texture, this.thumbSizes.get(image),
					mouseX, mouseY, () -> {
				this.onPick.accept(image);
				this.minecraft.setScreenAndShow(this.parent);
			});
			if (error != null) {
				graphics.text(this.font, "!", cellX + 4, cellY + 4, UiKit.BAD, false);
			}
			cellIndex++;
		}

		if (cellIndex == 0 || (this.folders.isEmpty() && this.images.isEmpty())) {
			graphics.text(this.font, "No images here. Drop PNG/JPG files into", x + 12, gridY + 4, UiKit.TEXT_FAINT, false);
			graphics.text(this.font, this.imagesDir().toString(), x + 12, gridY + 16, UiKit.TEXT_DIM, false);
			graphics.text(this.font, "or use the Shots button for screenshots.", x + 12, gridY + 28, UiKit.TEXT_FAINT, false);
		}

		graphics.text(this.font, this.status == null
						? "Tip: paste a folder above, or drag and drop an image file onto the game window."
						: this.status,
				x + 10, y + height - 14, this.status == null ? UiKit.TEXT_FAINT : UiKit.BAD, false);
	}

	private void drawCell(GuiGraphicsExtractor graphics, int x, int y, String label, Identifier texture, int[] textureSize,
			int mouseX, int mouseY, Runnable action) {
		boolean hover = UiKit.hovered(x, y, CELL_W - 4, CELL_H - 4, mouseX, mouseY);
		UiKit.fill(graphics, x, y, CELL_W - 4, CELL_H - 4, hover ? 0x40FFFFFF : 0x22000000);
		UiKit.outline(graphics, x, y, CELL_W - 4, CELL_H - 4, hover ? UiKit.ACCENT : UiKit.BORDER);
		if (texture != null && textureSize != null) {
			UiKit.blitTexture(graphics, texture, x + 4, y + 4, THUMB, THUMB, textureSize[0], textureSize[1]);
		} else {
			UiKit.fill(graphics, x + 4, y + 4, THUMB, THUMB, 0x30000000);
			graphics.centeredText(this.font, "folder", x + 4 + THUMB / 2, y + 4 + THUMB / 2 - 4, UiKit.TEXT_DIM);
		}
		graphics.text(this.font, UiKit.trim(this.font, label, CELL_W - 10), x + 4, y + THUMB + 8, UiKit.TEXT_DIM, false);
		this.addClick(x, y, CELL_W - 4, CELL_H - 4, action);
	}

	private void ensureThumbnail(Path image) {
		if (this.thumbnails.containsKey(image) || this.thumbErrors.containsKey(image)) {
			return;
		}
		this.thumbnails.put(image, null);
		Util.backgroundExecutor().execute(() -> {
			try {
				byte[] png = ImageUtil.thumbnail(image, THUMB);
				this.minecraft.execute(() -> {
					try {
						NativeImage nativeImage = NativeImage.read(png);
						Identifier location = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "picker/thumb_" + (this.serial++));
						this.minecraft.getTextureManager().register(location,
								new DynamicTexture(() -> "groupchat picker thumbnail", nativeImage));
						this.thumbnails.put(image, location);
						this.thumbSizes.put(image, new int[]{nativeImage.getWidth(), nativeImage.getHeight()});
					} catch (IOException exception) {
						this.thumbErrors.put(image, exception.getMessage());
						this.thumbnails.remove(image);
					}
				});
			} catch (Exception exception) {
				this.minecraft.execute(() -> {
					this.thumbnails.remove(image);
					this.thumbErrors.put(image, exception.getMessage());
				});
			}
		});
	}

	/** Applies whatever is typed in the path box: a folder to browse, or an image to send directly. */
	private void applyTypedPath() {
		if (this.pathBox == null) {
			return;
		}
		String raw = this.pathBox.getValue().trim();
		if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
			raw = raw.substring(1, raw.length() - 1);
		}
		if (raw.isEmpty()) {
			return;
		}
		try {
			Path target = Path.of(raw);
			if (Files.isRegularFile(target)) {
				if (ImageUtil.looksLikeImage(target)) {
					this.onPick.accept(target);
					this.minecraft.setScreenAndShow(this.parent);
				} else {
					this.status = "That file is not an image";
				}
				return;
			}
			if (Files.isDirectory(target)) {
				this.status = null;
				this.navigate(target);
				return;
			}
			this.status = "No such folder or file";
		} catch (RuntimeException exception) {
			this.status = "Invalid path";
		}
	}

	private void navigate(Path target) {
		this.directory = target;
		this.refresh();
	}

	private void addClick(int x, int y, int width, int height, Runnable action) {
		this.clicks.add(new Click(x, y, width, height, action));
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
		InputConstants.Key pressedKey = InputConstants.getKey(event);
		if (pressedKey.getName().equals("key.keyboard.enter") || pressedKey.getName().equals("key.keyboard.keypad.enter")) {
			if (this.pathBox != null && this.pathBox.isFocused()) {
				this.applyTypedPath();
				return true;
			}
		}
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

	@Override
	public void removed() {
		for (Identifier location : this.thumbnails.values()) {
			if (location != null) {
				this.minecraft.getTextureManager().release(location);
			}
		}
		this.thumbnails.clear();
	}
}
