package com.example.groupchat.client.dev;

import com.example.groupchat.client.GroupChatClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;

/**
 * Runtime check for the Mod Menu integration.
 *
 * <p>Opens the mod list that Mod Menu installs, narrows it down to Group Chat and screenshots both
 * the list and the settings screen Mod Menu hands out for it. Everything goes through reflection so
 * the mod keeps working when Mod Menu is not installed.
 */
public final class ModMenuCheck {
	private static final String MOD_ID = "groupchat";
	private static final String SEARCH = "Group Chat";

	private static int phase;
	private static int ticks;
	private static Screen modsScreen;

	private ModMenuCheck() {
	}

	public static void init() {
		GroupChatClient.LOGGER.warn("[groupchat] mod menu check enabled");
		ClientTickEvents.END_CLIENT_TICK.register(ModMenuCheck::tick);
	}

	private static void tick(Minecraft client) {
		if (phase == 0) {
			if (client.screen instanceof TitleScreen && client.getOverlay() == null) {
				phase = 1;
				ticks = 0;
			}
			return;
		}
		ticks++;
		switch (phase) {
			case 1 -> {
				if (ticks < 10) {
					return;
				}
				phase = 2;
				ticks = 0;
				try {
					Class<?> api = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi");
					Method create = api.getMethod("createModsScreen", Screen.class);
					modsScreen = (Screen) create.invoke(null, client.screen);
					client.setScreenAndShow(modsScreen);
					log("opened the mod list: " + modsScreen.getClass().getName());
				} catch (Throwable failure) {
					fail("Mod Menu is not installed, so the mod list cannot be opened", failure);
					client.stop();
				}
			}
			case 2 -> {
				if (ticks < 20) {
					return;
				}
				phase = 3;
				ticks = 0;
				// narrow the list down to our own mod
				try {
					Object search = field(modsScreen, "searchBox");
					Method setValue = search.getClass().getMethod("setValue", String.class);
					setValue.invoke(search, SEARCH);
					Object input = modsScreen.getClass().getMethod("getSearchInput").invoke(modsScreen);
					log("search box now holds '" + input + "'");
				} catch (Throwable failure) {
					fail("the mod list search box could not be used", failure);
				}
				// does Mod Menu know that our mod has a settings screen?
				try {
					Class<?> modMenu = Class.forName("com.terraformersmc.modmenu.ModMenu");
					Object has = modMenu.getMethod("hasConfigScreen", String.class).invoke(null, MOD_ID);
					if (Boolean.TRUE.equals(has)) {
						log("PASS Mod Menu found the settings screen of '" + MOD_ID + "'");
					} else {
						fail("Mod Menu does not know a settings screen for '" + MOD_ID + "'", null);
					}
					@SuppressWarnings("unchecked")
					java.util.Map<String, Boolean> known =
							(java.util.Map<String, Boolean>) field(modsScreen, "modHasConfigScreen");
					log("settings screen known for '" + MOD_ID + "': " + known.get(MOD_ID));
				} catch (Throwable failure) {
					fail("the Mod Menu config screen lookup failed", failure);
				}
			}
			case 3 -> {
				if (ticks < 20) {
					return;
				}
				phase = 4;
				ticks = 0;
				DevShots.shot(client, "modmenu-mods");
				log("screenshot of the mod list taken");
			}
			case 4 -> {
				if (ticks < 20) {
					return;
				}
				phase = 5;
				ticks = 0;
				try {
					Class<?> modMenu = Class.forName("com.terraformersmc.modmenu.ModMenu");
					Method get = modMenu.getMethod("getConfigScreen", String.class, Screen.class);
					Screen config = (Screen) get.invoke(null, MOD_ID, modsScreen);
					if (config == null) {
						fail("Mod Menu returned no settings screen for '" + MOD_ID + "'", null);
						client.stop();
						return;
					}
					log("PASS Mod Menu handed out " + config.getClass().getName());
					client.setScreenAndShow(config);
				} catch (Throwable failure) {
					fail("the settings screen could not be opened", failure);
					client.stop();
				}
			}
			case 5 -> {
				if (ticks < 20) {
					return;
				}
				phase = 6;
				ticks = 0;
				DevShots.shot(client, "modmenu-config");
				log("screenshot of the settings screen taken");
			}
			case 6 -> {
				if (ticks < 20) {
					return;
				}
				phase = 7;
				ticks = 0;
				// the key bindings have to show up in the vanilla controls screen
				List<String> wanted = List.of("key.groupchat.open", "key.groupchat.talk",
						"key.groupchat.waypoints", "key.groupchat.options", "key.groupchat.share_position");
				List<String> known = java.util.Arrays.stream(client.options.keyMappings)
						.map(mapping -> mapping.getName()).toList();
				for (String name : wanted) {
					if (known.contains(name)) {
						log("PASS the key binding '" + name + "' is registered in the controls screen");
					} else {
						fail("the key binding '" + name + "' is missing from the controls screen", null);
					}
				}
				client.setScreenAndShow(new KeyBindsScreen(client.screen, client.options));
				log("opened the vanilla key binds screen");
			}
			case 7 -> {
				if (ticks < 30) {
					return;
				}
				phase = 8;
				ticks = 0;
				// scroll to the bottom, where the "Group Chat" category sits
				try {
					Object list = field(client.screen, "keyBindsList");
					Method scroll = list.getClass().getMethod("setScrollAmount", double.class);
					scroll.invoke(list, 10_000.0D);
					log("scrolled the key binds list to the bottom");
				} catch (Throwable failure) {
					fail("the key binds list could not be scrolled", failure);
				}
			}
			case 8 -> {
				if (ticks < 20) {
					return;
				}
				phase = 9;
				ticks = 0;
				DevShots.shot(client, "modmenu-keybinds");
				log("screenshot of the key binds screen taken");
			}
			case 9 -> {
				if (ticks > 20) {
					client.stop();
				}
			}
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	private static Object field(Object target, String name) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void log(String message) {
		GroupChatClient.LOGGER.info("[groupchat] modmenu: {}", message);
	}

	private static void fail(String message, Throwable failure) {
		if (failure != null) {
			GroupChatClient.LOGGER.error("[groupchat] modmenu: FAIL {} ({})", message, failure.toString());
		} else {
			GroupChatClient.LOGGER.error("[groupchat] modmenu: FAIL {}", message);
		}
	}
}
