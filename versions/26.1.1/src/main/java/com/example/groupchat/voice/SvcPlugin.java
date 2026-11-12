package com.example.groupchat.voice;

import com.example.groupchat.Constants;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simple Voice Chat plugin entrypoint. Loaded by SVC only when SVC is installed. */
public final class SvcPlugin implements VoicechatPlugin {
	private static final Logger LOGGER = LoggerFactory.getLogger(Constants.MOD_ID);

	@Override
	public String getPluginId() {
		return Constants.MOD_ID;
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
			VoiceBridge.set(new SvcVoiceBridge(event.getVoicechat()));
			LOGGER.info("[groupchat] Simple Voice Chat bridge active");
		});
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
			VoiceBridge.reset();
			LOGGER.info("[groupchat] Simple Voice Chat bridge stopped");
		});
	}
}
