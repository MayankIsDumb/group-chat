package com.example.groupchat.client.dev;

import com.example.groupchat.Constants;
import com.example.groupchat.client.ClientConfig;
import com.example.groupchat.client.ClientState;
import com.example.groupchat.client.GroupChatConfigScreen;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.client.media.GifCache;
import com.example.groupchat.client.media.GifTexture;
import com.example.groupchat.client.media.TenorClient;
import com.example.groupchat.client.ui.GifPickerScreen;
import com.example.groupchat.client.ui.HubScreen;
import com.example.groupchat.client.ui.ImagePickerScreen;
import com.example.groupchat.client.ui.StickerPickerScreen;
import com.example.groupchat.client.ui.WaypointsScreen;
import com.example.groupchat.client.waypoint.SharedLocation;
import com.example.groupchat.client.waypoint.WaypointManager;
import com.example.groupchat.net.Payloads;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Two-agent end-to-end feature sweep.
 *
 * <p>Unlike {@link AgentScenario} (which demonstrates one happy path), this walks the whole
 * feature matrix and asserts on what each client can actually observe: relayed messages, group
 * membership, invite state, unread counters, waypoint lists, voice call state,
 * the image access-control rule and the server-side input clamps.
 *
 * <p>The two agents synchronise through chat. Markers travel over {@code /gchat msg}, which posts
 * straight to the group and is <em>not</em> subject to the 40-messages-per-10-seconds rate limit,
 * so the markers cannot disturb the rate-limit test. Real feature traffic uses the normal
 * payload channel.
 *
 * <p>Enabled with {@code -Dgroupchat.devtools.agent=sweep-a|sweep-b} plus
 * {@code -Dgroupchat.devtools.connect=host:port}. Every assertion is logged exactly once as
 * {@code PASS <name>} or {@code FAIL <name>: <reason>} so a run can be graded from the log alone.
 */
public final class FeatureSweep {
	private static final String ADDRESS = System.getProperty("groupchat.devtools.connect", "").trim();
	/** 20 ticks = 1 second. */
	private static final int TIMEOUT = 1800;
	/** Marker prefix; every marker is sent through {@code /gchat msg}. */
	private static final String TAG = "[sw]";

	/** The marker-driven feature steps, run in order by both agents. */
	private static final List<String> STEPS = List.of(
			"sticker",
			"sanitize",
			"loc",
			"cmd",
			"textlen",
			"call_join",
			"image",
			"gif",
			"call_hangup",
			"autoapprove");

	/**
	 * The single group the lifecycle operations share.
	 *
	 * <p>The server only allows a few group creations per minute, so the operations reuse one
	 * group and change its membership instead of creating a new group for every case.
	 */
	private static final String OP_GROUP = "optest";

	/** The scripted group-lifecycle operations, run after the steps. */
	private static final List<String> OPS = List.of(
			"create", "unread", "kick", "decline", "acl", "leave", "ratelimit");

	private final String role;
	private final String peer;
	private final boolean isA;

	private int phase;
	private int ticks;
	private String selfName;
	private String groupId;

	// step bookkeeping
	private int stepIndex;
	private int waitTicks;
	private boolean syncSent;
	private boolean readySent;
	private boolean acted;
	private boolean prepared;
	private boolean optionsKeyPressed;
	private int waypointsBefore;
	private int unreadBefore;
	private String pendingImageId;
	private int gifSource = -1;

	// lifecycle bookkeeping
	private int opIndex;
	private int opState;
	private String opGroupId;
	private String aclImageId;
	private int aclAbsentTicks;
	private int aclPending;
	private int burstLast;
	private int burstStable;
	/** The GIF picker, kept so the screenshot phase can drive it. */
	private GifPickerScreen picker;

	/** Assertion results, keyed by name; a name is only ever reported once. */
	private final Map<String, Boolean> results = new LinkedHashMap<>();
	private int passed;
	private int failed;

	private FeatureSweep(String role) {
		this.role = role;
		this.isA = role.equals("sweep-a");
		this.peer = this.isA ? "AgentB" : "AgentA";
	}

	public static void init(String role) {
		FeatureSweep sweep = new FeatureSweep(role);
		ClientTickEvents.END_CLIENT_TICK.register(sweep::tick);
		GroupChatClient.LOGGER.warn("[groupchat] feature sweep '{}' (peer '{}')", role, sweep.peer);
	}

	// ------------------------------------------------------------------- driver

	private void tick(Minecraft client) {
		if (this.phase == 0) {
			if (client.screen instanceof net.minecraft.client.gui.screens.TitleScreen
					&& client.getOverlay() == null && this.ticks++ > 10) {
				this.log("connecting to " + ADDRESS);
				net.minecraft.client.multiplayer.ServerData data = new net.minecraft.client.multiplayer.ServerData(
						"sweep", ADDRESS, net.minecraft.client.multiplayer.ServerData.Type.OTHER);
				net.minecraft.client.gui.screens.ConnectScreen.startConnecting(null, client,
						net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(ADDRESS), data, false, null);
				this.phase = 1;
				this.ticks = 0;
			}
			return;
		}
		if (this.phase == 1) {
			if (client.player != null && client.level != null) {
				this.selfName = client.player.getGameProfile().name();
				this.log("joined as " + this.selfName);
				this.phase = 2;
				this.ticks = 0;
			} else if (this.ticks++ > 900) {
				this.fail("connect", "could not join the server");
				client.stop();
			}
			return;
		}
		if (client.player == null || client.level == null) {
			return;
		}
		// keep the world rendering even when the window is in the background
		if (client.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
			client.setScreen(null);
		}
		if (client.player.isDeadOrDying()) {
			client.player.respawn();
			return;
		}
		this.ticks++;

		switch (this.phase) {
			case 2 -> this.phaseSetup(client);
			case 3 -> this.phaseSteps(client);
			case 4 -> this.phaseOps(client);
			case 5 -> this.phaseScreenshots(client);
			case 6 -> {
				this.log("sweep finished: " + this.passed + " passed, " + this.failed + " failed");
				client.stop();
			}
			default -> {
			}
		}
	}

