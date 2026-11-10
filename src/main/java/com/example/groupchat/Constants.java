package com.example.groupchat;

/**
 * Shared limits and tuning knobs.
 */
public final class Constants {
	public static final String MOD_ID = "groupchat";
	public static final String MOD_NAME = "Group Chat";

	/** Payload channel ids are hostname-like, so they must not contain spaces. */
	public static final String PROTOCOL_VERSION = "1";

	// --- groups ---------------------------------------------------------------
	public static final int MAX_GROUP_MEMBERS = 16;
	public static final int MAX_NAME_LEN = 32;
	public static final int MAX_TEXT_LEN = 900;
	public static final int MAX_HISTORY = 200;
	public static final int MAX_GROUP_NAME_LEN = 32;

	// --- images ---------------------------------------------------------------
	public static final int MAX_IMAGE_BYTES = 1_500_000;
	public static final int MAX_IMAGE_DIMENSION = 1280;
	public static final int MAX_IMAGES_PER_GROUP = 96;
	/** Payloads registered as "large" are split into packets of at most this size. */
	public static final int LARGE_PAYLOAD_CHUNK = 30_000;

	// --- voice ----------------------------------------------------------------
	public static final int VOICE_SAMPLE_RATE = 16_000;
	public static final int VOICE_FRAME_MS = 40;
	public static final int VOICE_FRAME_SAMPLES = VOICE_SAMPLE_RATE * VOICE_FRAME_MS / 1000;
	/** ADPCM of {@link #VOICE_FRAME_SAMPLES} samples: 4 byte header + 1 nibble per sample. */
	public static final int VOICE_FRAME_BYTES = 4 + VOICE_FRAME_SAMPLES / 2;
	public static final int VOICE_MAX_FRAME_BYTES = VOICE_FRAME_BYTES + 16;
	/** Hard server-side cap on voice frames per second per player. */
	public static final int VOICE_MAX_FRAMES_PER_SECOND = 1000 / VOICE_FRAME_MS * 2;

	// --- rate limits (server side) -------------------------------------------
	public static final int RATE_TEXT_PER_10S = 40;
	public static final int RATE_INVITE_PER_60S = 30;
	public static final int RATE_IMAGE_PER_60S = 20;

	private Constants() {
	}
}
