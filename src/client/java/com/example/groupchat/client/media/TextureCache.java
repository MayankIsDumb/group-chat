package com.example.groupchat.client.media;

import com.example.groupchat.Constants;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.net.Payloads;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Maps chat image ids to GPU textures. Images are fetched lazily the first time a message that
 * references them is drawn, and the least recently used texture is released beyond the cap.
 */
public final class TextureCache {
	private static final int MAX_TEXTURES = 24;

	private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
			if (this.size() > MAX_TEXTURES) {
				Minecraft.getInstance().getTextureManager().release(eldest.getValue().location());
				return true;
			}
			return false;
		}
	};
	private final Set<String> requested = new HashSet<>();
	private final Map<String, Long> failures = new HashMap<>();

	public record Entry(Identifier location, int width, int height) {
	}

	/** @return the texture data, or {@code null} while it is still being fetched */
	public Entry get(String imageId) {
		Entry entry = this.entries.get(imageId);
		if (entry != null) {
			return entry;
		}
		if (this.requested.contains(imageId)) {
			return null;
		}
		Long failure = this.failures.get(imageId);
		if (failure != null && System.currentTimeMillis() - failure < 15_000L) {
			return null;
		}
		this.requested.add(imageId);
		if (Minecraft.getInstance().getConnection() != null) {
			ClientPlayNetworking.send(new Payloads.RequestImage(imageId));
		}
		return null;
	}

	public boolean isPending(String imageId) {
		return this.entries.containsKey(imageId) || this.requested.contains(imageId);
	}

	/** Registers bytes that arrived from the server (or that this client just uploaded). */
	public boolean accept(String imageId, byte[] png) {
		try {
			NativeImage image = NativeImage.read(png);
			Identifier location = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "chat_image/" + sanitize(imageId));
			DynamicTexture texture = new DynamicTexture(() -> "groupchat image " + imageId, image);
			Minecraft.getInstance().getTextureManager().register(location, texture);
			this.entries.put(imageId, new Entry(location, image.getWidth(), image.getHeight()));
			this.requested.add(imageId);
			this.failures.remove(imageId);
			return true;
		} catch (IOException | RuntimeException exception) {
			GroupChatClient.LOGGER.warn("[groupchat] could not decode image {}", imageId, exception);
			this.failures.put(imageId, System.currentTimeMillis());
			return false;
		}
	}

	public void reset() {
		for (Entry entry : this.entries.values()) {
			Minecraft.getInstance().getTextureManager().release(entry.location());
		}
		this.entries.clear();
		this.requested.clear();
		this.failures.clear();
	}

	private static String sanitize(String imageId) {
		StringBuilder builder = new StringBuilder(imageId.length());
		for (int i = 0; i < imageId.length() && builder.length() < 40; i++) {
			char character = imageId.charAt(i);
			if (Character.isLetterOrDigit(character) || character == '-' || character == '_') {
				builder.append(character);
			}
		}
		return builder.toString();
	}
}
