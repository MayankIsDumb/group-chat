package com.example.groupchat.server;

import com.example.groupchat.Constants;
import com.example.groupchat.voice.VoiceBridge;
import com.example.groupchat.GroupChat;
import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.InviteDto;
import com.example.groupchat.net.Payloads.MessageDto;
import com.example.groupchat.net.Protocol;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Owns every group, invite and message on the server. All mutating methods run on the server thread.
 */
public final class GroupManager {
	private static final SecureRandom RANDOM = new SecureRandom();
	/** Number of base-36 characters in a group id. */
	private static final int ID_LENGTH = 8;
	private static GroupManager instance;

	private final MinecraftServer server;
	private final Path baseDir;
	private final Path groupsFile;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Map<String, GroupData> groups = new LinkedHashMap<>();
	private final Map<String, List<InviteDto>> invites = new HashMap<>();
	private final ImageStore images;
	private final RateLimits rates = new RateLimits();
	private boolean dirty;
	private int saveCountdown;

	private GroupManager(MinecraftServer server) {
		this.server = server;
		this.baseDir = server.getWorldPath(LevelResource.ROOT).resolve("groupchat");
		this.groupsFile = this.baseDir.resolve("groups.json");
		this.images = new ImageStore(this.baseDir.resolve("images"), GroupChat.LOGGER);
	}

	// ------------------------------------------------------------- lifecycle

	public static void bind(MinecraftServer server) {
		GroupManager manager = new GroupManager(server);
		manager.load();
		instance = manager;
		GroupChat.LOGGER.info("[groupchat] loaded {} group(s) from {}", manager.groups.size(), manager.groupsFile);
	}

	public static GroupManager get() {
		return instance;
	}

	public static void unbind() {
		if (instance != null) {
			instance.saveNow();
			instance = null;
		}
	}

	public static boolean ready() {
		return instance != null;
	}

	private void load() {
		this.images.scan();
		if (!Files.isRegularFile(this.groupsFile)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(this.groupsFile, StandardCharsets.UTF_8)) {
			Store store = this.gson.fromJson(reader, Store.class);
			if (store == null) {
				return;
			}
			if (store.groups != null) {
				for (GroupData group : store.groups) {
					if (group != null && group.id != null && group.name != null && group.owner != null) {
						this.groups.put(group.id, group);
					}
				}
			}
			if (store.invites != null) {
				this.invites.putAll(store.invites);
			}
		} catch (IOException | JsonSyntaxException exception) {
			GroupChat.LOGGER.error("[groupchat] could not read {}", this.groupsFile, exception);
		}
	}

	public void saveNow() {
		try {
			Files.createDirectories(this.baseDir);
			Store store = new Store();
			store.groups = new ArrayList<>(this.groups.values());
			store.invites = this.invites;
			Path temp = this.groupsFile.resolveSibling("groups.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				this.gson.toJson(store, writer);
			}
			Files.move(temp, this.groupsFile, StandardCopyOption.REPLACE_EXISTING);
			this.dirty = false;
		} catch (IOException exception) {
			GroupChat.LOGGER.error("[groupchat] could not save {}", this.groupsFile, exception);
		}
	}

	public void tick() {
		List<VoiceBridge.Call> calls = new ArrayList<>();
		for (GroupData group : this.groups.values()) {
			if (group.inCall.isEmpty()) {
				continue;
			}
			List<ServerPlayer> members = new ArrayList<>();
			for (String name : group.inCall) {
				ServerPlayer player = player(name);
				if (player != null) {
					members.add(player);
				}
			}
			if (!members.isEmpty()) {
				calls.add(new VoiceBridge.Call(group.id, group.name, members));
			}
		}
		VoiceBridge.update(calls);
		if (!this.dirty) {
			return;
		}
		if (--this.saveCountdown <= 0) {
			this.saveNow();
		}
	}

	private void markDirty() {
		this.dirty = true;
		this.saveCountdown = 100;
	}

	// ---------------------------------------------------------------- queries

	public ImageStore images() {
		return this.images;
	}

	public RateLimits rates() {
		return this.rates;
	}

	public GroupData find(String playerName, String groupId) {
		GroupData group = this.groups.get(groupId);
		return group != null && group.isMember(playerName) ? group : null;
	}

