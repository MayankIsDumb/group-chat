package com.example.groupchat.client;

import com.example.groupchat.client.media.TenorClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Lets the player paste their Tenor API key and pick the API host.
 *
 * <p>The key is optional: without one the public search page is used instead, which is slower but
 * needs no sign-up.
 */
public final class GifKeyScreen extends Screen {
	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 41, 33);

	private EditBox keyBox;
	private EditBox hostBox;

	public GifKeyScreen(Screen parent) {
		super(Component.translatable("groupchat.config.gif_key"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);

		GridLayout grid = new GridLayout();
		grid.defaultCellSetting().paddingHorizontal(6).paddingBottom(8);

		GridLayout.RowHelper rows = grid.createRowHelper(2);
		rows.addChild(new StringWidget(Component.translatable("groupchat.config.gif_key.field"), this.font),
				grid.newCellSettings().alignHorizontallyLeft());
		this.keyBox = new EditBox(this.font, 0, 0, 180, 16, Component.literal("API key"));
		this.keyBox.setMaxLength(120);
		this.keyBox.setHint(Component.literal("optional"));
		this.keyBox.setValue(ClientConfig.get().gifApiKey());
		rows.addChild(this.keyBox, grid.newCellSettings().alignHorizontallyRight());

		rows.addChild(new StringWidget(Component.translatable("groupchat.config.gif_host.field"), this.font),
				grid.newCellSettings().alignHorizontallyLeft());
		this.hostBox = new EditBox(this.font, 0, 0, 180, 16, Component.literal("API host"));
		this.hostBox.setMaxLength(120);
		this.hostBox.setValue(ClientConfig.get().gifApiHost());
		rows.addChild(this.hostBox, grid.newCellSettings().alignHorizontallyRight());

		this.layout.addToContents(grid);

		GridLayout hosts = new GridLayout();
		hosts.defaultCellSetting().paddingHorizontal(4).paddingBottom(4);
		GridLayout.RowHelper hostRows = hosts.createRowHelper(2);
		hostRows.addChild(Button.builder(Component.literal(TenorClient.GIPHY_HOST), button -> {
			ClientConfig.get().setGifApiHost(TenorClient.GIPHY_HOST);
			this.hostBox.setValue(TenorClient.GIPHY_HOST);
		}).build());
		hostRows.addChild(Button.builder(Component.literal(TenorClient.TENOR_HOST), button -> {
			ClientConfig.get().setGifApiHost(TenorClient.TENOR_HOST);
			this.hostBox.setValue(TenorClient.TENOR_HOST);
		}).build());
		this.layout.addToContents(hosts);

		this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> {
			ClientConfig.get().setGifApiKey(this.keyBox.getValue());
			ClientConfig.get().setGifApiHost(this.hostBox.getValue());
			this.onClose();
		}).width(200).build());

		this.layout.visitWidgets(widget -> this.addRenderableWidget(widget));
		this.repositionElements();
		this.setInitialFocus(this.keyBox);
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// the vanilla background is drawn by Screen.renderWithTooltipAndSubtitles
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(this.parent);
	}
}
