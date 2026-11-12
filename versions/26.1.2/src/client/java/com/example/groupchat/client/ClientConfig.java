package com.example.groupchat.client;

import com.example.groupchat.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Client settings, persisted to {@code config/groupchat/client.json}. */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClientConfig instance;

	private boolean autoApproveWaypoints;
	private VoiceMode voiceMode = VoiceMode.BUILTIN;
	private String gifApiKey = "";
	private String gifApiHost = "tenor.googleapis.com";

	public enum VoiceMode {
		AUTO,
		BUILTIN,
		SIMPLE_VOICE_CHAT
	}

	public static ClientConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public boolean autoApproveWaypoints() {
		return this.autoApproveWaypoints;
	}

	public void setAutoApproveWaypoints(boolean value) {
		this.autoApproveWaypoints = value;
		save();
	}

	public VoiceMode voiceMode() {
		return this.voiceMode == null ? VoiceMode.AUTO : this.voiceMode;
	}

	public void setVoiceMode(VoiceMode mode) {
		this.voiceMode = mode;
		save();
	}

	/** Optional Tenor API key. Without one the public search page is used instead. */
	public String gifApiKey() {
		return this.gifApiKey == null ? "" : this.gifApiKey;
	}

	public void setGifApiKey(String key) {
		this.gifApiKey = key == null ? "" : key.trim();
		save();
	}

	/** The API host used for GIF searches. */
	public String gifApiHost() {
		return this.gifApiHost == null || this.gifApiHost.isBlank()
				? "tenor.googleapis.com"
				: this.gifApiHost;
	}

	public void setGifApiHost(String host) {
		this.gifApiHost = host == null ? "" : host.trim();
		save();
	}

	private static ClientConfig load() {
		Path file = file();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				ClientConfig loaded = GSON.fromJson(reader, ClientConfig.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException | JsonSyntaxException exception) {
				GroupChatClient.LOGGER.warn("[groupchat] could not read client config", exception);
			}
		}
		return new ClientConfig();
	}

	private void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException exception) {
			GroupChatClient.LOGGER.warn("[groupchat] could not save client config", exception);
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Constants.MOD_ID).resolve("client.json");
	}
}
