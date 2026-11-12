package com.example.groupchat.client.voice;

import com.example.groupchat.Constants;

/**
 * IMA ADPCM (4:1) for voice frames. Every frame is self-contained (it carries its own predictor and
 * step index), so a dropped packet never desynchronises the stream — at the cost of a slightly
 * worse signal-to-noise ratio at frame boundaries, which is a good trade for voice chat.
 */
public final class Adpcm {
	private static final int[] INDEX_TABLE = {
			-1, -1, -1, -1, 2, 4, 6, 8,
			-1, -1, -1, -1, 2, 4, 6, 8
	};

	private static final int[] STEP_TABLE = {
			7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
			34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
			157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
			724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
			3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
			15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
	};

	private Adpcm() {
	}

	public static byte[] encode(short[] samples, int length) {
		int count = Math.max(1, Math.min(length, Constants.VOICE_FRAME_SAMPLES));
		byte[] out = new byte[4 + (count + 1) / 2];
		State state = new State();
		state.predictor = samples[0];
		state.index = 0;
		out[0] = (byte) (state.predictor & 0xFF);
		out[1] = (byte) ((state.predictor >> 8) & 0xFF);
		out[2] = (byte) state.index;
		out[3] = 0;
		int outIndex = 4;
		for (int i = 0; i < count; i += 2) {
			int first = encodeNibble(samples[i], state);
			int second = i + 1 < count ? encodeNibble(samples[i + 1], state) : first;
			out[outIndex++] = (byte) ((first & 0x0F) | ((second & 0x0F) << 4));
		}
		return out;
	}

	public static short[] decode(byte[] data, int sampleCount) {
		int count = Math.max(1, Math.min(sampleCount, Constants.VOICE_FRAME_SAMPLES));
		short[] out = new short[count];
		if (data.length < 4) {
			return out;
		}
		State state = new State();
		state.predictor = (short) ((data[0] & 0xFF) | ((data[1] & 0xFF) << 8));
		state.index = Math.max(0, Math.min(88, data[2] & 0xFF));
		int dataIndex = 4;
		// one byte carries two 4-bit samples, so consume the stream in pairs
		for (int i = 0; i < count; i += 2) {
			if (dataIndex >= data.length) {
				out[i] = (short) state.predictor;
				if (i + 1 < count) {
					out[i + 1] = (short) state.predictor;
				}
				continue;
			}
			int packed = data[dataIndex++] & 0xFF;
			out[i] = (short) decodeNibble(packed & 0x0F, state);
			if (i + 1 < count) {
				out[i + 1] = (short) decodeNibble((packed >> 4) & 0x0F, state);
			}
		}
		return out;
	}

	private static int encodeNibble(short sample, State state) {
		int step = STEP_TABLE[state.index];
		int difference = sample - state.predictor;
		int sign = 0;
		if (difference < 0) {
			sign = 8;
			difference = -difference;
		}
		int delta = 0;
		int prediction = step >> 3;
		if (difference >= step) {
			delta |= 4;
			difference -= step;
			prediction += step;
		}
		step >>= 1;
		if (difference >= step) {
			delta |= 2;
			difference -= step;
			prediction += step;
		}
		step >>= 1;
		if (difference >= step) {
			delta |= 1;
			prediction += step;
		}
		if (sign != 0) {
			state.predictor -= prediction;
		} else {
			state.predictor += prediction;
		}
		state.predictor = clamp(state.predictor, Short.MIN_VALUE, Short.MAX_VALUE);
		state.index = clamp(state.index + INDEX_TABLE[delta], 0, 88);
		return delta | sign;
	}

	private static int decodeNibble(int nibble, State state) {
		int step = STEP_TABLE[state.index];
		int delta = nibble & 0x07;
		int prediction = step >> 3;
		if ((delta & 1) != 0) {
			prediction += step >> 2;
		}
		if ((delta & 2) != 0) {
			prediction += step >> 1;
		}
		if ((delta & 4) != 0) {
			prediction += step;
		}
		if ((nibble & 0x08) != 0) {
			state.predictor -= prediction;
		} else {
			state.predictor += prediction;
		}
		state.predictor = clamp(state.predictor, Short.MIN_VALUE, Short.MAX_VALUE);
		state.index = clamp(state.index + INDEX_TABLE[nibble & 0x0F], 0, 88);
		return state.predictor;
	}

	private static int clamp(int value, int min, int max) {
		return value < min ? min : Math.min(value, max);
	}

	private static final class State {
		int predictor;
		int index;
	}
}
