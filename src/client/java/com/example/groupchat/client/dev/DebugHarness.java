package com.example.groupchat.client.dev;

import com.example.groupchat.client.ClientState;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.client.ui.HubScreen;
import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.InviteDto;
import com.example.groupchat.net.Payloads.MemberDto;
import com.example.groupchat.net.Payloads.MessageDto;
import com.example.groupchat.net.Payloads.Snapshot;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.util.Util;

/**
 * Development-only smoke tests, enabled with {@code -Dgroupchat.devtools=true}.
 *
 * <ul>
 *   <li>default: offline UI tour — seeds demo data, screenshots every hub panel</li>
 *   <li>{@code -Dgroupchat.devtools.connect=host:port}: joins a real server, creates a group, sends
 *       a text message, a sticker and an image, then screenshots the live conversation</li>
 * </ul>
 */
public final class DebugHarness {
	private static final String DEMO_IMAGE_ID = "dev_demo_image";
	private static final String CONNECT = System.getProperty("groupchat.devtools.connect", "").trim();
	private static final boolean VOICE_TEST = Boolean.getBoolean("groupchat.devtools.voicetest");
	private static final String AGENT = System.getProperty("groupchat.devtools.agent", "").trim().toLowerCase();

	private static int phase;
	private static int phaseTicks;
	private static HubScreen hub;
	private static String groupId;
	private static int lastGifSource = -1;

	private DebugHarness() {
	}

