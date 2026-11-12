package com.example.groupchat.client.media;

import com.example.groupchat.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * A decoded animated GIF, rendered as a texture that cycles through its frames.
 *
 * <p>All frames are packed into a single texture laid out horizontally, and the animation picks the
 * frame by offsetting the source rectangle. That means one texture upload instead of one per frame,
 * which matters because a busy chat can hold several GIFs at once.
 */
public final class GifTexture {
	private final Identifier location;
	private final DynamicTexture texture;
	private final int frameWidth;
	private final int frameHeight;
	private final int frameCount;
	private final int[] delays;
	private int frame;
	private long frameEndsAt;

	private GifTexture(Identifier location, DynamicTexture texture, int frameWidth, int frameHeight,
			int frameCount, int[] delays) {
		this.location = location;
		this.texture = texture;
		this.frameWidth = frameWidth;
		this.frameHeight = frameHeight;
		this.frameCount = frameCount;
		this.delays = delays;
	}

	/**
	 * Decodes GIF bytes and builds the texture.
	 *
	 * @param data   the raw GIF
	 * @param name   a name for the texture, usually the message id
	 * @return the animation, or {@code null} when the data could not be decoded
	 */
	public static GifTexture create(byte[] data, String name) {
		GifDecoder.Decoded decoded;
		try {
			decoded = GifDecoder.decode(data);
		} catch (IOException exception) {
			return null;
		}
		if (decoded.frames().length == 0) {
			return null;
		}
		int frameWidth = decoded.width();
		int frameHeight = decoded.height();
		int frameCount = decoded.frames().length;
		int sheetWidth = frameWidth * frameCount;

		NativeImage sheet = new NativeImage(sheetWidth, frameHeight, false);
		try {
			for (int i = 0; i < frameCount; i++) {
				int[] pixels = decoded.frames()[i];
				int offset = i * frameWidth;
				for (int y = 0; y < frameHeight; y++) {
					int row = y * frameWidth;
					for (int x = 0; x < frameWidth; x++) {
						// setPixel takes a plain ARGB value and does the byte order itself
						sheet.setPixel(offset + x, y, pixels[row + x]);
					}
				}
			}
		} catch (RuntimeException exception) {
			sheet.close();
			return null;
		}

		Identifier location = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "gif/" + sanitize(name));
		DynamicTexture texture = new DynamicTexture(() -> "groupchat gif " + name, sheet);
		Minecraft.getInstance().getTextureManager().register(location, texture);
		return new GifTexture(location, texture, frameWidth, frameHeight, frameCount, decoded.delays());
	}

	public Identifier location() {
		return this.location;
	}

	public int frameWidth() {
		return this.frameWidth;
	}

	public int frameHeight() {
		return this.frameHeight;
	}

	public int frameCount() {
		return this.frameCount;
	}

	/** Total width of the packed sheet, which is what the blit helper expects. */
	public int sheetWidth() {
		return this.frameWidth * this.frameCount;
	}

	public int sheetHeight() {
		return this.frameHeight;
	}

	/** Source X of the frame that should be drawn right now. */
	public int sourceX() {
		return this.frame * this.frameWidth;
	}

	/**
	 * Advances the animation.
	 *
	 * @param now the current time in milliseconds
	 * @return {@code true} when the frame changed and the screen should redraw
	 */
	public boolean advance(long now) {
		if (this.frameCount <= 1) {
			return false;
		}
		if (this.frameEndsAt == 0L) {
			this.frameEndsAt = now + this.delays[this.frame];
			return false;
		}
		if (now < this.frameEndsAt) {
			return false;
		}
		this.frame = (this.frame + 1) % this.frameCount;
		this.frameEndsAt = now + this.delays[this.frame];
		return true;
	}

	public void close() {
		Minecraft.getInstance().getTextureManager().release(this.location);
		this.texture.close();
	}

	/** Identifier paths must be lowercase and may only contain {@code [a-z0-9/._-]}. */
	private static String sanitize(String value) {
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length() && builder.length() < 40; i++) {
			char character = Character.toLowerCase(value.charAt(i));
			if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
					|| character == '-' || character == '_') {
				builder.append(character);
			}
		}
		return builder.length() == 0 ? "gif" : builder.toString();
	}
}
