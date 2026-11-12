package com.example.groupchat.client;

import com.example.groupchat.client.dev.DebugHarness;
import com.example.groupchat.client.media.GifCache;
import com.example.groupchat.client.media.GifTexture;
import com.example.groupchat.client.media.TextureCache;
import com.example.groupchat.client.ui.HubScreen;
import com.example.groupchat.client.ui.WaypointsScreen;
import com.example.groupchat.client.waypoint.SharedLocation;
import com.example.groupchat.client.waypoint.WaypointManager;
import com.example.groupchat.client.waypoint.WaypointOverlay;
import com.example.groupchat.client.waypoint.WaypointWorldRenderer;
import com.example.groupchat.client.voice.VoiceClient;
import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.MessageDto;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import com.example.groupchat.Constants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
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

	private static final boolean AUTO_CALL = Boolean.getBoolean("groupchat.devtools.autocall");
	/**
	 * The category the key bindings show up under in the vanilla controls screen.
	 *
	 * <p>Registering it here puts all of them together under "Group Chat" instead of spreading
	 * them over the vanilla categories.
	 */
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "group_chat"));
	private static KeyMapping openKey;
	private static KeyMapping talkKey;
	private static KeyMapping waypointKey;
	private static KeyMapping optionsKey;
	private static KeyMapping shareKey;
	private int autoCallTicks = -1;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		this.state.setMessageListener(this::onIncomingMessage);
		this.registerReceivers();

		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.groupchat.open",
				InputConstants.KEY_V, CATEGORY));
		talkKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.groupchat.talk",
				InputConstants.KEY_N, CATEGORY));
		waypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.groupchat.waypoints",
				InputConstants.KEY_B, CATEGORY));
		// the same screen that Mod Menu hands out, so the settings also work without Mod Menu
		optionsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.groupchat.options",
				InputConstants.KEY_O, CATEGORY));
		// shares the spot you are standing on right away, without opening the chat first
		shareKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.groupchat.share_position",
				InputConstants.KEY_M, CATEGORY));

		WaypointManager.INSTANCE.ensureLoaded();
		WaypointWorldRenderer.register();
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "waypoint_labels"),
				new WaypointOverlay());

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

		ClientPlayNetworking.registerGlobalReceiver(Payloads.GifData.TYPE,
				(payload, context) -> GifCache.instance().accept(payload.gifId(), payload.data()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Voice.TYPE,
				(payload, context) -> this.voice.onVoice(payload.sender(), payload.data()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Notice.TYPE, (payload, context) -> {
			Minecraft client = Minecraft.getInstance();
			ChatFormatting color = payload.error() ? ChatFormatting.RED : ChatFormatting.LIGHT_PURPLE;
			client.gui.hud.getChat().addClientSystemMessage(Component.literal("[Group Chat] ").withStyle(ChatFormatting.DARK_PURPLE)
					.append(Component.literal(payload.text()).withStyle(color)));
			if (payload.error()) {
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1.0F));
			}
		});
	}

	private void onTick(Minecraft client) {
		if (AUTO_CALL && client.player != null) {
			if (this.autoCallTicks < 0) {
				this.autoCallTicks = 0;
			}
			this.autoCallTicks++;
			if (this.autoCallTicks == 80) {
				client.player.connection.sendCommand("gchat create svctest");
			} else if (this.autoCallTicks == 120) {
				client.player.connection.sendCommand("gchat call svctest");
			}
		}
		while (openKey.consumeClick()) {
			if (client.player == null) {
				continue;
			}
			if (client.gui.screen() instanceof HubScreen hub) {
				hub.onClose();
			} else {
				client.setScreenAndShow(new HubScreen(client.gui.screen()));
			}
		}
		while (waypointKey.consumeClick()) {
			if (client.player != null && !(client.gui.screen() instanceof WaypointsScreen)) {
				client.setScreenAndShow(new WaypointsScreen(client.gui.screen()));
			}
		}
		while (optionsKey.consumeClick()) {
			if (client.player != null && !(client.gui.screen() instanceof GroupChatConfigScreen)) {
				client.setScreenAndShow(new GroupChatConfigScreen(client.gui.screen()));
			}
		}
		while (shareKey.consumeClick()) {
			this.sharePosition(client);
		}
		WaypointManager.INSTANCE.tick();
		WaypointWorldRenderer.warmLabels();
		this.voice.setTalking(talkKey.isDown());
		this.voice.tick();
		this.dropCallIfKicked();
		this.advanceGifs();
	}

	/**
	 * Shares the spot the player is standing on with the active group and pins it as a waypoint
	 * right away, so the marker shows up in the world without opening the chat first.
	 */
	private void sharePosition(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}
		GroupDto group = this.state.activeGroup();
		if (group == null) {
			this.notify("No group is selected, so there is nobody to share your spot with");
			return;
		}
		SharedLocation location = SharedLocation.of(client.player);
		ClientPlayNetworking.send(new Payloads.SendLocation(group.id(), location.dimension(), location.x(),
				location.y(), location.z()));
		WaypointManager.INSTANCE.addHere(client.player);
		this.notify("Shared your spot with #" + group.name());
	}

	/** Shows a short note in the chat so a key press always has some feedback. */
	private void notify(String message) {
		Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Group Chat] ")
				.withStyle(ChatFormatting.DARK_PURPLE)
				.append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
	}

	/**
	 * Steps every cached GIF forward. The frames are all in one sheet, so this only changes which
	 * rectangle is drawn and never touches the GPU.
	 */
	private void advanceGifs() {		for (GifTexture texture : GifCache.instance().textures()) {
			texture.advance(System.currentTimeMillis());
		}
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
		if (ClientConfig.get().autoApproveWaypoints()
				&& message.kind() == MessageDto.KIND_LOCATION
				&& !message.sender().equals(this.state.selfName())) {
			GroupDto autoGroup = this.state.group(message.groupId());
			WaypointManager.INSTANCE.addFromMessage(message, autoGroup == null ? null : autoGroup.name());
		}
		boolean visible = client.gui.screen() instanceof HubScreen hub && hub.isShowingGroup(message.groupId());
		if (visible || message.sender().equals(this.state.selfName())) {
			return;
		}
		GroupDto group = this.state.group(message.groupId());
		String groupName = group == null ? "group" : group.name();
		String preview = switch (message.kind()) {
			case MessageDto.KIND_IMAGE -> "[image]";
			case MessageDto.KIND_STICKER -> "[sticker]";
			case MessageDto.KIND_LOCATION -> {
				com.example.groupchat.client.waypoint.SharedLocation location =
						com.example.groupchat.client.waypoint.SharedLocation.parse(message.text());
				yield location == null ? "[coordinates]"
						: "shared coords " + location.pretty() + " (" + location.dimensionLabel() + ")";
			}
			default -> message.text();
		};
		Component line = Component.literal("#" + groupName + " ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal(message.sender() + ": ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(preview).withStyle(ChatFormatting.WHITE));
		client.gui.hud.getChat().addClientSystemMessage(line);
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
