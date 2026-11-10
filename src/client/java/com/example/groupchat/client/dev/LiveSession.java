package com.example.groupchat.client.dev;

import com.example.groupchat.client.ClientState;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.client.waypoint.SharedLocation;
import com.example.groupchat.client.waypoint.WaypointManager;
import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.MessageDto;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Live test session: one scripted "friend" agent plus a human-driven client on the same server.
 *
 * <p>roles: {@code friend} = joins, creates a group, invites the human, shares coordinates and
 * replies to whatever the human sends; {@code human} = only connects, then leaves the controls alone.
 */
public final class LiveSession {
	private static final String ADDRESS = System.getProperty("groupchat.devtools.connect", "").trim();
	private static final String HUMAN = System.getProperty("groupchat.devtools.human", "Human").trim();
	private static final String SELF_HOME = "320 -60 -260";

	private final String role;
	private final Set<String> handled = new HashSet<>();
	private final Deque<String> replies = new ArrayDeque<>();
	private int phase;
	private int ticks;
	private String groupId;
	private String selfName;
	private long lastSendAt;
	private boolean greeted;
	private boolean movingToWaypoint;
	private boolean screenshotDone;

	private LiveSession(String role) {
		this.role = role;
	}

	public static void init(String role) {
		LiveSession session = new LiveSession(role);
		ClientTickEvents.END_CLIENT_TICK.register(session::tick);
		GroupChatClient.LOGGER.warn("[groupchat] live session role '{}' (human='{}', server='{}')", role, HUMAN, ADDRESS);
	}

	private void tick(Minecraft client) {
		if (this.phase == 0) {
			if (client.screen instanceof TitleScreen && client.getOverlay() == null && this.ticks++ > 10) {
				this.log("connecting to " + ADDRESS);
				ServerData data = new ServerData("live", ADDRESS, ServerData.Type.OTHER);
				ConnectScreen.startConnecting(null, client, ServerAddress.parseString(ADDRESS), data, false, null);
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
				this.log("could not join");
				client.stop();
			}
			return;
		}
		if (client.player == null || client.level == null) {
			return;
		}
		if (client.screen instanceof PauseScreen) {
			client.setScreen(null);
		}
		if (client.player.isDeadOrDying()) {
			this.log("died, respawning");
			client.player.respawn();
			return;
		}
		this.ticks++;

		if (this.role.equals("human")) {
			return; // the player is driving
		}
		this.tickFriend(client);
	}

	// ----------------------------------------------------------------- friend

	private void tickFriend(Minecraft client) {
		switch (this.phase) {
			case 2 -> {
				// move to camp first so the shared coordinates are worth something
				if (this.ticks == 20) {
					client.player.connection.sendCommand("tp " + this.selfName + " " + SELF_HOME);
					this.log("moved to my camp at " + SELF_HOME);
					this.phase = 3;
					this.ticks = 0;
				}
			}
			case 3 -> {
				if (!this.atCamp(client) && this.ticks < 120) {
					return;
				}
				if (!ClientState.INSTANCE.groups().isEmpty()) {
					// already invited before (e.g. rejoining the same server)
					this.groupId = ClientState.INSTANCE.groups().get(0).id();
					ClientPlayNetworking.send(new Payloads.InvitePlayer(this.groupId, HUMAN));
					this.log("reusing group " + this.groupId + " and re-inviting " + HUMAN);
					this.phase = 4;
					this.ticks = 0;
				} else if (this.online(HUMAN)) {
					this.log(HUMAN + " is online, creating the group and inviting them");
					ClientPlayNetworking.send(new Payloads.CreateGroup("expedition", List.of(HUMAN)));
					this.groupId = "pending";
					this.phase = 5;
					this.ticks = 0;
				} else if (this.ticks % 400 == 0) {
					this.log("waiting for " + HUMAN + " to join the server...");
				}
			}
			case 5 -> {
				if (!ClientState.INSTANCE.groups().isEmpty()) {
					this.groupId = ClientState.INSTANCE.groups().get(0).id();
					this.log("group ready: " + this.groupId + " (waiting for " + HUMAN + " to accept)");
					this.phase = 4;
					this.ticks = 0;
				}
			}
			case 4 -> {
				boolean humanJoined = this.memberCount() > 1;
				if (!this.greeted && humanJoined) {
					this.greeted = true;
					boolean sharedBefore = ClientState.INSTANCE.messages(this.groupId).stream()
							.anyMatch(message -> message.sender().equals(this.selfName)
									&& message.kind() == MessageDto.KIND_LOCATION);
					this.replies.add(sharedBefore
							? "moved camp - here is the new spot, click it for a waypoint"
							: "yo! group is up. here's where I'm digging, come take a look");
					SharedLocation home = SharedLocation.of(client.player);
					ClientPlayNetworking.send(new Payloads.SendLocation(this.groupId, home.dimension(), home.x(),
							home.y(), home.z()));
					this.log("greeted " + HUMAN + " and shared coords " + home.pretty());
				}
				this.readIncoming();
				this.flush(client);
				this.inspectWaypoint(client);
			}
			default -> {
			}
		}
	}

