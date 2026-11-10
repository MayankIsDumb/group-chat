package com.example.groupchat.net;

import com.example.groupchat.Constants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Every packet exchanged by the mod. Payload records are plain data; the codecs below are hand
 * written so that the wire format stays explicit and easy to bound-check.
 */
public final class Payloads {
	private Payloads() {
	}

	// ------------------------------------------------------------------ helpers

	static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> packetType(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, path));
	}

	static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(BiConsumer<RegistryFriendlyByteBuf, T> encoder,
			Function<RegistryFriendlyByteBuf, T> decoder) {
		return StreamCodec.of(encoder::accept, decoder::apply);
	}

	static void writeStrings(FriendlyByteBuf buf, List<String> values) {
		int size = Math.min(values.size(), 64);
		buf.writeVarInt(size);
		for (int i = 0; i < size; i++) {
			buf.writeUtf(values.get(i), 64);
		}
	}

	static List<String> readStrings(FriendlyByteBuf buf) {
		int size = buf.readVarInt();
		if (size < 0 || size > 64) {
			throw new IllegalArgumentException("Bad list size " + size);
		}
		List<String> out = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			out.add(buf.readUtf(64));
		}
		return out;
	}

	// ---------------------------------------------------------------------- dto

	public record MemberDto(String name, boolean online, boolean inCall) {
	}

	public record GroupDto(String id, String name, String owner, List<MemberDto> members, boolean callActive) {
		public boolean isMember(String playerName) {
			for (MemberDto member : this.members) {
				if (member.name().equals(playerName)) {
					return true;
				}
			}
			return false;
		}

		public List<String> memberNames() {
			List<String> names = new ArrayList<>(this.members.size());
			for (MemberDto member : this.members) {
				names.add(member.name());
			}
			return names;
		}

		public boolean isInCall(String playerName) {
			for (MemberDto member : this.members) {
				if (member.name().equals(playerName)) {
					return member.inCall();
				}
			}
			return false;
		}
	}

	public record InviteDto(String groupId, String groupName, String inviter) {
	}

	/** A message as stored on the server and relayed to clients. */
	public record MessageDto(String id, String groupId, String sender, int kind, String text, String imageId, long time) {
		public static final int KIND_TEXT = 0;
		public static final int KIND_IMAGE = 1;
		public static final int KIND_STICKER = 2;
		public static final int KIND_SYSTEM = 3;
		/** Coordinates shared with one click; {@code text} holds {@code dimension|x|y|z}. */
		public static final int KIND_LOCATION = 4;
	}

	static void writeMessage(FriendlyByteBuf buf, MessageDto message) {
		buf.writeUtf(message.id(), 40);
		buf.writeUtf(message.groupId(), 40);
		buf.writeUtf(message.sender(), 40);
		buf.writeVarInt(message.kind());
		buf.writeUtf(message.text() == null ? "" : message.text(), Constants.MAX_TEXT_LEN);
		buf.writeUtf(message.imageId() == null ? "" : message.imageId(), 64);
		buf.writeLong(message.time());
	}

	static MessageDto readMessage(FriendlyByteBuf buf) {
		String id = buf.readUtf(40);
		String groupId = buf.readUtf(40);
		String sender = buf.readUtf(40);
		int kind = buf.readVarInt();
		String text = buf.readUtf(Constants.MAX_TEXT_LEN);
		String imageId = buf.readUtf(64);
		long time = buf.readLong();
		return new MessageDto(id, groupId, sender, kind, text, imageId, time);
	}

	static void writeGroup(FriendlyByteBuf buf, GroupDto group) {
		buf.writeUtf(group.id(), 40);
		buf.writeUtf(group.name(), Constants.MAX_GROUP_NAME_LEN);
		buf.writeUtf(group.owner(), 40);
		buf.writeBoolean(group.callActive());
		buf.writeVarInt(group.members().size());
		for (MemberDto member : group.members()) {
			buf.writeUtf(member.name(), 40);
			buf.writeBoolean(member.online());
			buf.writeBoolean(member.inCall());
		}
	}

	static GroupDto readGroup(FriendlyByteBuf buf) {
		String id = buf.readUtf(40);
		String name = buf.readUtf(Constants.MAX_GROUP_NAME_LEN);
		String owner = buf.readUtf(40);
		boolean callActive = buf.readBoolean();
		int size = buf.readVarInt();
		if (size < 0 || size > 64) {
			throw new IllegalArgumentException("Bad member count " + size);
		}
		List<MemberDto> members = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			members.add(new MemberDto(buf.readUtf(40), buf.readBoolean(), buf.readBoolean()));
		}
		return new GroupDto(id, name, owner, members, callActive);
	}

	static void writeInvite(FriendlyByteBuf buf, InviteDto invite) {
		buf.writeUtf(invite.groupId(), 40);
		buf.writeUtf(invite.groupName(), Constants.MAX_GROUP_NAME_LEN);
		buf.writeUtf(invite.inviter(), 40);
	}

	static InviteDto readInvite(FriendlyByteBuf buf) {
		return new InviteDto(buf.readUtf(40), buf.readUtf(Constants.MAX_GROUP_NAME_LEN), buf.readUtf(40));
	}

	// -------------------------------------------------------------------- c2s

	public record CreateGroup(String name, List<String> invitees) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CreateGroup> TYPE = packetType("c2s/create_group");
		public static final StreamCodec<RegistryFriendlyByteBuf, CreateGroup> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.name(), Constants.MAX_GROUP_NAME_LEN);
					writeStrings(buf, payload.invitees());
				},
				buf -> new CreateGroup(buf.readUtf(Constants.MAX_GROUP_NAME_LEN), readStrings(buf)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record InvitePlayer(String groupId, String player) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<InvitePlayer> TYPE = packetType("c2s/invite");
		public static final StreamCodec<RegistryFriendlyByteBuf, InvitePlayer> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeUtf(payload.player(), 40);
				},
				buf -> new InvitePlayer(buf.readUtf(40), buf.readUtf(40)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RespondInvite(String groupId, boolean accept) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RespondInvite> TYPE = packetType("c2s/invite_respond");
		public static final StreamCodec<RegistryFriendlyByteBuf, RespondInvite> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeBoolean(payload.accept());
				},
				buf -> new RespondInvite(buf.readUtf(40), buf.readBoolean()));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record LeaveGroup(String groupId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<LeaveGroup> TYPE = packetType("c2s/leave");
		public static final StreamCodec<RegistryFriendlyByteBuf, LeaveGroup> CODEC = codec(
				(buf, payload) -> buf.writeUtf(payload.groupId(), 40),
				buf -> new LeaveGroup(buf.readUtf(40)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record KickMember(String groupId, String player) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<KickMember> TYPE = packetType("c2s/kick");
		public static final StreamCodec<RegistryFriendlyByteBuf, KickMember> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeUtf(payload.player(), 40);
				},
				buf -> new KickMember(buf.readUtf(40), buf.readUtf(40)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record SendText(String groupId, String text) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SendText> TYPE = packetType("c2s/text");
		public static final StreamCodec<RegistryFriendlyByteBuf, SendText> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeUtf(payload.text(), Constants.MAX_TEXT_LEN);
				},
				buf -> new SendText(buf.readUtf(40), buf.readUtf(Constants.MAX_TEXT_LEN)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record SendSticker(String groupId, String stickerId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SendSticker> TYPE = packetType("c2s/sticker");
		public static final StreamCodec<RegistryFriendlyByteBuf, SendSticker> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeUtf(payload.stickerId(), 64);
				},
				buf -> new SendSticker(buf.readUtf(40), buf.readUtf(64)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UploadImage(String groupId, String imageId, byte[] data) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<UploadImage> TYPE = packetType("c2s/image_upload");
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadImage> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeUtf(payload.imageId(), 64);
					buf.writeByteArray(payload.data());
				},
				buf -> new UploadImage(buf.readUtf(40), buf.readUtf(64), buf.readByteArray(Constants.MAX_IMAGE_BYTES)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestImage(String imageId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestImage> TYPE = packetType("c2s/image_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestImage> CODEC = codec(
				(buf, payload) -> buf.writeUtf(payload.imageId(), 64),
				buf -> new RequestImage(buf.readUtf(64)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record CallJoin(String groupId, boolean join) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CallJoin> TYPE = packetType("c2s/call");
		public static final StreamCodec<RegistryFriendlyByteBuf, CallJoin> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeBoolean(payload.join());
				},
				buf -> new CallJoin(buf.readUtf(40), buf.readBoolean()));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record VoiceFrame(String groupId, byte[] data, int seq) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<VoiceFrame> TYPE = packetType("c2s/voice");
		public static final StreamCodec<RegistryFriendlyByteBuf, VoiceFrame> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeVarInt(payload.seq());
					buf.writeByteArray(payload.data());
				},
				buf -> {
					String groupId = buf.readUtf(40);
					int seq = buf.readVarInt();
					byte[] data = buf.readByteArray(Constants.VOICE_MAX_FRAME_BYTES);
					return new VoiceFrame(groupId, data, seq);
				});

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record SendLocation(String groupId, String dimension, double x, double y, double z) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SendLocation> TYPE = packetType("c2s/location");
		public static final StreamCodec<RegistryFriendlyByteBuf, SendLocation> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeUtf(payload.dimension(), 64);
					buf.writeDouble(payload.x());
					buf.writeDouble(payload.y());
					buf.writeDouble(payload.z());
				},
				buf -> new SendLocation(buf.readUtf(40), buf.readUtf(64), buf.readDouble(), buf.readDouble(), buf.readDouble()));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestHistory(String groupId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestHistory> TYPE = packetType("c2s/history_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestHistory> CODEC = codec(
				(buf, payload) -> buf.writeUtf(payload.groupId(), 40),
				buf -> new RequestHistory(buf.readUtf(40)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Sync() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Sync> TYPE = packetType("c2s/sync");
		public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.unit(new Sync());

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// -------------------------------------------------------------------- s2c

	public record Snapshot(String selfName, List<GroupDto> groups, List<InviteDto> invites) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Snapshot> TYPE = packetType("s2c/snapshot");
		public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.selfName(), 40);
					buf.writeVarInt(payload.groups().size());
					for (GroupDto group : payload.groups()) {
						writeGroup(buf, group);
					}
					buf.writeVarInt(payload.invites().size());
					for (InviteDto invite : payload.invites()) {
						writeInvite(buf, invite);
					}
				},
				buf -> {
					String selfName = buf.readUtf(40);
					int groupCount = Math.min(buf.readVarInt(), 64);
					List<GroupDto> groups = new ArrayList<>(groupCount);
					for (int i = 0; i < groupCount; i++) {
						groups.add(readGroup(buf));
					}
					int inviteCount = Math.min(buf.readVarInt(), 64);
					List<InviteDto> invites = new ArrayList<>(inviteCount);
					for (int i = 0; i < inviteCount; i++) {
						invites.add(readInvite(buf));
					}
					return new Snapshot(selfName, groups, invites);
				});

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Message(MessageDto message) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Message> TYPE = packetType("s2c/message");
		public static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC = codec(
				(buf, payload) -> writeMessage(buf, payload.message()),
				buf -> new Message(readMessage(buf)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record History(String groupId, List<MessageDto> messages) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<History> TYPE = packetType("s2c/history");
		public static final StreamCodec<RegistryFriendlyByteBuf, History> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeVarInt(payload.messages().size());
					for (MessageDto message : payload.messages()) {
						writeMessage(buf, message);
					}
				},
				buf -> {
					String groupId = buf.readUtf(40);
					int size = Math.min(buf.readVarInt(), Constants.MAX_HISTORY);
					List<MessageDto> messages = new ArrayList<>(size);
					for (int i = 0; i < size; i++) {
						messages.add(readMessage(buf));
					}
					return new History(groupId, messages);
				});

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Invite(InviteDto invite) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Invite> TYPE = packetType("s2c/invite");
		public static final StreamCodec<RegistryFriendlyByteBuf, Invite> CODEC = codec(
				(buf, payload) -> writeInvite(buf, payload.invite()),
				buf -> new Invite(readInvite(buf)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record InviteRemoved(String groupId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<InviteRemoved> TYPE = packetType("s2c/invite_removed");
		public static final StreamCodec<RegistryFriendlyByteBuf, InviteRemoved> CODEC = codec(
				(buf, payload) -> buf.writeUtf(payload.groupId(), 40),
				buf -> new InviteRemoved(buf.readUtf(40)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record GroupState(GroupDto group) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<GroupState> TYPE = packetType("s2c/group");
		public static final StreamCodec<RegistryFriendlyByteBuf, GroupState> CODEC = codec(
				(buf, payload) -> writeGroup(buf, payload.group()),
				buf -> new GroupState(readGroup(buf)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record GroupRemoved(String groupId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<GroupRemoved> TYPE = packetType("s2c/group_removed");
		public static final StreamCodec<RegistryFriendlyByteBuf, GroupRemoved> CODEC = codec(
				(buf, payload) -> buf.writeUtf(payload.groupId(), 40),
				buf -> new GroupRemoved(buf.readUtf(40)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ImageData(String imageId, byte[] data) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ImageData> TYPE = packetType("s2c/image_data");
		public static final StreamCodec<RegistryFriendlyByteBuf, ImageData> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.imageId(), 64);
					buf.writeByteArray(payload.data());
				},
				buf -> new ImageData(buf.readUtf(64), buf.readByteArray(Constants.MAX_IMAGE_BYTES)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Voice(String groupId, String sender, byte[] data, int seq) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Voice> TYPE = packetType("s2c/voice");
		public static final StreamCodec<RegistryFriendlyByteBuf, Voice> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.groupId(), 40);
					buf.writeUtf(payload.sender(), 40);
					buf.writeVarInt(payload.seq());
					buf.writeByteArray(payload.data());
				},
				buf -> {
					String groupId = buf.readUtf(40);
					String sender = buf.readUtf(40);
					int seq = buf.readVarInt();
					byte[] data = buf.readByteArray(Constants.VOICE_MAX_FRAME_BYTES);
					return new Voice(groupId, sender, data, seq);
				});

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Notice(String text, boolean error) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Notice> TYPE = packetType("s2c/notice");
		public static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = codec(
				(buf, payload) -> {
					buf.writeUtf(payload.text(), 256);
					buf.writeBoolean(payload.error());
				},
				buf -> new Notice(buf.readUtf(256), buf.readBoolean()));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
