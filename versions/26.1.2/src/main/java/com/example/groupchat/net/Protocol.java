package com.example.groupchat.net;

import com.example.groupchat.Constants;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers every payload type on both directions and wraps the send helpers.
 *
 * <p>Types must be registered on both the client and the server (this method runs in the common
 * initializer), before any receiver is attached.
 */
public final class Protocol {
	private Protocol() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s = PayloadTypeRegistry.serverboundPlay();
		PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c = PayloadTypeRegistry.clientboundPlay();

		c2s.register(Payloads.CreateGroup.TYPE, Payloads.CreateGroup.CODEC);
		c2s.register(Payloads.InvitePlayer.TYPE, Payloads.InvitePlayer.CODEC);
		c2s.register(Payloads.RespondInvite.TYPE, Payloads.RespondInvite.CODEC);
		c2s.register(Payloads.LeaveGroup.TYPE, Payloads.LeaveGroup.CODEC);
		c2s.register(Payloads.KickMember.TYPE, Payloads.KickMember.CODEC);
		c2s.register(Payloads.SendText.TYPE, Payloads.SendText.CODEC);
		c2s.register(Payloads.SendSticker.TYPE, Payloads.SendSticker.CODEC);
		c2s.register(Payloads.SendLocation.TYPE, Payloads.SendLocation.CODEC);
		c2s.registerLarge(Payloads.UploadImage.TYPE, Payloads.UploadImage.CODEC, Constants.LARGE_PAYLOAD_CHUNK);
		c2s.register(Payloads.RequestImage.TYPE, Payloads.RequestImage.CODEC);
		c2s.registerLarge(Payloads.UploadGif.TYPE, Payloads.UploadGif.CODEC, Constants.LARGE_PAYLOAD_CHUNK);
		c2s.register(Payloads.RequestGif.TYPE, Payloads.RequestGif.CODEC);
		c2s.register(Payloads.CallJoin.TYPE, Payloads.CallJoin.CODEC);
		c2s.register(Payloads.VoiceFrame.TYPE, Payloads.VoiceFrame.CODEC);
		c2s.register(Payloads.RequestHistory.TYPE, Payloads.RequestHistory.CODEC);
		c2s.register(Payloads.Sync.TYPE, Payloads.Sync.CODEC);

		s2c.register(Payloads.Snapshot.TYPE, Payloads.Snapshot.CODEC);
		s2c.register(Payloads.Message.TYPE, Payloads.Message.CODEC);
		s2c.register(Payloads.History.TYPE, Payloads.History.CODEC);
		s2c.register(Payloads.Invite.TYPE, Payloads.Invite.CODEC);
		s2c.register(Payloads.InviteRemoved.TYPE, Payloads.InviteRemoved.CODEC);
		s2c.register(Payloads.GroupState.TYPE, Payloads.GroupState.CODEC);
		s2c.register(Payloads.GroupRemoved.TYPE, Payloads.GroupRemoved.CODEC);
		s2c.registerLarge(Payloads.ImageData.TYPE, Payloads.ImageData.CODEC, Constants.LARGE_PAYLOAD_CHUNK);
		s2c.registerLarge(Payloads.GifData.TYPE, Payloads.GifData.CODEC, Constants.LARGE_PAYLOAD_CHUNK);
		s2c.register(Payloads.Voice.TYPE, Payloads.Voice.CODEC);
		s2c.register(Payloads.Notice.TYPE, Payloads.Notice.CODEC);
	}

	/** Sends a payload, skipping players that do not have the mod installed. */
	public static void send(ServerPlayer player, CustomPacketPayload payload) {
		if (ServerPlayNetworking.canSend(player, payload.type())) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
