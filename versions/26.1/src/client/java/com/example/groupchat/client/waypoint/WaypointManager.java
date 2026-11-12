package com.example.groupchat.client.waypoint;

import com.example.groupchat.Constants;
import com.example.groupchat.client.ClientState;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.net.Payloads.MessageDto;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Client-side waypoint list, persisted to {@code config/groupchat/waypoints.json}.
 *
 * <p>Waypoints are created by clicking a shared-coordinate message, or by pressing "Add here" in the
 * waypoint screen. They are per-client data — the server never sees them.
 */
public final class WaypointManager {
	public static final WaypointManager INSTANCE = new WaypointManager();

	private static final int MAX_WAYPOINTS = 64;
	/** Walking this close counts as "arrived" and clears the waypoint. */
	private static final double ARRIVAL_RADIUS = 6.0D;

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final List<Waypoint> waypoints = new ArrayList<>();
	private boolean loaded;

	private WaypointManager() {
	}

	public record Waypoint(String id, String name, String dimension, double x, double y, double z, int color,
			boolean visible, String owner) {
		public SharedLocation location() {
			return new SharedLocation(this.dimension, this.x, this.y, this.z);
		}

		public String ownerName() {
			return this.owner == null ? "" : this.owner;
		}

		public Waypoint withVisible(boolean value) {
			return new Waypoint(this.id, this.name, this.dimension, this.x, this.y, this.z, this.color, value,
					this.owner);
		}
	}

	public List<Waypoint> all() {
		ensureLoaded();
		return List.copyOf(this.waypoints);
	}

	public List<Waypoint> visible() {
		List<Waypoint> result = new ArrayList<>();
		for (Waypoint waypoint : this.all()) {
			if (waypoint.visible()) {
				result.add(waypoint);
			}
		}
		return result;
	}

	public void ensureLoaded() {
		if (this.loaded) {
			return;
		}
		this.loaded = true;
		Path file = this.file();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Store store = this.gson.fromJson(reader, Store.class);
			if (store != null && store.waypoints != null) {
				this.waypoints.clear();
				this.waypoints.addAll(store.waypoints);
			}
		} catch (IOException | JsonSyntaxException exception) {
			GroupChatClient.LOGGER.warn("[groupchat] could not read waypoints", exception);
		}
	}

	/** Creates a waypoint from a shared-coordinate message. */
	public Waypoint addFromMessage(MessageDto message, String groupName) {
		SharedLocation location = SharedLocation.parse(message.text());
		if (location == null) {
			return null;
		}
		String sender = message.sender().isBlank() ? "unknown" : message.sender();
		String name = sender + "'s spot" + (groupName == null || groupName.isBlank() ? "" : " · #" + groupName);
		return add(location, name, colorFor(sender), sender);
	}

	public Waypoint add(SharedLocation location, String name, int color) {
		return add(location, name, color, "");
	}

	public Waypoint add(SharedLocation location, String name, int color, String owner) {
		ensureLoaded();
		for (Waypoint existing : this.waypoints) {
			if (existing.dimension().equals(location.dimension())
					&& Math.abs(existing.x() - location.x()) < 2.0D
					&& Math.abs(existing.y() - location.y()) < 2.0D
					&& Math.abs(existing.z() - location.z()) < 2.0D) {
				return null;
			}
		}
		Waypoint waypoint = new Waypoint(
				Long.toString(System.nanoTime(), 36),
				name,
				location.dimension(),
				location.x(),
				location.y(),
				location.z(),
				color,
				true,
				owner == null ? "" : owner);
		this.waypoints.add(waypoint);
		while (this.waypoints.size() > MAX_WAYPOINTS) {
			this.waypoints.remove(0);
		}
		this.save();
		return waypoint;
	}

	public Waypoint addHere(LocalPlayer player) {
		if (player == null) {
			return null;
		}
		SharedLocation location = SharedLocation.of(player);
		String name = "My spot " + location.pretty();
		// the waypoint belongs to the player, so the arrival check leaves it alone
		return add(location, name, 0xFF8B7BFF, player.getScoreboardName());
	}

	public void remove(String id) {
		this.waypoints.removeIf(waypoint -> waypoint.id().equals(id));
		this.save();
	}

	public void toggle(String id) {
		for (int i = 0; i < this.waypoints.size(); i++) {
			Waypoint waypoint = this.waypoints.get(i);
			if (waypoint.id().equals(id)) {
				this.waypoints.set(i, waypoint.withVisible(!waypoint.visible()));
				break;
			}
		}
		this.save();
	}

	public void clear() {
		this.waypoints.clear();
		this.save();
	}

	/**
	 * Removes waypoints the player has walked into. Runs every client tick; the waypoint announces
	 * itself in chat and plays a short sound when it disappears.
	 */
	public void tick() {
		ensureLoaded();
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null || this.waypoints.isEmpty()) {
			return;
		}
		String dimension = client.level.dimension().identifier().toString();
		String self = client.player.getScoreboardName();
		List<Waypoint> reached = new ArrayList<>();
		for (Waypoint waypoint : this.waypoints) {
			if (!waypoint.dimension().equals(dimension)) {
				continue;
			}
			if (self.equals(waypoint.ownerName())) {
				continue;
			}
			double distance = waypoint.location().distanceFrom(client);
			if (distance >= 0 && distance <= ARRIVAL_RADIUS) {
				reached.add(waypoint);
			}
		}
		if (reached.isEmpty()) {
			return;
		}
		for (Waypoint waypoint : reached) {
			this.waypoints.remove(waypoint);
			client.gui.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("[Group Chat] ")
					.withStyle(net.minecraft.ChatFormatting.DARK_PURPLE)
					.append(net.minecraft.network.chat.Component.literal("reached waypoint " + waypoint.name())
							.withStyle(net.minecraft.ChatFormatting.GREEN)));
			client.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
					.forUI(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.4F, 0.6F));
		}
		this.save();
	}

	public Waypoint nearest() {
		Minecraft client = Minecraft.getInstance();
		Waypoint best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Waypoint waypoint : this.visible()) {
			double distance = waypoint.location().distanceFrom(client);
			if (distance >= 0 && distance < bestDistance) {
				bestDistance = distance;
				best = waypoint;
			}
		}
		return best;
	}

	private Path file() {
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(Constants.MOD_ID);
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
		return dir.resolve("waypoints.json");
	}

	private void save() {
		Store store = new Store();
		store.waypoints = new ArrayList<>(this.waypoints);
		try (Writer writer = Files.newBufferedWriter(this.file(), StandardCharsets.UTF_8)) {
			this.gson.toJson(store, writer);
		} catch (IOException exception) {
			GroupChatClient.LOGGER.warn("[groupchat] could not save waypoints", exception);
		}
	}

	public static int colorFor(String seed) {
		int[] palette = {0xFF89DDFF, 0xFFF7C873, 0xFF8CE99A, 0xFFFF9EB5, 0xFFC5A3FF, 0xFF9FE8D8};
		return palette[Math.abs(seed.hashCode()) % palette.length];
	}

	/** Convenience for the HUD/screens: the name of the group a waypoint came from, if any. */
	public static String currentGroupName() {
		return ClientState.INSTANCE.activeGroup() == null ? "" : ClientState.INSTANCE.activeGroup().name();
	}

	private static final class Store {
		List<Waypoint> waypoints;
	}
}