	// -------------------------------------------------------------------- setup

	private void phaseSetup(Minecraft client) {
		if (this.isA) {
			if (this.online(this.peer)) {
				this.log(this.peer + " is online, creating the sweep group");
				ClientPlayNetworking.send(new Payloads.CreateGroup("sweep", List.of(this.peer)));
				this.phase = 3;
				this.ticks = 0;
			} else if (this.ticks > 3000) {
				this.fail("setup", this.peer + " never showed up");
				client.stop();
			}
			return;
		}
		// agent B waits for the invite
		if (!ClientState.INSTANCE.invites().isEmpty()) {
			Payloads.InviteDto invite = ClientState.INSTANCE.invites().get(0);
			this.groupId = invite.groupId();
			this.log("accepting invite to " + invite.groupName());
			ClientPlayNetworking.send(new Payloads.RespondInvite(this.groupId, true));
			this.phase = 3;
			this.ticks = 0;
		} else if (this.ticks > 1500) {
			this.fail("setup", "no invite received");
			client.stop();
		}
	}

	// -------------------------------------------------------------- marker steps

	private void phaseSteps(Minecraft client) {
		if (this.groupId == null) {
			Payloads.GroupDto group = this.findGroup("sweep");
			if (group == null) {
				if (this.ticks > 400) {
					this.fail("setup", "the sweep group never appeared");
					client.stop();
				}
				return;
			}
			this.groupId = group.id();
			this.log("group id = " + this.groupId);
			if (this.isA) {
				// the freshly minted id must be exactly 8 base-36 characters
				this.check("group_id_format", this.groupId.matches("[0-9a-z]{8}"),
						"id '" + this.groupId + "' is not 8 base-36 characters");
			}
			this.phase = 3;
			this.ticks = 0;
			return;
		}

		String step = STEPS.get(this.stepIndex);
		if (this.isA) {
			this.stepA(client, step);
		} else {
			this.stepB(client, step);
		}
	}

	/** Agent A drives the order: sync -> wait ready -> act -> verify -> wait done. */
	private void stepA(Minecraft client, String step) {
		if (!this.syncSent) {
			this.syncSent = true;
			this.marker("sync:" + this.stepIndex);
			return;
		}
		if (!this.sawMarker("ready:" + this.stepIndex)) {
			if (this.waitTicks++ > TIMEOUT) {
				this.fail("step_" + step, "agent B never became ready");
				this.nextStep();
			}
			return;
		}
		if (!this.acted) {
			this.acted = true;
			this.actA(client, step);
			return;
		}
		if (!this.verifyA(client, step)) {
			if (this.waitTicks++ > TIMEOUT) {
				this.fail("step_" + step, "the agent A side never completed");
				this.nextStep();
			}
			return;
		}
		if (!this.sawMarker("done:" + this.stepIndex)) {
			if (this.waitTicks++ > TIMEOUT) {
				this.fail("step_" + step, "agent B never confirmed");
				this.nextStep();
			}
			return;
		}
		this.nextStep();
	}

	/** Agent B answers the markers and verifies the outcome. */
	private void stepB(Minecraft client, String step) {
		if (!this.sawMarker("sync:" + this.stepIndex)) {
			if (this.waitTicks++ > TIMEOUT) {
				this.fail("step_" + step, "agent A never started the step");
				this.nextStep();
			}
			return;
		}
		if (!this.prepared) {
			this.prepared = true;
			this.prepareB(client, step);
		}
		if (!this.readySent) {
			this.readySent = true;
			this.marker("ready:" + this.stepIndex);
			this.waitTicks = 0;
			return;
		}
		if (!this.verifyB(client, step)) {
			if (this.waitTicks++ > TIMEOUT) {
				this.fail("step_" + step, "verification timed out");
				this.marker("done:" + this.stepIndex);
				this.nextStep();
			}
			return;
		}
		this.marker("done:" + this.stepIndex);
		this.nextStep();
	}

	private void nextStep() {
		this.stepIndex++;
		this.waitTicks = 0;
		this.syncSent = false;
		this.readySent = false;
		this.acted = false;
		this.prepared = false;
		this.waypointsBefore = 0;
		this.unreadBefore = 0;
		this.pendingImageId = null;
		if (this.stepIndex >= STEPS.size()) {
			this.phase = 4;
			this.opIndex = 0;
			this.opState = 0;
		}
	}

	// ------------------------------------------------------------ step actions

	/**
	 * Agent B runs this once per step, after the sync marker but before it reports ready. It is
	 * where a step's preconditions have to be put in place (config flags, call membership).
	 */
	private void prepareB(Minecraft client, String step) {
		switch (step) {
			case "call_join" -> {
				// join as well, so agent A can see us in the call
				ClientPlayNetworking.send(new Payloads.CallJoin(this.groupId, true));
				GroupChatClient.INSTANCE.voice.join(this.groupId);
				this.log("joined the call");
			}
			case "call_hangup" -> {
				// leave the call as well, so the group really goes idle
				ClientPlayNetworking.send(new Payloads.CallJoin(this.groupId, false));
				GroupChatClient.INSTANCE.voice.leave();
				this.log("left the call");
			}
			case "autoapprove" -> {
				ClientConfig.get().setAutoApproveWaypoints(true);
				this.waypointsBefore = WaypointManager.INSTANCE.all().size();
				this.log("enabled auto-approve waypoints (baseline " + this.waypointsBefore + ")");
			}
			default -> {
			}
		}
	}

