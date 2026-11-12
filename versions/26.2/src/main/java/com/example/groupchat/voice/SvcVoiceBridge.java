package com.example.groupchat.voice;

import com.example.groupchat.Constants;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors Group Chat call membership into Simple Voice Chat groups. Each call gets a hidden,
 * non-persistent SVC group; leaving the call removes the player from it.
 */
final class SvcVoiceBridge implements VoiceBridge.Impl {
	private static final Logger LOGGER = LoggerFactory.getLogger(Constants.MOD_ID);
	private static final int MAX_GROUP_NAME = 16;

	private final VoicechatServerApi api;
	private final Map<String, Group> groups = new HashMap<>();
	private final Map<String, Set<UUID>> members = new HashMap<>();

	SvcVoiceBridge(VoicechatServerApi api) {
		this.api = api;
	}

	@Override
	public void update(List<VoiceBridge.Call> calls) {
		try {
			Set<String> active = new HashSet<>();
			for (VoiceBridge.Call call : calls) {
				active.add(call.groupId());
				sync(call);
			}
			for (String groupId : List.copyOf(this.groups.keySet())) {
				if (!active.contains(groupId)) {
					remove(groupId);
				}
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("[groupchat] Simple Voice Chat group sync failed", exception);
		}
	}

	private void sync(VoiceBridge.Call call) {
		Group group = this.groups.get(call.groupId());
		if (group == null) {
			UUID id = UUID.nameUUIDFromBytes(("groupchat:" + call.groupId()).getBytes(StandardCharsets.UTF_8));
			group = this.api.getGroup(id);
			if (group == null) {
				String name = call.groupName() == null ? "group call" : call.groupName();
				if (name.length() > MAX_GROUP_NAME) {
					name = name.substring(0, MAX_GROUP_NAME);
				}
				group = this.api.groupBuilder()
						.setId(id)
						.setName(name)
						.setPersistent(false)
						.setHidden(true)
						.build();
				LOGGER.info("[groupchat] created Simple Voice Chat group {} for #{}", id, call.groupName());
			}
			this.groups.put(call.groupId(), group);
		}

		Set<UUID> wanted = new HashSet<>();
		for (ServerPlayer player : call.members()) {
			wanted.add(player.getUUID());
		}
		Set<UUID> previous = this.members.getOrDefault(call.groupId(), Set.of());
		for (UUID uuid : previous) {
			if (!wanted.contains(uuid)) {
				unset(uuid, group);
			}
		}
		for (UUID uuid : wanted) {
			VoicechatConnection connection = this.api.getConnectionOf(uuid);
			if (connection != null && connection.isConnected() && !sameGroup(connection, group)) {
				connection.setGroup(group);
			}
		}
		this.members.put(call.groupId(), wanted);
	}

	private void remove(String groupId) {
		Group group = this.groups.remove(groupId);
		Set<UUID> previous = this.members.remove(groupId);
		if (group != null) {
			if (previous != null) {
				for (UUID uuid : previous) {
					unset(uuid, group);
				}
			}
			this.api.removeGroup(group.getId());
			LOGGER.info("[groupchat] removed Simple Voice Chat group {} (#{})", group.getId(), group.getName());
		}
	}

	private void unset(UUID uuid, Group group) {
		VoicechatConnection connection = this.api.getConnectionOf(uuid);
		if (connection != null && sameGroup(connection, group)) {
			connection.setGroup(null);
		}
	}

	private static boolean sameGroup(VoicechatConnection connection, Group group) {
		return connection.getGroup() != null && connection.getGroup().getId().equals(group.getId());
	}

	@Override
	public void reset() {
		this.groups.clear();
		this.members.clear();
	}
}
