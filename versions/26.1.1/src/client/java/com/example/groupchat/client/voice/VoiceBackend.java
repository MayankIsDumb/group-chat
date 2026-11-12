package com.example.groupchat.client.voice;

import com.example.groupchat.client.ClientConfig;
import net.fabricmc.loader.api.FabricLoader;

/** Decides whether the built-in voice transport or Simple Voice Chat drives a call. */
public final class VoiceBackend {
	private VoiceBackend() {
	}

	public static boolean useSimpleVoiceChat() {
		if (!FabricLoader.getInstance().isModLoaded("voicechat")) {
			return false;
		}
		return ClientConfig.get().voiceMode() != ClientConfig.VoiceMode.BUILTIN;
	}
}
