package com.example.groupchat.server;

import com.example.groupchat.Constants;
import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.MemberDto;
import com.example.groupchat.net.Payloads.MessageDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * Server-side group state. {@code public} fields keep Gson serialization trivial.
 */
public final class GroupData {
	public String id;
	public String name;
	public String owner;
	public LinkedHashSet<String> members = new LinkedHashSet<>();
	public ArrayList<MessageDto> history = new ArrayList<>();
	public boolean callActive;
	public LinkedHashSet<String> inCall = new LinkedHashSet<>();

	public GroupData() {
	}

	public GroupData(String id, String name, String owner) {
		this.id = id;
		this.name = name;
		this.owner = owner;
		this.members.add(owner);
	}

	public boolean isMember(String playerName) {
		return this.members.contains(playerName);
	}

	public void addMessage(MessageDto message) {
		this.history.add(message);
		while (this.history.size() > Constants.MAX_HISTORY) {
			this.history.remove(0);
		}
	}

	public boolean hasImage(String imageId) {
		for (MessageDto message : this.history) {
			if (imageId.equals(message.imageId())) {
				return true;
			}
		}
		return false;
	}

	public GroupDto toDto(MinecraftServer server) {
		List<MemberDto> members = new ArrayList<>(this.members.size());
		for (String name : this.members) {
			boolean online = server != null && server.getPlayerList().getPlayerByName(name) != null;
			members.add(new MemberDto(name, online, this.inCall.contains(name)));
		}
		return new GroupDto(this.id, this.name, this.owner, members, this.callActive);
	}
}
