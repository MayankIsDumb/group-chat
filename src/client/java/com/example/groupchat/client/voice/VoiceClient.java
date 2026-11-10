package com.example.groupchat.client.voice;

import com.example.groupchat.Constants;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.net.Payloads;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Push-to-talk voice over the Minecraft connection.
 *
 * <ul>
 *   <li>capture: 16 kHz mono, 40 ms frames, IMA-ADPCM (~8 KB/s)</li>
 *   <li>transport: {@link Payloads.VoiceFrame} → server relay → {@link Payloads.Voice}</li>
 *   <li>playback: one mixed 16 kHz line, so any number of speakers can talk at once</li>
 * </ul>
 */
public final class VoiceClient {
	public static final VoiceClient INSTANCE = new VoiceClient();

	private static final AudioFormat FORMAT = new AudioFormat(Constants.VOICE_SAMPLE_RATE, 16, 1, true, false);
	private static final int PCM_FRAME_BYTES = Constants.VOICE_FRAME_SAMPLES * 2;
	private static final int QUEUE_LIMIT = 6;

	private final Map<String, Queue<short[]>> inbox = new ConcurrentHashMap<>();
	private final Map<String, Long> lastHeard = new ConcurrentHashMap<>();
	private final Queue<byte[]> outbox = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean running = new AtomicBoolean();

	private volatile String callGroupId;
	private volatile boolean muted;
	private volatile boolean talkHeld;
	private volatile String error;
	private volatile long lastSentAt;

	private TargetDataLine mic;
	private SourceDataLine speakers;
	private Thread captureThread;
	private Thread playThread;
	private int sequence;

	private VoiceClient() {
	}

	// ------------------------------------------------------------------ state

	public boolean isInCall() {
		return this.callGroupId != null;
	}

	public String callGroupId() {
		return this.callGroupId;
	}

	public boolean isMuted() {
		return this.muted;
	}

	public void setMuted(boolean value) {
		this.muted = value;
	}

	/** Push-to-talk key state, updated every client tick. */
	public void setTalking(boolean value) {
		this.talkHeld = value;
	}

	public boolean isTalking() {
		return this.talkHeld && !this.muted;
	}

	public String error() {
		return this.error;
	}

	public boolean isSpeaking(String playerName) {
		Long last = this.lastHeard.get(playerName);
		return last != null && System.nanoTime() - last < 400_000_000L;
	}

	public boolean isTransmitting() {
		return this.isInCall() && !this.muted && this.talkHeld;
	}

	// --------------------------------------------------------------- lifecycle

	public void join(String groupId) {
		if (groupId == null) {
			return;
		}
		leave();
		this.callGroupId = groupId;
		this.error = null;
		this.running.set(true);
		openDevices();
		this.playThread = new Thread(this::playLoop, "groupchat-voice-play");
		this.playThread.setDaemon(true);
		this.playThread.start();
		if (this.mic != null) {
			this.captureThread = new Thread(this::captureLoop, "groupchat-voice-capture");
			this.captureThread.setDaemon(true);
			this.captureThread.start();
		}
		GroupChatClient.LOGGER.info("[groupchat] joined voice call in {}", groupId);
	}

	public void leave() {
		if (this.callGroupId == null) {
			return;
		}
		this.callGroupId = null;
		this.running.set(false);
		closeDevices();
		this.inbox.clear();
		this.lastHeard.clear();
		this.outbox.clear();
		GroupChatClient.LOGGER.info("[groupchat] left voice call");
	}

	private void openDevices() {
		try {
			DataLine.Info captureInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
			if (!AudioSystem.isLineSupported(captureInfo)) {
				this.error = "No microphone available";
			} else {
				this.mic = (TargetDataLine) AudioSystem.getLine(captureInfo);
				this.mic.open(FORMAT, PCM_FRAME_BYTES * 4);
				this.mic.start();
			}
		} catch (Exception exception) {
			this.error = "Microphone unavailable: " + exception.getMessage();
			GroupChatClient.LOGGER.warn("[groupchat] could not open microphone", exception);
			this.mic = null;
		}
		try {
			DataLine.Info playInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
			if (!AudioSystem.isLineSupported(playInfo)) {
				this.error = "No speakers available";
			} else {
				this.speakers = (SourceDataLine) AudioSystem.getLine(playInfo);
				this.speakers.open(FORMAT, PCM_FRAME_BYTES * 4);
				this.speakers.start();
			}
		} catch (Exception exception) {
			this.error = "Speakers unavailable: " + exception.getMessage();
			GroupChatClient.LOGGER.warn("[groupchat] could not open speakers", exception);
			this.speakers = null;
		}
	}

