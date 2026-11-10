package com.example.groupchat.client.dev;

import com.example.groupchat.client.ClientState;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.client.ui.HubScreen;
import com.example.groupchat.client.waypoint.SharedLocation;
import com.example.groupchat.client.waypoint.WaypointManager;
import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.InviteDto;
import com.example.groupchat.net.Payloads.MessageDto;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Two scripted "player agents" used to exercise the mod with a real second player.
 *
 * <p>Agent A joins a dedicated server, creates a group, invites B, chats, shares coordinates and
 * uploads a screenshot of its own view. Agent B accepts the invite, turns A's shared coordinates
 * into a waypoint, replies with its own coordinates and screenshots the waypoint HUD.
 *
 * <p>Enabled with {@code -Dgroupchat.devtools.agent=a|b} (plus {@code -Dgroupchat.devtools.connect}).
 */
public final class AgentScenario {
	private static final String ADDRESS = System.getProperty("groupchat.devtools.connect", "").trim();

	private final String role;
	private int phase;
	private int ticks;
	private String groupId;
	private String selfName;
	private HubScreen hub;

	private AgentScenario(String role) {
		this.role = role;
	}

	public static void init(String role) {
		AgentScenario scenario = new AgentScenario(role);
		ClientTickEvents.END_CLIENT_TICK.register(scenario::tick);
		GroupChatClient.LOGGER.warn("[groupchat] agent '{}' running", role);
	}

	private void tick(Minecraft client) {
		if (this.phase == 0) {
			if (client.screen instanceof net.minecraft.client.gui.screens.TitleScreen && client.getOverlay() == null) {
				if (this.ticks++ < 10) {
					return;
				}
				this.log("connecting to " + ADDRESS);
				net.minecraft.client.multiplayer.ServerData data =
						new net.minecraft.client.multiplayer.ServerData("agents", ADDRESS,
								net.minecraft.client.multiplayer.ServerData.Type.OTHER);
				net.minecraft.client.gui.screens.ConnectScreen.startConnecting(null, client,
						net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(ADDRESS), data, false, null);
				this.phase = 1;
				this.ticks = 0;
			}
			return;
		}
		if (this.phase == 1) {
			if (client.player != null && client.level != null) {
				if (this.selfName == null) {
					this.selfName = client.player.getGameProfile().name();
				}
				this.log("joined as " + this.selfName);
				this.phase = 2;
				this.ticks = 0;
			} else if (this.ticks++ > 900) {
				this.log("could not join the server");
				client.stop();
			}
			return;
		}
		if (client.player == null || client.level == null) {
			return;
		}
		// keep the world visible even when the window is in the background
		if (client.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
			client.setScreen(null);
		}
		if (client.player.isDeadOrDying()) {
			this.log("died, respawning");
			client.player.respawn();
			return;
		}
		this.ticks++;
		switch (this.phase) {
			case 2 -> {
				if (this.ticks > 20) {
					// teleport so the two agents are far apart and the coordinates are meaningful
					String target = this.role.equals("a") ? "140 -60 -180" : "-260 -60 240";
					client.player.connection.sendCommand("tp " + this.selfName + " " + target);
					this.log("teleported to " + target);
					this.phase = 3;
					this.ticks = 0;
				}
			}
			default -> {
				if (this.role.equals("a")) {
					this.tickA(client);
				} else {
					this.tickB(client);
				}
			}
		}
	}

	// ------------------------------------------------------------------ agent A