	private void actA(Minecraft client, String step) {
		switch (step) {
			case "sticker" -> {
				ClientPlayNetworking.send(new Payloads.SendSticker(this.groupId, "smile"));
				this.log("sent sticker 'smile'");
			}
			case "sanitize" -> {
				// the server must strip characters it does not allow out of a sticker id
				ClientPlayNetworking.send(new Payloads.SendSticker(this.groupId, "bad id!!"));
				// and must fall back to the overworld for an invalid dimension
				SharedLocation here = SharedLocation.of(client.player);
				ClientPlayNetworking.send(new Payloads.SendLocation(this.groupId, "BAD DIMENSION!", here.x(),
						here.y(), here.z()));
				this.log("sent a sticker and a location with invalid input");
			}
			case "loc" -> {
				SharedLocation here = SharedLocation.of(client.player);
				ClientPlayNetworking.send(new Payloads.SendLocation(this.groupId, here.dimension(), here.x(),
						here.y(), here.z()));
				this.log("shared coordinates " + here.pretty());
			}
			case "cmd" -> {
				client.player.connection.sendCommand("gchat msg " + this.groupId + " cmd-hello-from-command");
				this.log("ran /gchat msg");
			}
			case "textlen" -> {
				// exactly 900 characters is the documented maximum and must survive intact
				StringBuilder builder = new StringBuilder();
				while (builder.length() < 900) {
					builder.append("0123456789");
				}
				ClientPlayNetworking.send(new Payloads.SendText(this.groupId, builder.toString()));
				this.log("sent a 900 character message");
			}
			case "call_join" -> {
				ClientPlayNetworking.send(new Payloads.CallJoin(this.groupId, true));
				GroupChatClient.INSTANCE.voice.join(this.groupId);
				this.log("joined the call");
			}
			case "image" -> {
				this.uploadScreenshot(client, this.groupId, "sweep_shot");
			}
			case "gif" -> {
				this.sendGif(client);
			}
			case "call_hangup" -> {
				ClientPlayNetworking.send(new Payloads.CallJoin(this.groupId, false));
				GroupChatClient.INSTANCE.voice.leave();
				this.log("left the call");
			}
			case "autoapprove" -> {
				SharedLocation here = SharedLocation.of(client.player);
				ClientPlayNetworking.send(new Payloads.SendLocation(this.groupId, here.dimension(),
						here.x() + 250.0D, here.y(), here.z() + 250.0D));
				this.log("sent a second location for auto-approve");
			}
			default -> {
			}
		}
	}

	// -------------------------------------------------------- step verification

	/** Agent B's view of the step. @return {@code true} once the expectation is confirmed */
	private boolean verifyB(Minecraft client, String step) {
		switch (step) {
			case "sticker" -> {
				Payloads.MessageDto sticker = this.find(this.groupId,
						message -> message.kind() == Payloads.MessageDto.KIND_STICKER
								&& message.sender().equals(this.peer));
				if (sticker == null) {
					return false;
				}
				return this.check("step_sticker", "smile".equals(sticker.text()),
						"the sticker id was '" + sticker.text() + "'");
			}
			case "sanitize" -> {
				Payloads.MessageDto sticker = this.find(this.groupId,
						message -> message.kind() == Payloads.MessageDto.KIND_STICKER
								&& message.sender().equals(this.peer)
								&& message.text().equals("badid"));
				if (sticker == null) {
					return false;
				}
				this.check("step_sticker_sanitised", true, "");
				Payloads.MessageDto location = this.find(this.groupId,
						message -> message.kind() == Payloads.MessageDto.KIND_LOCATION
								&& message.sender().equals(this.peer));
				if (location == null) {
					return false;
				}
				SharedLocation parsed = SharedLocation.parse(location.text());
				if (parsed == null) {
					return this.check("step_dimension_fallback", false,
							"could not parse '" + location.text() + "'");
				}
				return this.check("step_dimension_fallback",
						"minecraft:overworld".equals(parsed.dimension()),
						"the dimension was '" + parsed.dimension() + "'");
			}
			case "loc" -> {
				Payloads.MessageDto location = this.find(this.groupId,
						message -> message.kind() == Payloads.MessageDto.KIND_LOCATION
								&& message.sender().equals(this.peer)
								&& message.text().startsWith("minecraft:"));
				if (location == null) {
					return false;
				}
				SharedLocation parsed = SharedLocation.parse(location.text());
				if (!this.check("step_location_parse", parsed != null,
						"could not parse '" + location.text() + "'")) {
					return true;
				}
				WaypointManager.Waypoint waypoint = WaypointManager.INSTANCE.addFromMessage(location, "sweep");
				if (!this.check("step_waypoint_create", waypoint != null, "the waypoint was not created")) {
					return true;
				}
				// reply with our own position so agent A can build a waypoint too
				SharedLocation mine = SharedLocation.of(client.player);
				ClientPlayNetworking.send(new Payloads.SendLocation(this.groupId, mine.dimension(), mine.x(),
						mine.y(), mine.z()));
				return true;
			}
			case "cmd" -> {
				Payloads.MessageDto message = this.find(this.groupId,
						m -> m.kind() == Payloads.MessageDto.KIND_TEXT
								&& m.sender().equals(this.peer)
								&& m.text().equals("cmd-hello-from-command"));
				return message != null && this.check("step_command_msg", true, "");
			}
			case "textlen" -> {
				Payloads.MessageDto message = this.find(this.groupId,
						m -> m.kind() == Payloads.MessageDto.KIND_TEXT
								&& m.sender().equals(this.peer)
								&& m.text().length() == 900);
				return message != null && this.check("step_message_length", true, "");
			}
			case "call_join" -> {
				// The server relays the new call state asynchronously, so this has to be polled
				// across ticks rather than spun on inside one tick.
				Payloads.GroupDto group = ClientState.INSTANCE.group(this.groupId);
				if (group == null || !group.callActive()) {
					return false;
				}
				int inCall = 0;
				boolean peerIn = false;
				for (Payloads.MemberDto member : group.members()) {
					if (member.inCall()) {
						inCall++;
					}
					if (member.name().equals(this.peer) && member.inCall()) {
						peerIn = true;
					}
				}
				if (!peerIn || inCall < 2) {
					return false;
				}
				this.check("step_call_active", true, "");
				this.check("step_call_member_flag", true, "");
				this.check("step_call_both_joined", true, "");
				return true;
			}
			case "image" -> {
				if (this.pendingImageId == null) {
					Payloads.MessageDto image = this.find(this.groupId,
							m -> m.kind() == Payloads.MessageDto.KIND_IMAGE && m.sender().equals(this.peer));
					if (image == null) {
						return false;
					}
					this.pendingImageId = image.imageId();
					// open the hub so the texture is actually requested and drawn
					client.setScreen(new HubScreen(null));
				}
				if (GroupChatClient.INSTANCE.textures.get(this.pendingImageId) != null) {
					return this.check("step_image_download", true, "");
				}
				return false;
			}
			case "gif" -> {
				if (this.pendingImageId == null) {
					Payloads.MessageDto gif = this.find(this.groupId,
							m -> m.kind() == Payloads.MessageDto.KIND_GIF && m.sender().equals(this.peer));
					if (gif == null) {
						return false;
					}
					this.pendingImageId = gif.imageId();
					this.log("saw a GIF from the peer: " + gif.imageId());
					client.setScreen(new HubScreen(null));
				}
				// the GIF must arrive from the server and start animating
				GifTexture texture = GifCache.instance().get(this.pendingImageId);
				if (texture == null) {
					return false;
				}
				this.check("step_gif_received", true, "");
				// remember the first frame we see, then wait for it to move
				if (this.gifSource < 0) {
					this.gifSource = texture.sourceX();
					return false;
				}
				if (texture.sourceX() == this.gifSource) {
					return false;
				}
				return this.check("step_gif_animates", true, "");
			}
			case "call_hangup" -> {
				// the server has to confirm that both of us left, so poll until it does
				Payloads.GroupDto group = ClientState.INSTANCE.group(this.groupId);
				if (group == null) {
					return false;
				}
				if (group.callActive()) {
					return false;
				}
				return this.check("step_call_hangup", true, "");
			}
			case "autoapprove" -> {
				int now = WaypointManager.INSTANCE.all().size();
				if (now <= this.waypointsBefore) {
					return false;
				}
				return this.check("step_auto_approve", true, "");
			}
			default -> {
				return true;
			}
		}
	}