	private void closeDevices() {
		TargetDataLine mic = this.mic;
		this.mic = null;
		if (mic != null) {
			try {
				mic.stop();
				mic.close();
			} catch (RuntimeException ignored) {
			}
		}
		SourceDataLine speakers = this.speakers;
		this.speakers = null;
		if (speakers != null) {
			try {
				speakers.stop();
				speakers.close();
			} catch (RuntimeException ignored) {
			}
		}
	}

	// ------------------------------------------------------------------ loops

	private void captureLoop() {
		byte[] buffer = new byte[PCM_FRAME_BYTES];
		short[] samples = new short[Constants.VOICE_FRAME_SAMPLES];
		while (this.running.get()) {
			TargetDataLine line = this.mic;
			if (line == null) {
				return;
			}
			int read;
			try {
				read = line.read(buffer, 0, buffer.length);
			} catch (RuntimeException exception) {
				break;
			}
			if (read < PCM_FRAME_BYTES) {
				continue;
			}
			if (this.muted || !this.talkHeld) {
				continue;
			}
			for (int i = 0; i < samples.length; i++) {
				samples[i] = (short) ((buffer[i * 2] & 0xFF) | (buffer[i * 2 + 1] << 8));
			}
			if (rms(samples) < 220) {
				continue;
			}
			this.outbox.add(Adpcm.encode(samples, samples.length));
			while (this.outbox.size() > 12) {
				this.outbox.poll();
			}
		}
	}

	private void playLoop() {
		byte[] bytes = new byte[PCM_FRAME_BYTES];
		short[] mix = new short[Constants.VOICE_FRAME_SAMPLES];
		while (this.running.get()) {
			SourceDataLine line = this.speakers;
			if (line == null) {
				return;
			}
			Arrays.fill(mix, (short) 0);
			for (Map.Entry<String, Queue<short[]>> entry : this.inbox.entrySet()) {
				short[] frame = entry.getValue().poll();
				if (frame == null) {
					continue;
				}
				for (int i = 0; i < mix.length && i < frame.length; i++) {
					int sum = mix[i] + frame[i];
					mix[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum));
				}
			}
			for (int i = 0; i < mix.length; i++) {
				bytes[i * 2] = (byte) (mix[i] & 0xFF);
				bytes[i * 2 + 1] = (byte) ((mix[i] >> 8) & 0xFF);
			}
			try {
				line.write(bytes, 0, bytes.length);
			} catch (RuntimeException exception) {
				break;
			}
		}
	}

	// ------------------------------------------------------------------ wiring

	public void onVoice(String sender, byte[] data) {
		short[] samples = Adpcm.decode(data, Constants.VOICE_FRAME_SAMPLES);
		Queue<short[]> queue = this.inbox.computeIfAbsent(sender, ignored -> new ConcurrentLinkedQueue<>());
		queue.add(samples);
		while (queue.size() > QUEUE_LIMIT) {
			queue.poll();
		}
		this.lastHeard.put(sender, System.nanoTime());
	}

	/** Called every client tick: drains captured frames onto the network. */
	public void tick() {
		String groupId = this.callGroupId;
		if (groupId == null) {
			this.outbox.clear();
			return;
		}
		long now = System.currentTimeMillis();
		if (now - this.lastSentAt < Constants.VOICE_FRAME_MS) {
			return;
		}
		byte[] frame = this.outbox.poll();
		if (frame == null) {
			return;
		}
		this.lastSentAt = now;
		if (ClientPlayNetworking.canSend(Payloads.VoiceFrame.TYPE)) {
			ClientPlayNetworking.send(new Payloads.VoiceFrame(groupId, frame, this.sequence++));
		}
	}

	public void shutdown() {
		leave();
	}

	private static double rms(short[] samples) {
		double sum = 0;
		for (short sample : samples) {
			sum += (double) sample * sample;
		}
		return Math.sqrt(sum / samples.length);
	}
}