	public List<GroupData> groupsOf(String playerName) {
		List<GroupData> result = new ArrayList<>();
		for (GroupData group : this.groups.values()) {
			if (group.isMember(playerName)) {
				result.add(group);
			}
		}
		result.sort(Comparator.comparing(group -> group.name, String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	public GroupDto dto(GroupData group) {
		return group.toDto(this.server);
	}

	public List<InviteDto> invitesFor(String playerName) {
		List<InviteDto> result = this.invites.get(playerName);
		return result == null ? List.of() : List.copyOf(result);
	}

	public boolean isOnline(String playerName) {
		return this.server.getPlayerList().getPlayerByName(playerName) != null;
	}

	public ServerPlayer player(String playerName) {
		return this.server.getPlayerList().getPlayerByName(playerName);
	}

	// -------------------------------------------------------------- mutations

	/** @return {@code null} on success, otherwise a user-facing error */
	public String create(String owner, String rawName, List<String> invitees) {
		String name = rawName == null ? "" : rawName.trim();
		if (name.isEmpty() || name.length() > Constants.MAX_GROUP_NAME_LEN) {
			return "Group name must be 1-" + Constants.MAX_GROUP_NAME_LEN + " characters.";
		}
		GroupData group = new GroupData(newId(), name, owner);
		this.groups.put(group.id, group);
		this.markDirty();
		Protocol.send(online(owner), new Payloads.GroupState(this.dto(group)));
		sendHistory(online(owner), group);
		for (String invitee : invitees) {
			String error = this.invite(group.id, owner, invitee);
			if (error != null && !invitee.equalsIgnoreCase(owner)) {
				Protocol.send(online(owner), new Payloads.Notice(error, true));
			}
		}
		return null;
	}

	public String invite(String groupId, String inviter, String target) {
		GroupData group = this.find(inviter, groupId);
		if (group == null) {
			return "You are not in that group.";
		}
		if (target == null || target.isBlank()) {
			return "Pick a player to invite.";
		}
		if (!this.isOnline(target)) {
			return target + " is not online.";
		}
		if (group.isMember(target)) {
			return target + " is already in '" + group.name + "'.";
		}
		if (group.members.size() >= Constants.MAX_GROUP_MEMBERS) {
			return "That group is full (" + Constants.MAX_GROUP_MEMBERS + " members).";
		}
		List<InviteDto> pending = this.invites.computeIfAbsent(target, ignored -> new ArrayList<>());
		for (InviteDto existing : pending) {
			if (existing.groupId().equals(groupId)) {
				return target + " already has an invite to '" + group.name + "'.";
			}
		}
		InviteDto invite = new InviteDto(group.id, group.name, inviter);
		pending.add(invite);
		this.markDirty();
		ServerPlayer player = online(target);
		if (player != null) {
			Protocol.send(player, new Payloads.Invite(invite));
			Protocol.send(player, new Payloads.Notice(inviter + " invited you to '" + group.name + "'", false));
		}
		return null;
	}

	public String respond(String player, String groupId, boolean accept) {
		List<InviteDto> pending = this.invites.get(player);
		if (pending == null) {
			return "You have no pending invites.";
		}
		InviteDto invite = null;
		for (InviteDto candidate : pending) {
			if (candidate.groupId().equals(groupId)) {
				invite = candidate;
				break;
			}
		}
		if (invite == null) {
			return "You have no pending invite for that group.";
		}
		pending.remove(invite);
		if (pending.isEmpty()) {
			this.invites.remove(player);
		}
		GroupData group = this.groups.get(groupId);
		ServerPlayer joining = online(player);
		if (!accept || group == null) {
			this.markDirty();
			if (joining != null) {
				Protocol.send(joining, new Payloads.InviteRemoved(groupId));
			}
			notifyOwner(group, player + " declined the invite to '" + (group == null ? "?" : group.name) + "'");
			return null;
		}
		if (group.members.size() >= Constants.MAX_GROUP_MEMBERS) {
			return "That group is full.";
		}
		group.members.add(player);
		this.markDirty();
		post(group, "", MessageDto.KIND_SYSTEM, player + " joined the group", "");
		broadcastGroup(group);
		if (joining != null) {
			Protocol.send(joining, new Payloads.InviteRemoved(groupId));
		}
		sendHistory(joining, group);
		return null;
	}

	public String leave(String player, String groupId) {
		GroupData group = this.find(player, groupId);
		if (group == null) {
			return "You are not in that group.";
		}
		group.inCall.remove(player);
		updateCallState(group);
		group.members.remove(player);
		this.markDirty();
		ServerPlayer leaving = online(player);
		if (leaving != null) {
			Protocol.send(leaving, new Payloads.GroupRemoved(group.id));
		}
		if (group.members.isEmpty()) {
			this.groups.remove(group.id);
			post(group, "", MessageDto.KIND_SYSTEM, player + " left, group deleted", "");
		} else {
			if (group.owner.equals(player)) {
				group.owner = group.members.iterator().next();
			}
			post(group, "", MessageDto.KIND_SYSTEM, player + " left the group", "");
			broadcastGroup(group);
		}
		return null;
	}

	public String kick(String player, String groupId, String target) {
		GroupData group = this.find(player, groupId);
		if (group == null) {
			return "You are not in that group.";
		}
		if (!group.owner.equals(player)) {
			return "Only the group owner can remove members.";
		}
		if (player.equals(target)) {
			return "Use /gchat leave to leave a group.";
		}
		if (!group.isMember(target)) {
			return target + " is not in that group.";
		}
		group.inCall.remove(target);
		updateCallState(group);
		group.members.remove(target);
		this.markDirty();
		ServerPlayer kicked = online(target);
		if (kicked != null) {
			Protocol.send(kicked, new Payloads.GroupRemoved(group.id));
			Protocol.send(kicked, new Payloads.Notice("You were removed from '" + group.name + "'", true));
		}
		post(group, "", MessageDto.KIND_SYSTEM, target + " was removed from the group", "");
		broadcastGroup(group);
		return null;
	}

	public MessageDto post(GroupData group, String sender, int kind, String text, String imageId) {
		MessageDto message = new MessageDto(
				UUID.randomUUID().toString().substring(0, 13),
				group.id,
				sender == null ? "" : sender,
				kind,
				text == null ? "" : text,
				imageId == null ? "" : imageId,
				System.currentTimeMillis());
		group.addMessage(message);
		this.markDirty();
		forEachOnlineMember(group, player -> Protocol.send(player, new Payloads.Message(message)));
		return message;
	}

	public void setCall(String player, String groupId, boolean join) {
		GroupData group = this.find(player, groupId);
		if (group == null) {
			return;
		}
		if (join) {
			group.inCall.add(player);
		} else {
			group.inCall.remove(player);
		}
		updateCallState(group);
		this.markDirty();
		broadcastGroup(group);
	}

	public void onDisconnect(String player) {
		boolean changed = false;
		for (GroupData group : this.groups.values()) {
			if (group.inCall.remove(player)) {
				updateCallState(group);
				changed = true;
				broadcastGroup(group);
			}
		}
		if (changed) {
			this.markDirty();
		}
	}

	public GroupData byId(String groupId) {
		return this.groups.get(groupId);
	}

	// ------------------------------------------------------------- transport

	public void broadcastGroup(GroupData group) {
		Payloads.GroupState payload = new Payloads.GroupState(this.dto(group));
		forEachOnlineMember(group, player -> Protocol.send(player, payload));
	}

	public void sendSnapshot(ServerPlayer player) {
		String name = player.getGameProfile().name();
		List<GroupDto> groups = new ArrayList<>();
		for (GroupData group : groupsOf(name)) {
			groups.add(this.dto(group));
		}
		Protocol.send(player, new Payloads.Snapshot(name, groups, invitesFor(name)));
	}

	public void sendHistory(ServerPlayer player, GroupData group) {
		if (player == null) {
			return;
		}
		Protocol.send(player, new Payloads.History(group.id, List.copyOf(group.history)));
	}

	private void notifyOwner(GroupData group, String text) {
		if (group == null) {
			return;
		}
		ServerPlayer owner = online(group.owner);
		if (owner != null) {
			Protocol.send(owner, new Payloads.Notice(text, false));
		}
	}

	private void forEachOnlineMember(GroupData group, Consumer<ServerPlayer> action) {
		for (String member : group.members) {
			ServerPlayer player = online(member);
			if (player != null) {
				action.accept(player);
			}
		}
	}

	private void updateCallState(GroupData group) {
		boolean active = !group.inCall.isEmpty();
		if (active != group.callActive) {
			group.callActive = active;
		}
	}

	private ServerPlayer online(String playerName) {
		return playerName == null ? null : this.server.getPlayerList().getPlayerByName(playerName);
	}

	/**
	 * Short, URL-safe group id.
	 *
	 * <p>{@code Long.toUnsignedString} avoids the sign problem of {@code Math.abs} (which leaves
	 * {@code Long.MIN_VALUE} negative) and the random long is drawn as base-36 digits until enough
	 * of them are collected, so the id is never shorter than {@link #ID_LENGTH} characters.
	 *
	 * <p>The <em>last</em> {@link #ID_LENGTH} characters are kept on purpose: the leading base-36
	 * digits are not uniform (three quarters of all random values have 13 digits, which makes the
	 * first digit collapse onto '1'-'3'), while the trailing digits are spread evenly.
	 */
	private static String newId() {
		StringBuilder builder = new StringBuilder(ID_LENGTH);
		while (builder.length() < ID_LENGTH) {
			builder.append(Long.toUnsignedString(RANDOM.nextLong(), 36));
		}
		return builder.substring(builder.length() - ID_LENGTH);
	}

	public List<String> onlinePlayerNames() {
		List<String> names = new ArrayList<>();
		for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
			names.add(player.getGameProfile().name());
		}
		return names;
	}

	public LinkedHashSet<String> contactsOf(String playerName) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (GroupData group : groupsOf(playerName)) {
			names.addAll(group.members);
		}
		names.remove(playerName);
		return names;
	}

	@SuppressWarnings("unused")
	private static final class Store {
		List<GroupData> groups;
		Map<String, List<InviteDto>> invites;
	}
}