	private void tickA(Minecraft client) {
		switch (this.phase) {
			case 3 -> {
				if (this.online("AgentB")) {
					this.log("AgentB is online, creating group");
					ClientPlayNetworking.send(new Payloads.CreateGroup("expedition", List.of("AgentB")));
					this.phase = 4;
					this.ticks = 0;
				} else if (this.ticks > 3000) {
					this.log("AgentB never showed up, aborting");
					client.stop();
				}
			}
			case 4 -> {
				if (!ClientState.INSTANCE.groups().isEmpty()) {
					GroupDto group = ClientState.INSTANCE.groups().get(0);
					this.groupId = group.id();
					this.log("group ready: " + group.name() + " [" + this.groupId + "]");
					ClientPlayNetworking.send(new Payloads.SendText(this.groupId,
							"found an exposed amethyst geode at the ravine, sharing my coords"));
					SharedLocation location = SharedLocation.of(client.player);
					ClientPlayNetworking.send(new Payloads.SendLocation(this.groupId, location.dimension(),
							location.x(), location.y(), location.z()));
					this.log("shared coords " + location.pretty());
					this.phase = 5;
					this.ticks = 0;
				} else if (this.ticks > 200) {
					this.log("group never arrived");
					client.stop();
				}
			}
			case 5 -> {
				// take a real in-game screenshot and push it into the group as an image
				if (this.ticks == 20) {
					this.uploadScreenshot(client, "agentA_ravine");
				} else if (this.ticks > 50) {
					this.log("opening the hub");
					client.setScreen(new HubScreen(null));
					this.phase = 6;
					this.ticks = 0;
				}
			}
			case 6 -> {
				if (this.ticks == 30) {
					this.clearToasts(client);
					DevShots.shot(client, "a1");
				} else if (this.ticks > 60) {
					this.phase = 7;
					this.ticks = 0;
				}
			}
			case 7 -> {
				if (this.hasMessageFrom("AgentB")) {
					this.log("AgentB replied");
					this.phase = 8;
					this.ticks = 0;
				} else if (this.ticks > 2400) {
					this.log("no reply from AgentB, wrapping up");
					this.phase = 8;
					this.ticks = 0;
				}
			}
			case 8 -> {
				if (this.ticks == 20) {
					this.clearToasts(client);
					DevShots.shot(client, "a2");
				} else if (this.ticks > 50) {
					this.log("agent A done");
					client.stop();
				}
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ agent B

	private void tickB(Minecraft client) {
		switch (this.phase) {
			case 3 -> {
				if (!ClientState.INSTANCE.groups().isEmpty()) {
					// rejoining: already a member of a group
					GroupDto existing = ClientState.INSTANCE.groups().get(0);
					this.groupId = existing.id();
					this.log("already a member of " + existing.name());
					this.phase = 4;
					this.ticks = 0;
					return;
				}
				List<InviteDto> invites = ClientState.INSTANCE.invites();
				if (!invites.isEmpty()) {
					InviteDto invite = invites.get(0);
					this.groupId = invite.groupId();
					this.log("accepting invite to " + invite.groupName());
					ClientPlayNetworking.send(new Payloads.RespondInvite(invite.groupId(), true));
					this.phase = 4;
					this.ticks = 0;
				} else if (this.ticks > 900) {
					this.log("no invite received, aborting");
					client.stop();
				}
			}
			case 4 -> {
				if (this.ticks == 5) {
					// make sure history is in memory (it also carries the images and shared coords)
					GroupChatClient.INSTANCE.ensureHistory(this.groupId);
				}
				MessageDto locationMessage = this.findLocationMessage();
				if (locationMessage != null) {
					GroupDto group = ClientState.INSTANCE.group(this.groupId);
					WaypointManager.Waypoint waypoint = WaypointManager.INSTANCE.addFromMessage(locationMessage,
							group == null ? "" : group.name());
					this.log("created waypoint from " + locationMessage.sender() + ": "
							+ (waypoint == null ? "already existed" : waypoint.name() + " " + waypoint.location().pretty()));
					ClientPlayNetworking.send(new Payloads.SendText(this.groupId,
							"waypoint set, heading over - my position:"));
					SharedLocation mine = SharedLocation.of(client.player);
					ClientPlayNetworking.send(new Payloads.SendLocation(this.groupId, mine.dimension(), mine.x(),
							mine.y(), mine.z()));
					this.log("shared own coords " + mine.pretty());
					this.phase = 5;
					this.ticks = 0;
				} else if (this.ticks > 500) {
					this.log("never received shared coordinates");
					client.stop();
				}
			}
			case 5 -> {
				if (this.ticks == 20) {
					client.setScreen(null);
				} else if (this.ticks > 40) {
					this.clearToasts(client);
					DevShots.shot(client, "b1");
					this.phase = 6;
					this.ticks = 0;
				}
			}
			case 6 -> {
				if (this.ticks == 20) {
					this.hub = new HubScreen(null);
					client.setScreen(this.hub);
				} else if (this.ticks == 60 && this.hub != null) {
					// scroll to the oldest message so the shared screenshot is visible
					this.hub.devScrollToTop();
				} else if (this.ticks > 140) {
					this.clearToasts(client);
					DevShots.shot(client, "b2");
					this.phase = 7;
					this.ticks = 0;
				}
			}
			case 7 -> {
				if (this.ticks > 80) {
					this.log("agent B done");
					client.stop();
				}
			}
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- helpers

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

	private MessageDto findLocationMessage() {
		if (this.groupId == null) {
			return null;
		}
		for (MessageDto message : ClientState.INSTANCE.messages(this.groupId)) {
			if (message.kind() == MessageDto.KIND_LOCATION && !message.sender().equals(this.selfName)) {
				return message;
			}
		}
		return null;
	}

	private boolean hasMessageFrom(String name) {
		if (this.groupId == null) {
			return false;
		}
		for (MessageDto message : ClientState.INSTANCE.messages(this.groupId)) {
			if (message.sender().equals(name) && message.kind() != MessageDto.KIND_SYSTEM) {
				return true;
			}
		}
		return false;
	}

	/** Captures the current frame, stores it on disk and sends it to the group as an image. */
	private void uploadScreenshot(Minecraft client, String imageId) {
		Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
			try {
				File file = new File(client.gameDirectory, imageId + ".png");
				image.writeToFile(file);
				byte[] png = Files.readAllBytes(file.toPath());
				client.execute(() -> {
					GroupChatClient.INSTANCE.textures.accept(imageId, png);
					ClientPlayNetworking.send(new Payloads.UploadImage(this.groupId, imageId, png));
					this.log("uploaded in-game screenshot " + imageId + " (" + png.length + " bytes)");
				});
			} catch (Exception exception) {
				this.log("screenshot upload failed: " + exception.getMessage());
			} finally {
				image.close();
			}
		});
	}

	private void clearToasts(Minecraft client) {
		client.getToastManager().clear();
	}

	private void log(String message) {
		GroupChatClient.LOGGER.info("[groupchat] agent-{}: {}", this.role, message);
	}
}
