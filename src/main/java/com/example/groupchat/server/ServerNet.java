package com.example.groupchat.server;

import com.example.groupchat.Constants;
import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.MessageDto;
import com.example.groupchat.net.Protocol;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side packet handlers. Fabric invokes these on the server thread.
 */
public final class ServerNet {
	private ServerNet() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Sync.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			if (manager != null) {
				manager.sendSnapshot(context.player());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.RequestHistory.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			if (manager == null) {
				return;
			}
			GroupData group = manager.find(self(context.player()), payload.groupId());
			if (group != null) {
				manager.sendHistory(context.player(), group);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.CreateGroup.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String self = self(player);
			if (!manager.rates().allow(self, "create", 3, 60_000L)) {
				notice(player, "Please slow down.", true);
				return;
			}
			String error = manager.create(self, payload.name(), payload.invitees());
			if (error != null) {
				notice(player, error, true);
			} else {
				manager.sendSnapshot(player);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.InvitePlayer.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String self = self(player);
			if (!manager.rates().allow(self, "invite", Constants.RATE_INVITE_PER_60S, 60_000L)) {
				notice(player, "You are inviting people too quickly.", true);
				return;
			}
			String error = manager.invite(payload.groupId(), self, payload.player());
			if (error != null) {
				notice(player, error, true);
			} else {
				notice(player, "Invite sent to " + payload.player() + ".", false);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.RespondInvite.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String error = manager.respond(self(player), payload.groupId(), payload.accept());
			if (error != null) {
				notice(player, error, true);
			} else {
				manager.sendSnapshot(player);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.LeaveGroup.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String error = manager.leave(self(player), payload.groupId());
			if (error != null) {
				notice(player, error, true);
			}
			manager.sendSnapshot(player);
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.KickMember.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String error = manager.kick(self(player), payload.groupId(), payload.player());
			if (error != null) {
				notice(player, error, true);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.SendText.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String self = self(player);
			GroupData group = manager.find(self, payload.groupId());
			if (group == null) {
				notice(player, "You are not in that group.", true);
				return;
			}
			if (!manager.rates().allow(self, "text", Constants.RATE_TEXT_PER_10S, 10_000L)) {
				notice(player, "You are sending messages too quickly.", true);
				return;
			}
			String text = payload.text() == null ? "" : payload.text().strip();
			if (text.isEmpty()) {
				return;
			}
			if (text.length() > Constants.MAX_TEXT_LEN) {
				text = text.substring(0, Constants.MAX_TEXT_LEN);
			}
			manager.post(group, self, MessageDto.KIND_TEXT, text, "");
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.SendSticker.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String self = self(player);
			GroupData group = manager.find(self, payload.groupId());
			if (group == null) {
				return;
			}
			if (!manager.rates().allow(self, "text", Constants.RATE_TEXT_PER_10S, 10_000L)) {
				return;
			}
			String stickerId = payload.stickerId() == null ? "" : payload.stickerId().replaceAll("[^a-z0-9_]", "");
			if (stickerId.isEmpty() || stickerId.length() > 64) {
				return;
			}
			manager.post(group, self, MessageDto.KIND_STICKER, stickerId, "");
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.SendLocation.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String self = self(player);
			GroupData group = manager.find(self, payload.groupId());
			if (group == null) {
				return;
			}
			if (!manager.rates().allow(self, "text", Constants.RATE_TEXT_PER_10S, 10_000L)) {
				return;
			}
			String dimension = payload.dimension() == null ? "" : payload.dimension().trim();
			if (!dimension.matches("[a-z0-9_.:/-]{1,64}")) {
				dimension = "minecraft:overworld";
			}
			double x = clampCoordinate(payload.x());
			double y = clampCoordinate(payload.y());
			double z = clampCoordinate(payload.z());
			String text = dimension + "|" + round(x) + "|" + round(y) + "|" + round(z);
			manager.post(group, self, MessageDto.KIND_LOCATION, text, "");
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.UploadImage.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String self = self(player);
			GroupData group = manager.find(self, payload.groupId());
			if (group == null) {
				notice(player, "You are not in that group.", true);
				return;
			}
			if (!manager.rates().allow(self, "image", Constants.RATE_IMAGE_PER_60S, 60_000L)) {
				notice(player, "You are sending images too quickly.", true);
				return;
			}
			String imageId = payload.imageId() == null ? ""
					: payload.imageId().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
			if (imageId.isEmpty() || imageId.length() > 40) {
				notice(player, "Invalid image.", true);
				return;
			}
			String error = manager.images().save(group.id, imageId, payload.data());
			if (error != null) {
				notice(player, error, true);
				return;
			}
			manager.post(group, self, MessageDto.KIND_IMAGE, "", imageId);
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.RequestImage.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String imageId = payload.imageId();
			String groupId = manager.images().groupOf(imageId);
			if (groupId == null || manager.find(self(player), groupId) == null) {
				return;
			}
			byte[] data = manager.images().load(imageId);
			if (data != null) {
				Protocol.send(player, new Payloads.ImageData(imageId, data));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.UploadGif.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String self = self(player);
			GroupData group = manager.find(self, payload.groupId());
			if (group == null) {
				notice(player, "You are not in that group.", true);
				return;
			}
			if (!manager.rates().allow(self, "gif", Constants.RATE_GIF_PER_60S, 60_000L)) {
				notice(player, "You are sending GIFs too quickly.", true);
				return;
			}
			String gifId = payload.gifId() == null ? ""
					: payload.gifId().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
			if (gifId.isEmpty() || gifId.length() > 40) {
				notice(player, "Invalid GIF.", true);
				return;
			}
			String error = manager.images().saveGif(group.id, gifId, payload.data());
			if (error != null) {
				notice(player, error, true);
				return;
			}
			manager.post(group, self, MessageDto.KIND_GIF, "", gifId);
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.RequestGif.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String gifId = payload.gifId();
			String groupId = manager.images().groupOfGif(gifId);
			if (groupId == null || manager.find(self(player), groupId) == null) {
				return;
			}
			byte[] data = manager.images().loadGif(gifId);
			if (data != null) {
				Protocol.send(player, new Payloads.GifData(gifId, data));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.CallJoin.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			if (manager != null) {
				manager.setCall(self(context.player()), payload.groupId(), payload.join());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.VoiceFrame.TYPE, (payload, context) -> {
			GroupManager manager = GroupManager.get();
			ServerPlayer player = context.player();
			if (manager == null) {
				return;
			}
			String self = self(player);
			if (!manager.rates().allow(self, "voice", Constants.VOICE_MAX_FRAMES_PER_SECOND, 1_000L)) {
				return;
			}
			byte[] data = payload.data();
			if (data == null || data.length == 0 || data.length > Constants.VOICE_MAX_FRAME_BYTES) {
				return;
			}
			GroupData group = manager.find(self, payload.groupId());
			if (group == null || !group.inCall.contains(self)) {
				return;
			}
			Payloads.Voice outgoing = new Payloads.Voice(group.id, self, data, payload.seq());
			for (String member : group.inCall) {
				if (member.equals(self)) {
					continue;
				}
				ServerPlayer target = manager.player(member);
				if (target != null) {
					Protocol.send(target, outgoing);
				}
			}
		});
	}

	static void notice(ServerPlayer player, String text, boolean error) {
		if (player != null) {
			Protocol.send(player, new Payloads.Notice(text, error));
			if (error) {
				player.sendSystemMessage(Component.literal("[GroupChat] " + text).withStyle(net.minecraft.ChatFormatting.RED));
			}
		}
	}

	static String self(ServerPlayer player) {
		return player.getGameProfile().name();
	}

	private static double clampCoordinate(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0.0D;
		}
		return Math.max(-30_000_000.0D, Math.min(30_000_000.0D, value));
	}

	private static String round(double value) {
		return Double.toString(Math.round(value * 10.0D) / 10.0D);
	}
}