	/** Agent A's own view of the step (only used by steps that need a reply). */
	private boolean verifyA(Minecraft client, String step) {
		switch (step) {
			case "loc" -> {
				Payloads.MessageDto location = this.find(this.groupId,
						message -> message.kind() == Payloads.MessageDto.KIND_LOCATION
								&& message.sender().equals(this.peer));
				if (location == null) {
					return false;
				}
				SharedLocation parsed = SharedLocation.parse(location.text());
				if (!this.check("step_location_parse_b", parsed != null,
						"could not parse '" + location.text() + "'")) {
					return true;
				}
				WaypointManager.Waypoint waypoint = WaypointManager.INSTANCE.addFromMessage(location, "sweep");
				return this.check("step_waypoint_create_b", waypoint != null,
						"the waypoint was not created");
			}
			default -> {
				return true;
			}
		}
	}

	// ------------------------------------------------------- group lifecycle ops

	private void phaseOps(Minecraft client) {
		if (this.opIndex >= OPS.size()) {
			this.phase = 5;
			this.ticks = 0;
			return;
		}
		// an operation that never finishes must not stall the whole sweep
		if (this.ticks > TIMEOUT) {
			this.fail("op_" + OPS.get(this.opIndex), "the operation timed out");
			this.nextOp();
			return;
		}
		switch (OPS.get(this.opIndex)) {
			case "create" -> this.opCreate(client);
			case "unread" -> this.opUnread(client);
			case "kick" -> this.opKick(client);
			case "decline" -> this.opDecline(client);
			case "acl" -> this.opAcl(client);
			case "leave" -> this.opLeave(client);
			case "ratelimit" -> this.opRateLimit(client);
			default -> {
			}
		}
	}

	/**
	 * Creates the group the lifecycle operations share and brings the peer into it.
	 *
	 * <p>All the operations reuse this one group, because the server only allows a few group
	 * creations per minute and the sweep needs more memberships than that.
	 */
	private void opCreate(Minecraft client) {
		if (this.isA) {
			switch (this.opState) {
				case 0 -> {
					ClientPlayNetworking.send(new Payloads.CreateGroup(OP_GROUP, List.of(this.peer)));
					this.marker("create:created");
					this.opState = 1;
				}
				case 1 -> {
					if (!this.sawMarker("create:accepted")) {
						return;
					}
					Payloads.GroupDto group = this.findGroup(OP_GROUP);
					if (group == null) {
						this.fail("create", "the operation group never appeared");
						this.nextOp();
						return;
					}
					this.opGroupId = group.id();
					this.marker("create:ok");
					this.opState = 2;
				}
				case 2 -> {
					if (this.sawMarker("create:done")) {
						this.nextOp();
					}
				}
				default -> {
				}
			}
			return;
		}
		switch (this.opState) {
			case 0 -> {
				if (!this.sawMarker("create:created")) {
					return;
				}
				Payloads.InviteDto invite = this.findInvite(OP_GROUP);
				if (invite == null) {
					return;
				}
				this.opGroupId = invite.groupId();
				ClientPlayNetworking.send(new Payloads.RespondInvite(this.opGroupId, true));
				this.marker("create:accepted");
				this.opState = 1;
			}
			case 1 -> {
				if (!this.sawMarker("create:ok")) {
					return;
				}
				Payloads.GroupDto group = this.findGroup(OP_GROUP);
				boolean joined = group != null && group.members().stream()
						.anyMatch(entry -> entry.name().equals(this.peer));
				this.check("feature_group_created", joined, "the peer never joined the operation group");
				this.marker("create:done");
				this.nextOp();
			}
			default -> {
			}
		}
	}

