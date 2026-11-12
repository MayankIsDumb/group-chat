package com.example.groupchat.client.media;

import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.net.Payloads;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Holds the animated GIFs that are currently on screen.
 *
 * <p>GIF bytes are fetched from the server on demand, exactly like images: the first frame that
 * needs a GIF asks for it, and the server only serves it to members of the owning group.
 */
public final class GifCache {
	private static final GifCache INSTANCE = new GifCache();

	private final Map<String, GifTexture> textures = new LinkedHashMap<>(8, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, GifTexture> eldest) {
			if (this.size() > com.example.groupchat.Constants.MAX_GIF_CACHE) {
				eldest.getValue().close();
				return true;
			}
			return false;
		}
	};

	private final Map<String, Boolean> requested = new LinkedHashMap<>();
	private final Map<String, Long> failures = new LinkedHashMap<>();

	private GifCache() {
	}

	public static GifCache instance() {
		return INSTANCE;
	}

	/** The animation for a GIF, or {@code null} while it is still being fetched. */
	public GifTexture get(String gifId) {
		GifTexture texture = this.textures.get(gifId);
		if (texture != null) {
			return texture;
		}
		if (this.requested.containsKey(gifId)) {
			return null;
		}
		Long failure = this.failures.get(gifId);
		if (failure != null && System.currentTimeMillis() - failure < 15_000L) {
			return null;
		}
		this.requested.put(gifId, Boolean.TRUE);
		if (Minecraft.getInstance().getConnection() != null
				&& ClientPlayNetworking.canSend(Payloads.RequestGif.TYPE)) {
			ClientPlayNetworking.send(new Payloads.RequestGif(gifId));
		}
		return null;
	}

	/** Registers GIF bytes that arrived from the server. */
	public void accept(String gifId, byte[] data) {
		GifTexture texture = GifTexture.create(data, gifId);
		if (texture == null) {
			this.failures.put(gifId, System.currentTimeMillis());
			return;
		}
		this.failures.remove(gifId);
		this.textures.put(gifId, texture);
	}

	public void reset() {
		for (GifTexture texture : this.textures.values()) {
			texture.close();
		}
		this.textures.clear();
		this.requested.clear();
		this.failures.clear();
	}

	/** The animations that are currently loaded, for the client tick. */
	public Iterable<GifTexture> textures() {
		return this.textures.values();
	}
}
