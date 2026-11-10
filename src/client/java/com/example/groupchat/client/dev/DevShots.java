package com.example.groupchat.client.dev;

import com.example.groupchat.client.GroupChatClient;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Shared screenshot helper for the dev harness and the agent scenarios. */
public final class DevShots {
	private DevShots() {
	}

	public static void shot(Minecraft client, String name) {
		Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
			try {
				File file = new File(client.gameDirectory, "agent-" + name + ".png");
				image.writeToFile(file);
				GroupChatClient.LOGGER.info("[groupchat] screenshot written to {}", file.getAbsolutePath());
			} catch (Exception exception) {
				GroupChatClient.LOGGER.warn("[groupchat] screenshot failed", exception);
			} finally {
				image.close();
			}
		});
	}
}