	/**
	 * Unread badges: agent B watches the sweep group, agent A sends into the operation group, so
	 * only that second group may show an unread badge.
	 */
	private void opUnread(Minecraft client) {
		if (this.isA) {
			switch (this.opState) {
				case 0 -> {
					for (int i = 0; i < 3; i++) {
						ClientPlayNetworking.send(new Payloads.SendText(this.opGroupId, "unread-" + i));
					}
					this.marker("unread:sent");
					this.opState = 1;
				}
				case 1 -> {
					if (this.sawMarker("unread:done")) {
						this.nextOp();
					}
				}
				default -> {
				}
			}
			return;
		}
		switch (this.opState) {
			case 0 -> {
				// watch the sweep group, so only the other group shows a badge
				ClientState.INSTANCE.setActiveGroup(this.groupId);
				this.unreadBefore = ClientState.INSTANCE.unread(this.groupId);
				this.marker("unread:ready");
				this.opState = 1;
			}
			case 1 -> {
				if (!this.sawMarker("unread:sent")) {
					return;
				}
				Payloads.GroupDto group = ClientState.INSTANCE.group(this.opGroupId);
				if (group == null) {
					return;
				}
				int unread = ClientState.INSTANCE.unread(group.id());
				if (unread < 3) {
					return;
				}
				this.check("feature_unread_badge", unread >= 3, "the unread counter was " + unread);
				this.check("feature_unread_scoped", ClientState.INSTANCE.unread(this.groupId) == this.unreadBefore,
						"the watched group also showed an unread badge");
				this.marker("unread:done");
				this.nextOp();
			}
			default -> {
			}
		}
	}

	/** The owner removes a member from the operation group. */
	private void opKick(Minecraft client) {
		if (this.isA) {
			switch (this.opState) {
				case 0 -> {
					ClientPlayNetworking.send(new Payloads.KickMember(this.opGroupId, this.peer));
					this.marker("kick:kicked");
					this.opState = 1;
				}
				case 1 -> {
					if (this.sawMarker("kick:done")) {
						this.nextOp();
					}
				}
				default -> {
				}
			}
			return;
		}
		switch (this.opState) {
			case 0 -> {
				if (!this.sawMarker("kick:kicked")) {
					return;
				}
				// the group must vanish from the removed member's list
				if (this.findGroup(OP_GROUP) != null) {
					return;
				}
				this.check("feature_kick_member", true, "");
				this.marker("kick:done");
				this.nextOp();
			}
			default -> {
			}
		}
	}

	/** Declining an invite must not make you a member. */
	private void opDecline(Minecraft client) {
		if (this.isA) {
			switch (this.opState) {
				case 0 -> {
					// the peer was kicked out, so the group can be invited to again
					ClientPlayNetworking.send(new Payloads.InvitePlayer(this.opGroupId, this.peer));
					this.marker("decline:invited");
					this.opState = 1;
				}
				case 1 -> {
					if (!this.sawMarker("decline:declined")) {
						return;
					}
					Payloads.GroupDto group = this.findGroup(OP_GROUP);
					boolean stillMember = group != null && group.members().stream()
							.anyMatch(entry -> entry.name().equals(this.peer));
					this.check("feature_decline_invite", !stillMember,
							"the declined invitee is still a member");
					this.marker("decline:done");
					this.opState = 2;
				}
				case 2 -> {
					if (this.sawMarker("decline:ok")) {
						this.nextOp();
					}
				}
				default -> {
				}
			}
			return;
		}
		switch (this.opState) {
			case 0 -> {
				if (!this.sawMarker("decline:invited")) {
					return;
				}
				Payloads.InviteDto invite = this.findInvite(OP_GROUP);
				if (invite == null) {
					return;
				}
				ClientPlayNetworking.send(new Payloads.RespondInvite(invite.groupId(), false));
				this.marker("decline:declined");
				this.opState = 1;
			}
			case 1 -> {
				if (!this.sawMarker("decline:done")) {
					return;
				}
				boolean inviteGone = ClientState.INSTANCE.invites().stream()
						.noneMatch(invite -> invite.groupName().equals(OP_GROUP));
				boolean notAMember = this.findGroup(OP_GROUP) == null;
				this.check("feature_decline_state", inviteGone && notAMember,
						"the invite or the membership survived the decline");
				this.marker("decline:ok");
				this.nextOp();
			}
			default -> {
			}
		}
	}