	/**
	 * Dev-only: after a while the friend walks over to the first waypoint it owns, looks at it and
	 * takes a screenshot, so the in-world marker can be checked without a human recording it.
	 */
	private void inspectWaypoint(Minecraft client) {
		if (this.ticks < 200 || this.screenshotDone) {
			return;
		}
		List<WaypointManager.Waypoint> waypoints = WaypointManager.INSTANCE.all();
		if (waypoints.isEmpty()) {
			return;
		}
		WaypointManager.Waypoint target = waypoints.get(0);
		if (!this.movingToWaypoint) {
			this.movingToWaypoint = true;
			this.log("walking over to " + target.name() + " at " + target.location().pretty());
			client.player.connection.sendCommand("tp " + this.selfName + " "
					+ Math.round(target.x() + 14) + " " + Math.round(target.y()) + " " + Math.round(target.z() + 14));
			return;
		}
		if (this.ticks == 240) {
			// face the marker
			double dx = target.x() - client.player.getX();
			double dy = target.y() - client.player.getY();
			double dz = target.z() - client.player.getZ();
			float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
			float pitch = (float) (-Math.toDegrees(Math.atan2(dy + 1.0D, Math.sqrt(dx * dx + dz * dz))));
			client.player.setYRot(yaw);
			client.player.setXRot(pitch);
			this.log(String.format("looking at the waypoint (yaw %.1f, pitch %.1f)", yaw, pitch));
		} else if (this.ticks > 280) {
			this.screenshotDone = true;
			DevShots.shot(client, "friend-waypoint");
			this.log("screenshot taken");
		}
	}

	private void readIncoming() {
		if (this.groupId == null) {
			return;
		}
		for (MessageDto message : ClientState.INSTANCE.messages(this.groupId)) {
			if (message.sender().equals(this.selfName) || message.kind() == MessageDto.KIND_SYSTEM) {
				continue;
			}
			if (!this.handled.add(message.id())) {
				continue;
			}
			this.log("heard from " + message.sender() + ": kind=" + message.kind());
			switch (message.kind()) {
				case MessageDto.KIND_LOCATION -> {
					SharedLocation location = SharedLocation.parse(message.text());
					GroupDto group = ClientState.INSTANCE.group(this.groupId);
					WaypointManager.Waypoint waypoint = WaypointManager.INSTANCE.addFromMessage(message,
							group == null ? "" : group.name());
					this.replies.add(waypoint == null
							? "already had that spot on my list"
							: "waypoint set at " + (location == null ? "your position" : location.pretty()) + " - heading over");
				}
				case MessageDto.KIND_IMAGE -> this.replies.add("nice screenshot, it came through on my side too");
				case MessageDto.KIND_STICKER -> this.replies.add("haha");
				default -> this.replies.add(this.replyTo(message.text()));
			}
		}
	}

	private String replyTo(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.contains("where")) {
			Minecraft client = Minecraft.getInstance();
			if (client.player != null) {
				SharedLocation here = SharedLocation.of(client.player);
				ClientPlayNetworking.send(new Payloads.SendLocation(this.groupId, here.dimension(), here.x(),
						here.y(), here.z()));
			}
			return "sending my coords again - check the pin on the map";
		}
		if (lower.contains("?")) {
			return "yep, all good. click my coords message and you'll get a waypoint";
		}
		if (lower.contains("hi") || lower.contains("hey") || lower.contains("hello") || lower.contains("yo")) {
			return "hey! press Coords if you want my spot pinned, or drop a screenshot in here";
		}
		if (lower.contains("waypoint") || lower.contains("pin")) {
			return "waypoints are client side - press B to see or delete them";
		}
		if (lower.contains("image") || lower.contains("picture") || lower.contains("screenshot")) {
			return "send it with Img, or just drag the file onto the window";
		}
		return "got it: " + text;
	}

	private void flush(Minecraft client) {
		if (this.replies.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - this.lastSendAt < 900L) {
			return;
		}
		String next = this.replies.poll();
		ClientPlayNetworking.send(new Payloads.SendText(this.groupId, next));
		this.lastSendAt = now;
		this.log("replied: " + next);
	}

	// ---------------------------------------------------------------- helpers

	private boolean atCamp(Minecraft client) {
		if (client.player == null) {
			return false;
		}
		return client.player.blockPosition().distSqr(new net.minecraft.core.BlockPos(320, -60, -260)) < 400.0D;
	}

	private int memberCount() {
		GroupDto group = ClientState.INSTANCE.group(this.groupId);
		return group == null ? 0 : group.members().size();
	}

	private boolean online(String name) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return false;
		}
		for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
			if (info.getProfile().name().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	private void log(String message) {
		GroupChatClient.LOGGER.info("[groupchat] live-{}: {}", this.role, message);
	}
}
