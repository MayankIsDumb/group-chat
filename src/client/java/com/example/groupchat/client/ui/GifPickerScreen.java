package com.example.groupchat.client.ui;

import com.example.groupchat.Constants;
import com.example.groupchat.client.ClientConfig;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.client.media.ImageUtil;
import com.example.groupchat.client.media.TenorClient;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Searches Tenor for GIFs and lets the player send one into the conversation.
 *
 * <p>Searching runs off the render thread, so the screen stays responsive while a request is in
 * flight. Results are fetched when the search is started; each result's still preview is then
 * downloaded in the background and drawn in the grid. Clicking a result downloads the animation
 * itself and hands it to the caller.
 */
public class GifPickerScreen extends Screen {
	private static final int CELL = 74;
	private static final int COLUMNS = 5;
	/** Edge length of the still preview drawn in a cell. */
	private static final int THUMB = 62;
	/**
	 * The previews are fetched on their own small pool, so a search for many results does not
	 * crowd out the shared background executor.
	 */
	private static final java.util.concurrent.ExecutorService PREVIEWS =
			java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
				Thread thread = new Thread(runnable, "groupchat-gif-preview");
				thread.setDaemon(true);
				return thread;
			});

	private record Click(int x, int y, int width, int height, Runnable action) {
	}

	/** A GIF the player picked, together with its bytes. */
	public record Selection(String id, byte[] data) {
	}

	private final Screen parent;
	private final Consumer<Selection> onPick;
	private final List<Click> clicks = new ArrayList<>();
	private final List<TenorClient.Gif> results = new ArrayList<>();
	/** Preview textures, keyed by GIF id. */
	private final Map<String, Identifier> previews = new LinkedHashMap<>();
	/** Preview sizes, keyed by GIF id. */
	private final Map<String, int[]> previewSizes = new LinkedHashMap<>();
	/** GIFs whose preview could not be loaded, so they are not retried forever. */
	private final List<String> previewFailed = new ArrayList<>();
	/** GIFs whose preview is still being downloaded. */
	private final java.util.Set<String> previewPending = new java.util.HashSet<>();

	private EditBox searchBox;
	private String status = "Search Tenor for a GIF.";
	private boolean searching;
	private boolean downloading;
	private int serial;

	public GifPickerScreen(Screen parent, Consumer<Selection> onPick) {
		super(Component.literal("GIFs"));
		this.parent = parent;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		this.searchBox = new EditBox(this.font, 10, 10, 200, 16, Component.literal("Search"));
		this.searchBox.setMaxLength(64);
		this.searchBox.setHint(Component.literal("search GIFs, e.g. celebration"));
		this.addRenderableWidget(this.searchBox);
		this.setInitialFocus(this.searchBox);
	}

	private void startSearch() {
		if (this.searching || this.searchBox == null) {
			return;
		}
		String query = this.searchBox.getValue().strip();
		if (query.isEmpty()) {
			this.status = "Type something to search for.";
			return;
		}
		this.runSearch(query);
	}

	/** Dev helper: runs a search for a fixed query, as if it had been typed in. */
	public void devSearch(String query) {
		if (this.searchBox != null) {
			this.searchBox.setValue(query);
		}
		this.runSearch(query);
	}

	private void runSearch(String query) {
		this.searching = true;
		this.results.clear();
		this.status = "Searching...";
		String host = ClientConfig.get().gifApiHost();
		String apiKey = ClientConfig.get().gifApiKey();
		Util.backgroundExecutor().execute(() -> {
			List<TenorClient.Gif> found;
			String failure = null;
			try {
				found = TenorClient.search(host, apiKey, query);
			} catch (Exception exception) {
				found = List.of();
				failure = exception.getMessage() == null ? exception.toString() : exception.getMessage();
			}
			List<TenorClient.Gif> finalFound = found;
			String finalFailure = failure;
			this.minecraft.execute(() -> {
				this.searching = false;
				this.results.clear();
				this.results.addAll(finalFound);
				this.status = finalFailure != null
						? "Search failed: " + finalFailure
						: (finalFound.isEmpty() ? "No GIFs found for that search." : finalFound.size() + " results.");
				// the previews of the previous search are stale now
				this.releasePreviews();
				this.loadPreviews(finalFound);
			});
		});
	}

	/**
	 * Downloads the still preview of every result, so the grid shows the animations instead of
	 * empty boxes. Each preview is fetched on its own background task.
	 */
	private void loadPreviews(List<TenorClient.Gif> gifs) {
		for (TenorClient.Gif gif : gifs) {
			if (gif.id().isEmpty() || gif.previewUrl().isEmpty()) {
				continue;
			}
			this.previewPending.add(gif.id());
			PREVIEWS.execute(() -> {
				try {
					byte[] raw = TenorClient.download(gif.previewUrl(), 2_000_000);
					byte[] png = ImageUtil.thumbnail(raw, THUMB * 2);
					this.minecraft.execute(() -> this.acceptPreview(gif.id(), png));
				} catch (Exception exception) {
					this.minecraft.execute(() -> {
						this.previewPending.remove(gif.id());
						if (!this.previewFailed.contains(gif.id())) {
							this.previewFailed.add(gif.id());
						}
					});
				}
			});
		}
	}

	/**
	 * Whether the search has finished and every result has a preview by now.
	 *
	 * <p>Dev helper, so the screenshot harness can wait for the grid to fill up.
	 */
	public boolean devPreviewsLoaded() {
		return !this.searching && !this.results.isEmpty() && this.previewPending.isEmpty();
	}

	/** Registers a downloaded preview as a texture. */
	private void acceptPreview(String id, byte[] png) {
		try {
			NativeImage image = NativeImage.read(png);
			Identifier location = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gifpicker/preview_" + (this.serial++));
			this.minecraft.getTextureManager().register(location, new DynamicTexture(() -> "groupchat gif preview", image));
			this.previews.put(id, location);
			this.previewSizes.put(id, new int[]{image.getWidth(), image.getHeight()});
			this.previewPending.remove(id);
		} catch (IOException exception) {
			this.previewPending.remove(id);
			if (!this.previewFailed.contains(id)) {
				this.previewFailed.add(id);
			}
		}
	}

	/** Frees the preview textures of a finished search. */
	private void releasePreviews() {
		for (Identifier location : this.previews.values()) {
			if (this.minecraft != null) {
				this.minecraft.getTextureManager().release(location);
			}
		}
		this.previews.clear();
		this.previewSizes.clear();
		this.previewFailed.clear();
		this.previewPending.clear();
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xD008080C);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.clicks.clear();
		int x = 8;
		int y = 8;
		int width = this.width - 16;
		int height = this.height - 16;
		UiKit.panel(graphics, x, y, width, height);

		graphics.drawString(this.font, "GIFs", x + 10, y + 9, UiKit.TEXT, false);

		if (this.searchBox != null) {
			this.searchBox.setX(x + 10);
			this.searchBox.setY(y + 24);
			this.searchBox.setWidth(Math.max(120, width - 220));
		}
		int buttonY = y + 24;
		UiKit.button(graphics, this.font, x + width - 140, buttonY, 68, 16,
				Component.literal("Search"), mouseX, mouseY, true, !this.searching && !this.downloading);
		this.addClick(x + width - 140, buttonY, 68, 16, this::startSearch);
		UiKit.button(graphics, this.font, x + width - 68, buttonY, 58, 16,
				Component.literal("Cancel"), mouseX, mouseY, false);
		this.addClick(x + width - 68, buttonY, 58, 16, () -> this.minecraft.setScreen(this.parent));

		int gridY = y + 48;
		int index = 0;
		for (TenorClient.Gif gif : this.results) {
			int column = index % COLUMNS;
			int row = index / COLUMNS;
			int cellX = x + 10 + column * CELL;
			int cellY = gridY + row * CELL;
			if (cellY + CELL > y + height - 30) {
				break;
			}
			boolean hover = UiKit.hovered(cellX, cellY, CELL - 6, CELL - 6, mouseX, mouseY);
			UiKit.fill(graphics, cellX, cellY, CELL - 6, CELL - 6, hover ? 0x50FFFFFF : 0x22000000);
			UiKit.outline(graphics, cellX, cellY, CELL - 6, CELL - 6, hover ? UiKit.ACCENT : UiKit.BORDER);
			this.drawPreview(graphics, gif, cellX + 3, cellY + 3);
			this.clicks.add(new Click(cellX, cellY, CELL - 6, CELL - 6, () -> this.pick(gif)));
			index++;
		}

		if (this.results.isEmpty() && !this.searching) {
			graphics.drawString(this.font, "No GIFs yet. Type a search above and press Search.",
					x + 12, gridY + 4, UiKit.TEXT_FAINT, false);
		}

		graphics.drawString(this.font, this.status, x + 10, y + height - 14,
				this.status.startsWith("Search failed") || this.status.startsWith("Could not download")
						? UiKit.BAD
						: UiKit.TEXT_FAINT,
				false);

		// the search box is a widget, so it has to be drawn on top of the panel
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	/** Draws the still preview of a result, or a placeholder while it is still loading. */
	private void drawPreview(GuiGraphics graphics, TenorClient.Gif gif, int x, int y) {
		Identifier texture = this.previews.get(gif.id());
		int[] size = this.previewSizes.get(gif.id());
		if (texture != null && size != null && size[0] > 0 && size[1] > 0) {
			// keep the aspect ratio of the preview inside the square cell
			float scale = Math.min((float) THUMB / size[0], (float) THUMB / size[1]);
			int drawWidth = Math.max(1, Math.round(size[0] * scale));
			int drawHeight = Math.max(1, Math.round(size[1] * scale));
			UiKit.blitTexture(graphics, texture, x + (THUMB - drawWidth) / 2, y + (THUMB - drawHeight) / 2,
					drawWidth, drawHeight, size[0], size[1]);
			return;
		}
		if (this.previewFailed.contains(gif.id())) {
			graphics.drawCenteredString(this.font, "!", x + THUMB / 2, y + THUMB / 2 - 4, UiKit.BAD);
			return;
		}
		graphics.drawCenteredString(this.font, "...", x + THUMB / 2, y + THUMB / 2 - 4, UiKit.TEXT_DIM);
	}

	/** Downloads the chosen GIF and hands the bytes to the caller. */
	private void pick(TenorClient.Gif gif) {
		if (this.downloading) {
			return;
		}
		this.status = "Downloading...";
		this.downloading = true;
		Util.backgroundExecutor().execute(() -> {
			byte[] data = null;
			String failure = null;
			try {
				data = TenorClient.download(gif.smallGifUrl(), com.example.groupchat.Constants.MAX_GIF_BYTES);
			} catch (Exception exception) {
				failure = exception.getMessage() == null ? exception.toString() : exception.getMessage();
			}
			byte[] finalData = data;
			String finalFailure = failure;
			this.minecraft.execute(() -> {
				this.downloading = false;
				if (finalData == null) {
					this.status = "Could not download that GIF: " + finalFailure;
					GroupChatClient.LOGGER.warn("[groupchat] GIF download failed: {}", finalFailure);
					return;
				}
				this.minecraft.setScreen(this.parent);
				this.onPick.accept(new Selection(gif.id(), finalData));
			});
		});
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
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			if (this.searchBox != null && this.searchBox.isFocused()) {
				this.startSearch();
				return true;
			}
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			this.minecraft.setScreen(this.parent);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	@Override
	public void removed() {
		this.releasePreviews();
	}
}