	/** Leaving the last member deletes the group. */
	private void opLeave(Minecraft client) {
		if (this.isA) {
			switch (this.opState) {
				case 0 -> {
					// the peer left during the access-control check, so invite them back
					ClientPlayNetworking.send(new Payloads.InvitePlayer(this.opGroupId, this.peer));
					this.marker("leave:invited");
					this.opState = 1;
				}
				case 1 -> {
					if (!this.sawMarker("leave:accepted")) {
						return;
					}
					this.marker("leave:now");
					this.opState = 2;
				}
				case 2 -> {
					if (!this.sawMarker("leave:left")) {
						return;
					}
					// one member is left, so the group must still exist
					this.check("feature_leave_group_survives", this.findGroup(OP_GROUP) != null,
							"the group was deleted while a member was still in it");
					ClientPlayNetworking.send(new Payloads.LeaveGroup(this.opGroupId));
					this.marker("leave:done");
					this.opState = 3;
				}
				case 3 -> {
					if (!this.sawMarker("leave:ok")) {
						return;
					}
					this.check("feature_leave_group", this.findGroup(OP_GROUP) == null,
							"the group survived the last member leaving");
					this.nextOp();
				}
				default -> {
				}
			}
			return;
		}
		switch (this.opState) {
			case 0 -> {
				if (!this.sawMarker("leave:invited")) {
					return;
				}
				Payloads.InviteDto invite = this.findInvite(OP_GROUP);
				if (invite == null) {
					return;
				}
				ClientPlayNetworking.send(new Payloads.RespondInvite(invite.groupId(), true));
				this.marker("leave:accepted");
				this.opState = 1;
			}
			case 1 -> {
				if (!this.sawMarker("leave:now")) {
					return;
				}
				ClientPlayNetworking.send(new Payloads.LeaveGroup(this.opGroupId));
				this.marker("leave:left");
				this.opState = 2;
			}
			case 2 -> {
				if (!this.sawMarker("leave:done")) {
					return;
				}
				this.marker("leave:ok");
				this.nextOp();
			}
			default -> {
			}
		}
	}

	/**
	 * Image access control: a member may download an image, a player who left the owning group
	 * may not. Two distinct image ids make a wrongly served image visible.
	 */
	private void opAcl(Minecraft client) {
		if (this.isA) {
			switch (this.opState) {
				case 0 -> {
					// bring the peer back in, so the member download can be tested
					ClientPlayNetworking.send(new Payloads.InvitePlayer(this.opGroupId, this.peer));
					this.marker("acl:invited");
					this.opState = 1;
				}
				case 1 -> {
					if (!this.sawMarker("acl:accepted")) {
						return;
					}
					// the peer must not ask for the image before it exists on the server
					this.aclPending = 2;
					this.uploadScreenshot(client, this.opGroupId, "acl_visible", this::aclUploaded);
					this.uploadScreenshot(client, this.opGroupId, "acl_secret", this::aclUploaded);
				}
				case 2 -> {
					if (this.sawMarker("acl:done")) {
						this.nextOp();
					}
				}
				default -> {
				}
			}
			return;
		}
		switch (this.opState) {
			case 0 -> {
				if (!this.sawMarker("acl:invited")) {
					return;
				}
				Payloads.InviteDto invite = this.findInvite(OP_GROUP);
				if (invite == null) {
					return;
				}
				this.opGroupId = invite.groupId();
				ClientPlayNetworking.send(new Payloads.RespondInvite(this.opGroupId, true));
				this.marker("acl:accepted");
				this.opState = 1;
			}
			case 1 -> {
				if (!this.sawMarker("acl:uploaded")) {
					return;
				}
				this.aclImageId = "acl_visible";
				this.opState = 2;
			}
			case 2 -> {
				// a member may download the image
				if (GroupChatClient.INSTANCE.textures.get(this.aclImageId) == null) {
					return;
				}
				this.check("feature_image_member_access", true, "");
				// leave the group, then try the other image
				ClientPlayNetworking.send(new Payloads.LeaveGroup(this.opGroupId));
				this.opState = 3;
			}
			case 3 -> {
				if (this.findGroup(OP_GROUP) != null) {
					return;
				}
				ClientPlayNetworking.send(new Payloads.RequestImage("acl_secret"));
				this.aclAbsentTicks = 0;
				this.opState = 4;
			}
			case 4 -> {
				// the server must never deliver the image to a non-member
				if (GroupChatClient.INSTANCE.textures.get("acl_secret") != null) {
					this.fail("feature_image_nonmember_blocked", "the server served an image to a non-member");
					this.marker("acl:done");
					this.nextOp();
					return;
				}
				if (this.aclAbsentTicks++ < 400) {
					return;
				}
				this.check("feature_image_nonmember_blocked", true, "");
				this.marker("acl:done");
				this.nextOp();
			}
			default -> {
			}
		}
	}

	/**
	 * Rate limiting: the server allows 40 text messages per 10 seconds per player, so a burst of
	 * 45 must be cut down.
	 */
	private void opRateLimit(Minecraft client) {
		if (this.isA) {
			if (this.opState == 0) {
				for (int i = 0; i < 45; i++) {
					ClientPlayNetworking.send(new Payloads.SendText(this.groupId, "rl-" + i));
				}
				this.log("sent 45 messages in one burst (the limit is 40 per 10s)");
				this.marker("ratelimit:sent");
				this.opState = 1;
			} else if (this.sawMarker("ratelimit:done")) {
				this.nextOp();
			}
			return;
		}
		if (this.opState == 0) {
			if (!this.sawMarker("ratelimit:sent")) {
				return;
			}
			this.opState = 1;
			return;
		}
		int received = 0;
		for (Payloads.MessageDto message : ClientState.INSTANCE.messages(this.groupId)) {
			if (message.sender().equals(this.peer) && message.text().startsWith("rl-")) {
				received++;
			}
		}
		if (received != this.burstLast) {
			this.burstLast = received;
			this.burstStable = 0;
			return;
		}
		// the count has stopped growing, so the burst is over
		if (this.burstStable++ < 60) {
			return;
		}
		if (received == 0) {
			this.fail("step_rate_limit", "no burst message arrived at all");
		} else {
			this.check("step_rate_limit", received <= 40,
					received + " of 45 burst messages got through, the limiter did not fire");
			this.check("step_rate_limit_partial", received >= 20,
					"only " + received + " of 45 burst messages got through");
		}
		this.marker("ratelimit:done");
		this.nextOp();
	}

