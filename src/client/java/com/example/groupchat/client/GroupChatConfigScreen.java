package com.example.groupchat.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Client settings screen, opened from Mod Menu or with the settings key.
 *
 * <p>The screen is a plain vanilla options screen: the title in the header, the settings in the
 * middle and the done button in the footer. Every setting is a normal cycling button, so it
 * reads and behaves exactly like the rest of the game.
 */
public final class GroupChatConfigScreen extends Screen {
	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 41, 33);

	public GroupChatConfigScreen(Screen parent) {
		super(Component.translatable("groupchat.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);

		GridLayout grid = new GridLayout();
		grid.defaultCellSetting().paddingBottom(6).alignHorizontallyCenter();
		GridLayout.RowHelper rows = grid.createRowHelper(1);

		rows.addChild(CycleButton.onOffBuilder(ClientConfig.get().autoApproveWaypoints())
				.withTooltip(value -> Tooltip.create(Component.translatable("groupchat.config.auto_approve.hint")))
				.create(0, 0, 200, 20, Component.translatable("groupchat.config.auto_approve"),
						(button, value) -> ClientConfig.get().setAutoApproveWaypoints(value)));

		rows.addChild(CycleButton
				.builder(mode -> Component.translatable("groupchat.config.voice." + mode.name().toLowerCase()),
						ClientConfig.get().voiceMode())
				.withValues(ClientConfig.VoiceMode.values())
				.withTooltip(value -> Tooltip.create(Component.translatable("groupchat.config.voice.hint")))
				.create(0, 0, 200, 20, Component.translatable("groupchat.config.voice"),
						(button, value) -> ClientConfig.get().setVoiceMode(value)));

		rows.addChild(CycleButton.onOffBuilder(!ClientConfig.get().gifApiKey().isBlank())
				.withTooltip(value -> Tooltip.create(Component.translatable("groupchat.config.gif_key.hint")))
				.create(0, 0, 200, 20, Component.translatable("groupchat.config.gif_key"), (button, value) -> {
					if (value) {
						// let the player paste the key
						this.minecraft.setScreen(new GifKeyScreen(this));
					} else {
						// no key, so the public search page is used again
						ClientConfig.get().setGifApiKey("");
					}
				}));

		this.layout.addToContents(grid);
		this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());

		this.layout.visitWidgets(widget -> this.addRenderableWidget(widget));
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// the vanilla background is drawn by Screen.renderWithTooltipAndSubtitles
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