	public static void init() {
		if (!Boolean.getBoolean("groupchat.devtools")) {
			return;
		}
		GroupChatClient.LOGGER.warn("[groupchat] devtools enabled (connect='{}', agent='{}')", CONNECT, AGENT);
		if (!AGENT.isEmpty()) {
			if (AGENT.equals("friend") || AGENT.equals("human")) {
				LiveSession.init(AGENT);
			} else if (AGENT.startsWith("sweep-")) {
				FeatureSweep.init(AGENT);
			} else if (AGENT.equals("modmenu")) {
				ModMenuCheck.init();
			} else {
				AgentScenario.init(AGENT);
			}
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(DebugHarness::tick);
	}

	private static void tick(Minecraft client) {
		boolean titleReady = client.screen instanceof TitleScreen && client.getOverlay() == null;
		if (phase == 0) {
			if (titleReady) {
				phase = 1;
				phaseTicks = 0;
			}
			return;
		}
		if (VOICE_TEST) {
			phaseTicks++;
			if (phaseTicks > 10) {
				voiceSelfTest();
				client.stop();
			}
		} else if (CONNECT.isEmpty()) {
			tickOffline(client);
		} else {
			tickNetwork(client);
		}
	}

	// ------------------------------------------------------------ offline tour

	private static void tickOffline(Minecraft client) {
		phaseTicks++;
		if (phase >= 2 && phase <= 5 && !(client.screen instanceof HubScreen)) {
			// the title screen reinstalls itself once the Realms availability check finishes
			client.setScreen(hub);
		}
		switch (phase) {
			case 1 -> {
				if (phaseTicks > 20) {
					seedOffline();
					log("seeded demo data, opening hub");
					hub = new HubScreen(null);
					client.setScreen(hub);
					phase = 2;
					phaseTicks = 0;
				}
			}
			case 2 -> {
				if (phaseTicks == 24) {
					shot(client, 1);
				} else if (phaseTicks > 30) {
					log("open create panel");
					hub.devOpenPanel("create");
					phase = 3;
					phaseTicks = 0;
				}
			}
			case 3 -> {
				if (phaseTicks == 18) {
					shot(client, 2);
				} else if (phaseTicks > 24) {
					log("open invites panel");
					hub.devOpenPanel("invites");
					phase = 4;
					phaseTicks = 0;
				}
			}
			case 4 -> {
				if (phaseTicks == 18) {
					shot(client, 3);
				} else if (phaseTicks > 24) {
					log("open members panel");
					hub.devOpenPanel("members");
					phase = 5;
					phaseTicks = 0;
				}
			}
			case 5 -> {
				if (phaseTicks == 18) {
					shot(client, 4);
				} else if (phaseTicks > 24) {
					log("back to chat, scrolled to top");
					hub.devOpenPanel("chat");
					hub.devScrollToTop();
					phase = 6;
					phaseTicks = 0;
				}
			}
			case 6 -> {
				if (phaseTicks == 18) {
					shot(client, 5);
				} else if (phaseTicks > 30) {
					log("open waypoints screen");
					client.setScreen(new com.example.groupchat.client.ui.WaypointsScreen(null));
					phase = 7;
					phaseTicks = 0;
				}
			}
			case 7 -> {
				if (phaseTicks == 18) {
					shot(client, 6);
				} else if (phaseTicks > 30) {
					log("done, stopping client");
					client.stop();
				}
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------- live server

	private static void tickNetwork(Minecraft client) {
		phaseTicks++;
		switch (phase) {
			case 1 -> {
				if (phaseTicks > 10) {
					log("connecting to " + CONNECT);
					ServerData data = new ServerData("dev", CONNECT, ServerData.Type.OTHER);
					ConnectScreen.startConnecting(null, client, ServerAddress.parseString(CONNECT), data, false, null);
					phase = 2;
					phaseTicks = 0;
				}
			}
			case 2 -> {
				if (client.player != null && client.level != null) {
					log("joined the server as " + client.player.getName().getString());
					phase = 3;
					phaseTicks = 0;
				} else if (phaseTicks > 400) {
					log("could not join, giving up");
					client.stop();
				}
			}
			case 3 -> {
				if (phaseTicks > 20) {
					log("creating group");
					ClientPlayNetworking.send(new Payloads.CreateGroup("dev squad", List.of()));
					phase = 4;
					phaseTicks = 0;
				}
			}
			case 4 -> {
				if (!ClientState.INSTANCE.groups().isEmpty()) {
					GroupDto group = ClientState.INSTANCE.groups().get(0);
					groupId = group.id();
					log("group available: " + group.name() + " [" + groupId + "]");
					ClientState.INSTANCE.setActiveGroup(groupId);
					client.setScreen(new HubScreen(null));
					phase = 5;
					phaseTicks = 0;
				} else if (phaseTicks > 200) {
					log("group never arrived");
					client.stop();
				}
			}
			case 5 -> {
				if (phaseTicks == 20) {
					ClientPlayNetworking.send(new Payloads.SendText(groupId, "hello from the dev harness"));
					ClientPlayNetworking.send(new Payloads.SendSticker(groupId, "party"));
					sendImage();
					log("sent text + sticker + image");
				} else if (phaseTicks == 40) {
					sendGif();
				} else if (phaseTicks > 120) {
					shot(client, 9);
					phase = 7;
					phaseTicks = 0;
				}
			}
			case 8 -> {
				// open the GIF picker with a search already run, so the grid can be checked
				if (phaseTicks > 20) {
					openGifPicker(client);
					phase = 9;
					phaseTicks = 0;
				}
			}
			case 9 -> {
				if (phaseTicks == 60) {
					shot(client, 10);
				} else if (phaseTicks > 90) {
					log("done, stopping client");
					client.stop();
				}
			}
			case 7 -> {
				// verify the GIF actually animates: the source rectangle must move between frames
				com.example.groupchat.client.media.GifTexture gif = findGif();
				if (gif == null) {
					if (phaseTicks > 600) {
						log("FAIL gif_animation: the GIF never reached the client");
						phase = 6;
						phaseTicks = 0;
					}
					return;
				}
				if (lastGifSource < 0) {
					lastGifSource = gif.sourceX();
					return;
				}
				if (gif.sourceX() != lastGifSource) {
					log("PASS gif_animation: frame moved from x=" + lastGifSource + " to x=" + gif.sourceX()
							+ " (frame " + (gif.sourceX() / gif.frameWidth()) + " of " + gif.frameCount() + ")");
					phase = 8;
					phaseTicks = 0;
				} else if (phaseTicks > 600) {
					log("FAIL gif_animation: the frame never advanced");
					phase = 6;
					phaseTicks = 0;
				}
			}
			case 6 -> {
				if (phaseTicks > 40) {
					log("done, stopping client");
					client.stop();
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Opens the GIF picker and runs a search in it, so the grid and its previews can be checked.
	 */
	private static void openGifPicker(Minecraft client) {
		com.example.groupchat.client.ui.GifPickerScreen picker =
				new com.example.groupchat.client.ui.GifPickerScreen(null, selection -> {
				});
		client.setScreen(picker);
		picker.devSearch("celebration");
		log("opened the GIF picker");
	}

	private static void sendImage() {
		byte[] demo = demoImage();
		if (demo == null) {
			return;
		}
		GroupChatClient.INSTANCE.textures.accept(DEMO_IMAGE_ID, demo);
		ClientPlayNetworking.send(new Payloads.UploadImage(groupId, DEMO_IMAGE_ID, demo));
	}

	/** The GIF that is currently on screen, if it has arrived. */
	private static com.example.groupchat.client.media.GifTexture findGif() {
		for (com.example.groupchat.client.media.GifTexture texture :
				com.example.groupchat.client.media.GifCache.instance().textures()) {
			return texture;
		}
		return null;
	}

	/**
	 * Searches Tenor for a GIF and sends the first result into the group, so the whole GIF path
	 * (search, download, upload, relay, decode, animate) can be checked in one run.
	 */
	private static void sendGif() {
		Util.backgroundExecutor().execute(() -> {
			try {
				List<com.example.groupchat.client.media.TenorClient.Gif> results =
						com.example.groupchat.client.media.TenorClient.search(
								com.example.groupchat.client.ClientConfig.get().gifApiHost(),
								com.example.groupchat.client.ClientConfig.get().gifApiKey(),
								"celebration");
				log("GIF search returned " + results.size() + " results");
				if (results.isEmpty()) {
					return;
				}
				com.example.groupchat.client.media.TenorClient.Gif gif = results.get(0);
				byte[] data = com.example.groupchat.client.media.TenorClient.download(gif.smallGifUrl(),
						com.example.groupchat.Constants.MAX_GIF_BYTES);
				log("downloaded GIF " + gif.id() + " (" + data.length + " bytes)");
				Minecraft.getInstance().execute(() -> {
					String gifId = Long.toString(System.nanoTime(), 36);
					ClientPlayNetworking.send(new Payloads.UploadGif(groupId, gifId, data));
					log("uploaded GIF as " + gifId);
				});
			} catch (Exception exception) {
				log("GIF harness failed: " + exception);
			}
		});
	}

	// ------------------------------------------------------------------ shared

	/**
	 * Headless check of the voice pipeline: ADPCM round trip quality plus audio device availability.
	 */
	private static void voiceSelfTest() {
		int samples = com.example.groupchat.Constants.VOICE_SAMPLE_RATE;
		short[] tone = new short[samples];
		for (int i = 0; i < samples; i++) {
			tone[i] = (short) (Math.sin(2 * Math.PI * 440 * i / (double) samples) * 12000);
		}
		short[] decoded = new short[samples];
		int frames = samples / com.example.groupchat.Constants.VOICE_FRAME_SAMPLES;
		for (int frame = 0; frame < frames; frame++) {
			short[] slice = new short[com.example.groupchat.Constants.VOICE_FRAME_SAMPLES];
			System.arraycopy(tone, frame * slice.length, slice, 0, slice.length);
			byte[] encoded = com.example.groupchat.client.voice.Adpcm.encode(slice, slice.length);
			short[] out = com.example.groupchat.client.voice.Adpcm.decode(encoded, slice.length);
			System.arraycopy(out, 0, decoded, frame * slice.length, slice.length);
		}
		double signal = 0;
		double noise = 0;
		for (int i = 0; i < samples; i++) {
			signal += (double) tone[i] * tone[i];
			double diff = tone[i] - decoded[i];
			noise += diff * diff;
		}
		double snr = 10 * Math.log10(signal / Math.max(1e-9, noise));
		log(String.format("voice: ADPCM round trip frame=%d bytes, SNR=%.1f dB", 
				com.example.groupchat.Constants.VOICE_FRAME_BYTES, snr));

		javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(
				com.example.groupchat.Constants.VOICE_SAMPLE_RATE, 16, 1, true, false);
		boolean mic = javax.sound.sampled.AudioSystem.isLineSupported(
				new javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine.class, format));
		boolean speakers = javax.sound.sampled.AudioSystem.isLineSupported(
				new javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine.class, format));
		log("voice: microphone=" + (mic ? "available" : "MISSING") + " speakers=" + (speakers ? "available" : "MISSING"));
	}

	private static void log(String message) {
		GroupChatClient.LOGGER.info("[groupchat] harness: " + message);
	}

	private static void seedOffline() {
		ClientState state = ClientState.INSTANCE;
		long now = System.currentTimeMillis();

		GroupDto squad = new GroupDto("demo1", "survival squad", "You", List.of(
				new MemberDto("You", true, true),
				new MemberDto("Steve", true, true),
				new MemberDto("Alex", true, false),
				new MemberDto("Herobrine", false, false)), true);
		GroupDto ideas = new GroupDto("demo2", "build ideas", "Alex", List.of(
				new MemberDto("Alex", true, false),
				new MemberDto("You", true, false)), false);

		state.applySnapshot(new Snapshot("You", List.of(squad, ideas),
				List.of(new InviteDto("demo3", "redstone lab", "Steve"))));
		state.setActiveGroup("demo1");

		state.applyHistory("demo1", List.of(
				new MessageDto("d1", "demo1", "Steve", MessageDto.KIND_TEXT,
						"did you see the new base? i moved storage downstairs", "", now - 900_000L),
				new MessageDto("d2", "demo1", "You", MessageDto.KIND_TEXT,
						"yeah, the shulker wall looks great", "", now - 840_000L),
				new MessageDto("d3", "demo1", "Steve", MessageDto.KIND_LOCATION,
						"minecraft:overworld|412.0|71.0|-238.5", "", now - 720_000L),
				new MessageDto("d3b", "demo1", "Alex", MessageDto.KIND_STICKER, "creeper", "", now - 600_000L),
				new MessageDto("d4", "demo1", "Steve", MessageDto.KIND_IMAGE, "", DEMO_IMAGE_ID, now - 300_000L),
				new MessageDto("d5", "demo1", "", MessageDto.KIND_SYSTEM, "Herobrine left the group", "", now - 90_000L)));
		state.addMessage(new MessageDto("d6", "demo1", "Steve", MessageDto.KIND_TEXT,
				"voice call is up if you want to talk", "", now));

		byte[] demo = demoImage();
		if (demo != null) {
			GroupChatClient.INSTANCE.textures.accept(DEMO_IMAGE_ID, demo);
		}

		// a waypoint as if the player had clicked Steve's shared coordinates
		com.example.groupchat.client.waypoint.WaypointManager.INSTANCE.add(
				new com.example.groupchat.client.waypoint.SharedLocation("minecraft:overworld", 412.0D, 71.0D, -238.5D),
				"Steve's spot · #survival squad", 0xFF89DDFF);
		com.example.groupchat.client.waypoint.WaypointManager.INSTANCE.add(
				new com.example.groupchat.client.waypoint.SharedLocation("minecraft:overworld", -180.0D, 64.0D, 320.0D),
				"Alex's spot", 0xFFF7C873);
	}

	private static byte[] demoImage() {
		try {
			BufferedImage image = new BufferedImage(480, 270, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = image.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setPaint(new GradientPaint(0, 0, new Color(0x2B, 0x22, 0x55), 480, 270, new Color(0x7C, 0x6C, 0xF5)));
			graphics.fillRect(0, 0, 480, 270);
			graphics.setColor(new Color(255, 255, 255, 235));
			graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 30));
			graphics.drawString("sent over the network", 34, 120);
			graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 16));
			graphics.drawString("480x270 PNG relayed by the server", 34, 152);
			graphics.setColor(new Color(0, 255, 0));
			graphics.setStroke(new java.awt.BasicStroke(6f));
			graphics.drawRect(4, 4, 471, 261);
			graphics.dispose();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (Exception exception) {
			GroupChatClient.LOGGER.warn("[groupchat] could not build demo image", exception);
			return null;
		}
	}

	private static void shot(Minecraft client, int index) {
		final int number = index;
		Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
			try {
				File file = new File(client.gameDirectory, "groupchat-dev-" + number + ".png");
				image.writeToFile(file);
				log("screenshot written to " + file.getAbsolutePath());
			} catch (Exception exception) {
				GroupChatClient.LOGGER.warn("[groupchat] dev screenshot failed", exception);
			} finally {
				image.close();
			}
		});
	}
}