	// ------------------------------------------------------------- screenshots

	private void phaseScreenshots(Minecraft client) {
		switch (this.opState) {
			case 0 -> {
				if (this.findGroup("sweep") == null) {
					this.waitFor("setup", "the sweep group vanished");
					return;
				}
				client.setScreen(new HubScreen(null));
				this.opState = 1;
				this.ticks = 0;
			}
			case 1 -> {
				if (this.ticks == 30) {
					this.clearToasts(client);
					DevShots.shot(client, "sweep-chat");
				} else if (this.ticks > 60) {
					this.opState = 2;
					this.ticks = 0;
				}
			}
			case 2 -> {
				if (this.ticks == 30) {
					this.clearToasts(client);
					DevShots.shot(client, "sweep-members");
				} else if (this.ticks > 60) {
					client.setScreen(null);
					this.opState = 3;
					this.ticks = 0;
				}
			}
			case 3 -> {
				if (this.ticks == 30) {
					this.clearToasts(client);
					DevShots.shot(client, "sweep-waypoints");
				} else if (this.ticks > 60) {
					client.setScreen(null);
					this.opState = 4;
					this.ticks = 0;
				}
			}
			case 4 -> {
				if (this.ticks == 30) {
					this.clearToasts(client);
					DevShots.shot(client, "sweep-stickers");
				} else if (this.ticks > 60) {
					client.setScreen(null);
					this.opState = 5;
					this.ticks = 0;
				}
			}
			case 5 -> {
				if (this.ticks == 30) {
					this.clearToasts(client);
					DevShots.shot(client, "sweep-images");
				} else if (this.ticks > 60) {
					client.setScreen(null);
					this.opState = 6;
					this.ticks = 0;
				}
			}
			case 8 -> {
				// the GIF picker, with a search already run
				if (this.ticks == 20) {
					this.clearToasts(client);
					client.setScreen(new GifPickerScreen(null, selection -> {
					}));
					this.picker = (GifPickerScreen) client.screen;
					this.picker.devSearch("celebration");
				} else if (this.ticks > 40 && this.picker != null && this.picker.devPreviewsLoaded()) {
					this.clearToasts(client);
					DevShots.shot(client, "sweep-gif-picker");
					client.setScreen(null);
					this.opState = 9;
					this.ticks = 0;
				} else if (this.ticks > 600) {
					this.fail("feature_gif_picker", "the GIF previews never loaded");
					client.setScreen(null);
					this.opState = 9;
					this.ticks = 0;
				}
			}
			case 6 -> {
				// open the settings screen with the settings key, so the symbol-based rows can be
				// checked and the key binding itself is covered too
				if (!this.optionsKeyPressed) {
					this.optionsKeyPressed = true;
					KeyMapping mapping = KeyMapping.get("key.groupchat.options");
					if (mapping == null) {
						this.fail("step_options_key", "the settings key is not registered");
					} else {
						KeyMapping.click(mapping.getDefaultKey());
						this.log("pressed the settings key");
					}
				}
				if (!(client.screen instanceof GroupChatConfigScreen)) {
					if (this.ticks > 40) {
						this.fail("step_options_key", "the settings key did not open the settings");
						client.setScreen(null);
						this.opState = 7;
						this.ticks = 0;
						return;
					}
					return;
				}
				this.check("step_options_key", true, "the settings key opened the settings screen");
				if (this.ticks == 30) {
					this.clearToasts(client);
					DevShots.shot(client, "sweep-config");
				} else if (this.ticks > 60) {
					client.setScreen(null);
					this.opState = 7;
					this.ticks = 0;
				}
			}
			case 7 -> {
				// stand next to a waypoint so the in-world marker and name tag are visible.
				// A dedicated waypoint is placed here: the ones from the chat steps may already
				// have been cleared by the "you arrived" rule.
				WaypointManager.Waypoint target = this.renderWaypoint(client);
				if (target == null) {
					this.fail("feature_waypoint_render", "no waypoint to render");
					this.opState = 8;
					this.ticks = 0;
					return;
				}
				if (this.ticks == 20) {
					client.player.connection.sendCommand("tp " + this.selfName + " "
							+ Math.round(target.x() + 10) + " " + Math.round(target.y()) + " "
							+ Math.round(target.z() + 10));
				}
				if (this.ticks == 60) {
					double dx = target.x() - client.player.getX();
					double dy = target.y() - client.player.getY();
					double dz = target.z() - client.player.getZ();
					client.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
					client.player.setXRot((float) (-Math.toDegrees(Math.atan2(dy + 1.0D,
							Math.sqrt(dx * dx + dz * dz)))));
				}
				if (this.ticks > 90) {
					this.clearToasts(client);
					DevShots.shot(client, "sweep-waypoint-world");
					this.check("feature_waypoint_render", true, "");
					this.opState = 8;
					this.ticks = 0;
				}
			}
			case 9 -> {
				this.phase = 6;
				this.ticks = 0;
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	private void nextOp() {
		this.opIndex++;
		this.opState = 0;
		this.ticks = 0;
	}

	private void waitFor(String name, String reason) {
		if (this.ticks > TIMEOUT) {
			this.fail(name, reason);
			this.ticks = 0;
			this.opState++;
		}
	}

	/** Sends a marker through {@code /gchat msg}, which bypasses the message rate limit. */
	private void marker(String suffix) {
		Minecraft client = Minecraft.getInstance();
		if (this.groupId == null || client.player == null) {
			return;
		}
		client.player.connection.sendCommand("gchat msg " + this.groupId + " " + TAG + suffix);
	}

	private boolean sawMarker(String suffix) {
		if (this.groupId == null) {
			return false;
		}
		String wanted = TAG + suffix;
		for (Payloads.MessageDto message : ClientState.INSTANCE.messages(this.groupId)) {
			if (wanted.equals(message.text())) {
				return true;
			}
		}
		return false;
	}

	private Payloads.MessageDto find(String groupId, Predicate<Payloads.MessageDto> predicate) {
		if (groupId == null) {
			return null;
		}
		for (Payloads.MessageDto message : ClientState.INSTANCE.messages(groupId)) {
			if (predicate.test(message)) {
				return message;
			}
		}
		return null;
	}

	private Payloads.GroupDto findGroup(String name) {
		for (Payloads.GroupDto group : ClientState.INSTANCE.groups()) {
			if (group.name().equals(name)) {
				return group;
			}
		}
		return null;
	}

	private Payloads.InviteDto findInvite(String groupName) {
		for (Payloads.InviteDto invite : ClientState.INSTANCE.invites()) {
			if (invite.groupName().equals(groupName)) {
				return invite;
			}
		}
		return null;
	}

	private boolean online(String name) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return false;
		}
		for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
			if (info.getProfile().name().equals(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Searches Tenor for a GIF and uploads the first result, so the peer can check that animated
	 * GIFs are relayed and play.
	 */
	private void sendGif(Minecraft client) {
		net.minecraft.util.Util.backgroundExecutor().execute(() -> {
			try {
				List<TenorClient.Gif> results = TenorClient.search(
						ClientConfig.get().gifApiHost(), ClientConfig.get().gifApiKey(), "celebration");
				this.log("GIF search returned " + results.size() + " results");
				if (results.isEmpty()) {
					return;
				}
				byte[] data = TenorClient.download(results.get(0).smallGifUrl(), Constants.MAX_GIF_BYTES);
				this.log("downloaded GIF (" + data.length + " bytes)");
				client.execute(() -> {
					String gifId = Long.toString(System.nanoTime(), 36);
					ClientPlayNetworking.send(new Payloads.UploadGif(this.groupId, gifId, data));
					this.log("uploaded GIF as " + gifId);
				});
			} catch (Exception exception) {
				this.log("GIF send failed: " + exception);
			}
		});
	}

	/** Captures the current frame, stores it on disk and sends it to the group as an image. */
	private void uploadScreenshot(Minecraft client, String groupId, String imageId) {
		this.uploadScreenshot(client, groupId, imageId, null);
	}

	/**
	 * Captures the current frame and sends it to the group as an image.
	 *
	 * <p>Taking a screenshot and reading the file back is asynchronous, so {@code done} is called
	 * once the image has actually been handed to the server. Without that the peer could ask for
	 * the image before it exists.
	 */
	private void uploadScreenshot(Minecraft client, String groupId, String imageId, Runnable done) {
		if (groupId == null) {
			if (done != null) {
				done.run();
			}
			return;
		}
		Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
			try {
				File file = new File(client.gameDirectory, imageId + ".png");
				image.writeToFile(file);
				byte[] png = Files.readAllBytes(file.toPath());
				client.execute(() -> {
					GroupChatClient.INSTANCE.textures.accept(imageId, png);
					ClientPlayNetworking.send(new Payloads.UploadImage(groupId, imageId, png));
					this.log("uploaded " + imageId + " (" + png.length + " bytes)");
					if (done != null) {
						done.run();
					}
				});
			} catch (Exception exception) {
				this.log("screenshot upload failed: " + exception.getMessage());
				if (done != null) {
					done.run();
				}
			} finally {
				image.close();
			}
		});
	}

	/** Called once an access-control image has been uploaded; signals the peer when both are in. */
	private void aclUploaded() {
		if (--this.aclPending > 0) {
			return;
		}
		this.marker("acl:uploaded");
		this.opState = 2;
	}

	/**
	 * Places a waypoint a short distance ahead of the player and returns it.
	 *
	 * <p>The waypoint belongs to this player, so the "you have arrived" rule leaves it alone and
	 * it is still there when the screenshot is taken.
	 */
	private WaypointManager.Waypoint renderWaypoint(Minecraft client) {
		if (client.player == null || client.level == null) {
			return null;
		}
		String dimension = client.level.dimension().identifier().toString();
		SharedLocation spot = new SharedLocation(dimension,
				client.player.getX() + 24.0D, client.player.getY(), client.player.getZ());
		WaypointManager.Waypoint waypoint = WaypointManager.INSTANCE.add(spot, "sweep waypoint", 0xFF8B7BFF,
				this.selfName);
		return waypoint == null ? WaypointManager.INSTANCE.all().stream().findFirst().orElse(null) : waypoint;
	}

	private void clearToasts(Minecraft client) {
		client.getToastManager().clear();
	}

	/**
	 * Reports an assertion exactly once per name.
	 *
	 * <p>A {@code false} result is a final verdict: callers must only check once the condition has
	 * actually been observed. Anything still unproven returns {@code false} without recording
	 * anything, so the assertion can be retried on a later tick.
	 */
	private boolean check(String name, boolean ok, String detail) {
		Boolean known = this.results.get(name);
		if (known != null) {
			return known;
		}
		this.results.put(name, ok);
		if (ok) {
			this.passed++;
			this.log("PASS " + name);
		} else {
			this.failed++;
			this.log("FAIL " + name + ": " + detail);
		}
		return ok;
	}

	/**
	 * Records a failure once the caller has given up waiting.
	 */
	private void fail(String name, String reason) {
		if (this.results.containsKey(name)) {
			return;
		}
		this.results.put(name, false);
		this.failed++;
		this.log("FAIL " + name + ": " + reason);
	}

	private void log(String message) {
		GroupChatClient.LOGGER.info("[groupchat] sweep-{}: {}", this.role, message);
	}
}
