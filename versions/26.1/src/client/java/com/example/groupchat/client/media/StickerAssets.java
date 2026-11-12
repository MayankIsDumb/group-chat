package com.example.groupchat.client.media;

import com.example.groupchat.Constants;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * The bundled starter sticker pack. Stickers are shipped inside the mod jar, so sending one only
 * costs a six-byte payload: the id.
 */
public final class StickerAssets {
	public static final List<String> IDS = List.of(
			"smile", "laugh", "sad", "cool", "heart", "star", "creeper", "diamond", "party");

	private StickerAssets() {
	}

	public static boolean isKnown(String stickerId) {
		return IDS.contains(stickerId);
	}

	public static Identifier texture(String stickerId) {
		String safe = isKnown(stickerId) ? stickerId : "smile";
		return Identifier.fromNamespaceAndPath(Constants.MOD_ID, "textures/stickers/" + safe + ".png");
	}
}
