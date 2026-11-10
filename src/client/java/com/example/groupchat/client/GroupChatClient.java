package com.example.groupchat.client;

import com.example.groupchat.client.dev.DebugHarness;
import com.example.groupchat.client.media.TextureCache;
import com.example.groupchat.client.ui.HubScreen;
import com.example.groupchat.client.voice.VoiceClient;
import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.MessageDto;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: receives server payloads, owns the chat screen and the voice client.
 */
public class GroupChatClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("groupchat-client");
	public static GroupChatClient INSTANCE;

	public final ClientState state = ClientState.INSTANCE;
	public final TextureCache textures = new TextureCache();
	public final VoiceClient voice = VoiceClient.INSTANCE;

	private static KeyMapping openKey;
	private static KeyMapping talkKey;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		this.state.setMessageListener(this::onIncomingMessage);
		this.registerReceivers();

		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.groupchat.open",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MULTIPLAYER));
		talkKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.groupchat.talk",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, KeyMapping.Category.MULTIPLAYER));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> this.onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> this.voice.shutdown());
		DebugHarness.init();
		LOGGER.info("[groupchat] client init");
	}

	private void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Snapshot.TYPE, (payload, context) -> this.state.applySnapshot(payload));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Message.TYPE, (payload, context) -> this.state.addMessage(payload.message()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.History.TYPE,
				(payload, context) -> this.state.applyHistory(payload.groupId(), payload.messages()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Invite.TYPE, (payload, context) -> this.state.addInvite(payload.invite()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.InviteRemoved.TYPE,
				(payload, context) -> this.state.removeInvite(payload.groupId()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.GroupState.TYPE, (payload, context) -> this.state.applyGroup(payload.group()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.GroupRemoved.TYPE,
				(payload, context) -> this.state.removeGroup(payload.groupId()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.ImageData.TYPE,
				(payload, context) -> this.textures.accept(payload.imageId(), payload.data()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Voice.TYPE,
				(payload, context) -> this.voice.onVoice(payload.sender(), payload.data()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Notice.TYPE, (payload, context) -> {
			Minecraft client = Minecraft.getInstance();
			ChatFormatting color = payload.error() ? ChatFormatting.RED : ChatFormatting.LIGHT_PURPLE;
			client.gui.getChat().addMessage(Component.literal("[Group Chat] ").withStyle(ChatFormatting.DARK_PURPLE)
					.append(Component.literal(payload.text()).withStyle(color)));
			if (payload.error()) {
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1.0F));
			}
		});
	}

	private void onTick(Minecraft client) {
		while (openKey.consumeClick()) {
			if (client.player == null) {
				continue;
			}
			if (client.screen instanceof HubScreen hub) {
				hub.onClose();
			} else {
				client.setScreen(new HubScreen(client.screen));
			}
		}
		this.voice.setTalking(talkKey.isDown());
		this.voice.tick();
		this.dropCallIfKicked();
	}

	/** Stops the microphone if the player is no longer part of the call (kicked, group gone, call ended). */
	private void dropCallIfKicked() {
		String callGroup = this.voice.callGroupId();
		if (callGroup == null) {
			return;
		}
		GroupDto group = this.state.group(callGroup);
		if (group == null || !group.isInCall(this.state.selfName())) {
			this.voice.leave();
		}
	}

	private void onDisconnect() {
		// DISCONNECT fires on a netty thread; releasing GL textures must happen on the render thread.
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			this.state.reset();
			this.textures.reset();
			this.voice.leave();
		});
	}

	private void onIncomingMessage(MessageDto message) {
		Minecraft client = Minecraft.getInstance();
		boolean visible = client.screen instanceof HubScreen hub && hub.isShowingGroup(message.groupId());
		if (visible || message.sender().equals(this.state.selfName())) {
			return;
		}
		GroupDto group = this.state.group(message.groupId());
		String groupName = group == null ? "group" : group.name();
		String preview = switch (message.kind()) {
			case MessageDto.KIND_IMAGE -> "[image]";
			case MessageDto.KIND_STICKER -> "[sticker]";
			default -> message.text();
		};
		Component line = Component.literal("#" + groupName + " ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal(message.sender() + ": ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(preview).withStyle(ChatFormatting.WHITE));
		client.gui.getChat().addMessage(line);
		client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6F, 0.5F));
	}

	/** Asks the server for a fresh group/invite snapshot (sent when the hub opens). */
	public void refreshSnapshot() {
		if (ClientPlayNetworking.canSend(Payloads.Sync.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Sync());
		}
	}

	/** Fetches message history the first time a group is opened. */
	public void ensureHistory(String groupId) {
		if (groupId == null || this.state.hasHistory(groupId)) {
			return;
		}
		if (ClientPlayNetworking.canSend(Payloads.RequestHistory.TYPE)) {
			ClientPlayNetworking.send(new Payloads.RequestHistory(groupId));
		}
	}
}
