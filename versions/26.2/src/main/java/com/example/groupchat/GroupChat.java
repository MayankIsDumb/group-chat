package com.example.groupchat;

import com.example.groupchat.net.Protocol;
import com.example.groupchat.server.GcCommands;
import com.example.groupchat.server.GroupManager;
import com.example.groupchat.server.ServerNet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint: payload types, server handlers, commands and server lifecycle wiring.
 */
public class GroupChat implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger(Constants.MOD_ID);

	@Override
	public void onInitialize() {
		Protocol.registerPayloads();
		ServerNet.register();
		GcCommands.register();

		ServerLifecycleEvents.SERVER_STARTED.register(GroupManager::bind);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> GroupManager.unbind());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			GroupManager manager = GroupManager.get();
			if (manager != null) {
				manager.tick();
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			GroupManager manager = GroupManager.get();
			if (manager != null) {
				manager.sendSnapshot(handler.getPlayer());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			GroupManager manager = GroupManager.get();
			if (manager != null) {
				manager.onDisconnect(handler.getPlayer().getGameProfile().name());
			}
		});

		LOGGER.info("[groupchat] common init complete");
	}
}
