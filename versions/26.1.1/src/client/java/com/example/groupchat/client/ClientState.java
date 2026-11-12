package com.example.groupchat.client;

import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.InviteDto;
import com.example.groupchat.net.Payloads.MemberDto;
import com.example.groupchat.net.Payloads.MessageDto;
import com.example.groupchat.net.Payloads.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Everything the GUI needs to know, mirroring the server snapshot. Only touched from the render
 * thread (Fabric delivers payloads there).
 */
public final class ClientState {
	public static final ClientState INSTANCE = new ClientState();

	private final Map<String, GroupDto> groups = new LinkedHashMap<>();
	private final Map<String, List<MessageDto>> messages = new LinkedHashMap<>();
	private final Map<String, Integer> unread = new LinkedHashMap<>();
	private final List<InviteDto> invites = new ArrayList<>();
	private String selfName = "";
	private String activeGroupId;
	private int version = 1;
	private Consumer<MessageDto> messageListener = message -> {
	};

	private ClientState() {
	}

	public void setMessageListener(Consumer<MessageDto> listener) {
		this.messageListener = listener;
	}

	public String selfName() {
		return this.selfName;
	}

	/** Increments on every mutation; the chat view uses it to drop cached layout. */
	public int version() {
		return this.version;
	}

	public void applySnapshot(Snapshot snapshot) {
		this.selfName = snapshot.selfName();
		this.groups.clear();
		for (GroupDto group : snapshot.groups()) {
			this.groups.put(group.id(), group);
		}
		for (String id : List.copyOf(this.messages.keySet())) {
			if (!this.groups.containsKey(id)) {
				this.messages.remove(id);
				this.unread.remove(id);
			}
		}
		this.invites.clear();
		this.invites.addAll(snapshot.invites());
		if (this.activeGroupId != null && !this.groups.containsKey(this.activeGroupId)) {
			this.activeGroupId = null;
		}
		this.bump();
	}

	public void applyGroup(GroupDto group) {
		this.groups.put(group.id(), group);
		this.bump();
	}

	public void removeGroup(String groupId) {
		this.groups.remove(groupId);
		this.messages.remove(groupId);
		this.unread.remove(groupId);
		if (groupId.equals(this.activeGroupId)) {
			this.activeGroupId = null;
		}
		this.bump();
	}

	public void applyHistory(String groupId, List<MessageDto> history) {
		List<MessageDto> list = new ArrayList<>(history);
		list.sort((left, right) -> Long.compare(left.time(), right.time()));
		this.messages.put(groupId, list);
		this.bump();
	}

	public void addMessage(MessageDto message) {
		List<MessageDto> list = this.messages.computeIfAbsent(message.groupId(), ignored -> new ArrayList<>());
		for (MessageDto existing : list) {
			if (existing.id().equals(message.id())) {
				return;
			}
		}
		list.add(message);
		while (list.size() > 200) {
			list.remove(0);
		}
		if (!message.groupId().equals(this.activeGroupId)) {
			this.unread.merge(message.groupId(), 1, Integer::sum);
		}
		this.bump();
		this.messageListener.accept(message);
	}

	public void addInvite(InviteDto invite) {
		for (InviteDto existing : this.invites) {
			if (existing.groupId().equals(invite.groupId())) {
				return;
			}
		}
		this.invites.add(invite);
		this.bump();
	}

	public void removeInvite(String groupId) {
		this.invites.removeIf(invite -> invite.groupId().equals(groupId));
		this.bump();
	}

	public List<InviteDto> invites() {
		return List.copyOf(this.invites);
	}

	public List<GroupDto> groups() {
		List<GroupDto> list = new ArrayList<>(this.groups.values());
		list.sort((left, right) -> {
			long leftTime = this.lastMessageTime(left.id());
			long rightTime = this.lastMessageTime(right.id());
			if (leftTime != rightTime) {
				return Long.compare(rightTime, leftTime);
			}
			return left.name().compareToIgnoreCase(right.name());
		});
		return list;
	}

	public GroupDto group(String groupId) {
		return groupId == null ? null : this.groups.get(groupId);
	}

	public GroupDto activeGroup() {
		return this.group(this.activeGroupId);
	}

	public String activeGroupId() {
		return this.activeGroupId;
	}

	public boolean hasHistory(String groupId) {
		return this.messages.containsKey(groupId);
	}

	public void setActiveGroup(String groupId) {
		this.activeGroupId = groupId;
		if (groupId != null) {
			this.unread.remove(groupId);
		}
		this.bump();
	}

	public List<MessageDto> messages(String groupId) {
		List<MessageDto> list = this.messages.get(groupId);
		return list == null ? List.of() : list;
	}

	public int unread(String groupId) {
		return this.unread.getOrDefault(groupId, 0);
	}

	public int totalUnread() {
		int total = 0;
		for (int value : this.unread.values()) {
			total += value;
		}
		return total;
	}

	public boolean isOnlineMember(GroupDto group, String name) {
		for (MemberDto member : group.members()) {
			if (member.name().equals(name)) {
				return member.online();
			}
		}
		return false;
	}

	public long lastMessageTime(String groupId) {
		List<MessageDto> list = this.messages.get(groupId);
		return list == null || list.isEmpty() ? 0L : list.get(list.size() - 1).time();
	}

	public void reset() {
		this.groups.clear();
		this.messages.clear();
		this.unread.clear();
		this.invites.clear();
		this.activeGroupId = null;
		this.selfName = "";
		this.bump();
	}

	private void bump() {
		this.version++;
	}
}
