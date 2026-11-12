package com.example.groupchat.client.ui;

import com.example.groupchat.Constants;
import com.example.groupchat.client.ClientState;
import com.example.groupchat.client.GroupChatClient;
import com.example.groupchat.client.media.GifCache;
import com.example.groupchat.client.media.GifTexture;
import com.example.groupchat.client.media.ImageUtil;
import com.example.groupchat.client.media.StickerAssets;
import com.example.groupchat.client.media.TextureCache;
import com.example.groupchat.client.voice.VoiceClient;
import com.example.groupchat.client.waypoint.SharedLocation;
import com.example.groupchat.client.waypoint.WaypointManager;
import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.GroupDto;
import com.example.groupchat.net.Payloads.InviteDto;
import com.example.groupchat.net.Payloads.MemberDto;
import com.example.groupchat.net.Payloads.MessageDto;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.multiplayer.PlayerInfo;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.FormattedCharSequence;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.Util;

/**
 * The whole chat experience in one screen: group list on the left, conversation (or one of the
 * create / invite / member panels) on the right.
 */
public class HubScreen extends Screen {
	private static final int MARGIN = 6;
	private static final int LEFT_W = 132;
	private static final int HEADER_H = 30;
	private static final int COMPOSER_H = 30;
	private static final int ROW_H = 20;

	private enum Mode {
		CHAT, CREATE, INVITES, MEMBERS
	}

	private record Click(int x, int y, int width, int height, Runnable action) {
	}

	private final Screen parent;
	private final ClientState state = ClientState.INSTANCE;
	private final List<Click> clicks = new ArrayList<>();
	private final Set<String> picked = new LinkedHashSet<>();
	private final List<String> onlineNames = new ArrayList<>();

	private Mode mode = Mode.CHAT;
	private EditBox composer;
	private EditBox createName;
	private EditBox inviteName;
	private double scroll;
	private double leftScroll;
	private String zoomImageId;
	private String statusMessage;
	private long statusUntil;

	public HubScreen(Screen parent) {
		super(Component.literal("Group Chat"));
		this.parent = parent;
	}

	// --------------------------------------------------------------- lifecycle

	@Override
	protected void init() {
		this.composer = new EditBox(this.font, 0, 0, 100, 16, Component.literal("Message"));
		this.composer.setMaxLength(Constants.MAX_TEXT_LEN);
		this.composer.setHint(Component.literal("Message").withStyle(ChatFormatting.DARK_GRAY));
		this.addRenderableWidget(this.composer);

		this.createName = new EditBox(this.font, 0, 0, 150, 16, Component.literal("Group name"));
		this.createName.setMaxLength(Constants.MAX_GROUP_NAME_LEN);
		this.createName.setHint(Component.literal("Group name").withStyle(ChatFormatting.DARK_GRAY));
		this.addRenderableWidget(this.createName);

		this.inviteName = new EditBox(this.font, 0, 0, 120, 16, Component.literal("Player"));
		this.inviteName.setMaxLength(16);
		this.inviteName.setHint(Component.literal("Player name").withStyle(ChatFormatting.DARK_GRAY));
		this.addRenderableWidget(this.inviteName);

		this.refreshOnlineNames();
		this.layoutWidgets();
		this.setInitialFocus(this.composer);
		GroupChatClient.INSTANCE.refreshSnapshot();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	public boolean isShowingGroup(String groupId) {
		return this.mode == Mode.CHAT && groupId != null && groupId.equals(this.state.activeGroupId());
	}

	private void refreshOnlineNames() {
		this.onlineNames.clear();
		if (this.minecraft != null && this.minecraft.getConnection() != null) {
			for (PlayerInfo info : this.minecraft.getConnection().getOnlinePlayers()) {
				this.onlineNames.add(info.getProfile().name());
			}
		}
		this.onlineNames.remove(this.state.selfName());
		this.onlineNames.sort(String.CASE_INSENSITIVE_ORDER);
	}

	// --------------------------------------------------------------- geometry

	private int panelX() {
		return MARGIN;
	}

	private int panelY() {
		return MARGIN;
	}

	private int panelW() {
		return this.width - MARGIN * 2;
	}

	private int panelH() {
		return this.height - MARGIN * 2;
	}

	private int rightX() {
		return this.panelX() + LEFT_W + 1;
	}

	private int rightW() {
		return this.panelW() - LEFT_W - 1;
	}

	private int listTop() {
		return this.panelY() + HEADER_H;
	}

	private int composerTop() {
		return this.panelY() + this.panelH() - COMPOSER_H;
	}

	private void layoutWidgets() {
		int by = this.composerTop() + 6;
		int buttonW = 32;
		int iconW = 22;
		int coordsW = 46;
		int x = this.rightX() + 6;
		int width = this.rightW() - 12 - buttonW - iconW * 3 - coordsW - 12;
		if (this.composer != null) {
			this.composer.setX(x);
			this.composer.setY(by);
			this.composer.setWidth(Math.max(40, width));
			this.composer.setHeight(16);
			boolean chat = this.mode == Mode.CHAT && this.state.activeGroup() != null;
			this.composer.visible = chat;
			this.composer.active = chat;
		}
		if (this.createName != null) {
			boolean create = this.mode == Mode.CREATE;
			this.createName.setX(this.rightX() + 12);
			this.createName.setY(this.listTop() + 30);
			this.createName.setWidth(Math.min(220, this.rightW() - 24));
			this.createName.setHeight(16);
			this.createName.visible = create;
			this.createName.active = create;
		}
		if (this.inviteName != null) {
			boolean members = this.mode == Mode.MEMBERS;
			this.inviteName.setX(this.rightX() + 12);
			this.inviteName.setY(this.panelY() + this.panelH() - 62);
			this.inviteName.setWidth(Math.max(60, this.rightW() - 24 - 56));
			this.inviteName.setHeight(16);
			this.inviteName.visible = members;
			this.inviteName.active = members;
		}
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xC808080C);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.clicks.clear();
		this.layoutWidgets();
		if (this.state.activeGroupId() == null) {
			List<GroupDto> available = this.state.groups();
			if (!available.isEmpty()) {
				this.selectGroup(available.get(0).id());
			}
		}
		UiKit.panel(graphics, this.panelX(), this.panelY(), this.panelW(), this.panelH());
		graphics.fill(this.panelX() + LEFT_W, this.panelY(), this.panelX() + LEFT_W + 1,
				this.panelY() + this.panelH(), UiKit.BORDER);

		this.renderSidebar(graphics, mouseX, mouseY);

		GroupDto group = this.state.activeGroup();
		switch (this.mode) {
			case CHAT -> this.renderChat(graphics, mouseX, mouseY, group);
			case CREATE -> this.renderCreate(graphics, mouseX, mouseY);
			case INVITES -> this.renderInvites(graphics, mouseX, mouseY);
			case MEMBERS -> this.renderMembers(graphics, mouseX, mouseY, group);
		}

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (this.statusMessage != null && System.currentTimeMillis() < this.statusUntil) {
			graphics.centeredText(this.font, this.statusMessage, this.rightX() + this.rightW() / 2,
					this.composerTop() - 12, UiKit.TEXT_DIM);
		}

		if (this.zoomImageId != null) {
			this.renderZoom(graphics, mouseX, mouseY);
		}
	}

	private void renderSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = this.panelX();
		int y = this.panelY();
		graphics.text(this.font, "GROUPS", x + 10, y + 9, UiKit.TEXT_FAINT, false);

		int top = y + HEADER_H - 6;
		int bottom = y + this.panelH() - 56;
		List<GroupDto> groups = this.state.groups();
		int contentHeight = groups.size() * ROW_H;
		double maxScroll = Math.max(0, contentHeight - (bottom - top));
		this.leftScroll = Math.max(0, Math.min(this.leftScroll, maxScroll));

		graphics.enableScissor(x + 4, top, x + LEFT_W - 4, bottom);
		int rowY = top - (int) this.leftScroll;
		for (GroupDto group : groups) {
			boolean active = group.id().equals(this.state.activeGroupId()) && this.mode == Mode.CHAT;
			boolean hover = UiKit.hovered(x + 4, rowY, LEFT_W - 8, ROW_H - 2, mouseX, mouseY);
			if (active) {
				UiKit.fill(graphics, x + 4, rowY, LEFT_W - 8, ROW_H - 2, UiKit.ACCENT_SOFT);
				graphics.fill(x + 4, rowY, x + 6, rowY + ROW_H - 2, UiKit.ACCENT);
			} else if (hover) {
				UiKit.fill(graphics, x + 4, rowY, LEFT_W - 8, ROW_H - 2, 0x30FFFFFF);
			}
			int unread = this.state.unread(group.id());
			String label = UiKit.trim(this.font, "# " + group.name(), LEFT_W - 46);
			graphics.text(this.font, label, x + 11, rowY + 6, active ? UiKit.TEXT : UiKit.TEXT_DIM, false);
			if (unread > 0) {
				String badge = unread > 9 ? "9+" : Integer.toString(unread);
				int badgeW = this.font.width(badge) + 6;
				UiKit.fill(graphics, x + LEFT_W - 12 - badgeW, rowY + 5, badgeW, 11, UiKit.ACCENT);
				graphics.text(this.font, badge, x + LEFT_W - 9 - badgeW, rowY + 7, 0xFFFFFFFF, false);
			}
			final String groupId = group.id();
			this.addClick(x + 4, rowY, LEFT_W - 8, ROW_H - 2, () -> this.selectGroup(groupId));
			rowY += ROW_H;
		}
		graphics.disableScissor();

		// footer actions
		int invites = this.state.invites().size();
		int invitesY = y + this.panelH() - 50;
		boolean invitesHover = UiKit.button(graphics, this.font, x + 6, invitesY, LEFT_W - 12, 18,
				Component.literal("Invites (" + invites + ")"), mouseX, mouseY, false, invites > 0);
		if (invites > 0) {
			this.addClick(x + 6, invitesY, LEFT_W - 12, 18, () -> this.setMode(Mode.INVITES));
		}
		int newY = y + this.panelH() - 28;
		UiKit.button(graphics, this.font, x + 6, newY, LEFT_W - 12, 18, Component.literal("+ New group"), mouseX, mouseY, true);
		this.addClick(x + 6, newY, LEFT_W - 12, 18, () -> this.setMode(Mode.CREATE));
	}

	private void renderChat(GuiGraphicsExtractor graphics, int mouseX, int mouseY, GroupDto group) {
		int x = this.rightX();
		int width = this.rightW();

		if (group == null) {
			graphics.centeredText(this.font, "No conversation selected", x + width / 2,
					this.panelY() + this.panelH() / 2 - 10, UiKit.TEXT_DIM);
			graphics.centeredText(this.font, "Create a group or accept an invite to start chatting",
					x + width / 2, this.panelY() + this.panelH() / 2 + 4, UiKit.TEXT_FAINT);
			return;
		}

		// header: title + meta on the left, actions on the right
		boolean inCall = group.isInCall(this.state.selfName());
		int callW = 82;
		int membersW = 56;
		int buttonY = this.panelY() + 6;
		int membersX = x + width - 8 - membersW;
		int callX = membersX - 4 - callW;
		int titleMax = Math.max(24, callX - (x + 12) - 8);

		graphics.text(this.font, UiKit.trim(this.font, "# " + group.name(), titleMax), x + 12,
				this.panelY() + 5, UiKit.TEXT, false);

		int inCallCount = 0;
		for (MemberDto member : group.members()) {
			if (member.inCall()) {
				inCallCount++;
			}
		}
		StringBuilder meta = new StringBuilder(group.members().size() + " members");
		if (group.callActive()) {
			meta.append(" · ").append(inCallCount).append(" in call");
		}
		VoiceClient voiceNow = GroupChatClient.INSTANCE.voice;
		if (voiceNow.callGroupId() != null && voiceNow.callGroupId().equals(group.id())) {
			meta.append(" · ").append(voiceNow.isMuted() ? "muted" : (voiceNow.isTransmitting() ? "talking" : "hold N to talk"));
		}
		graphics.text(this.font, UiKit.trim(this.font, meta.toString(), titleMax), x + 12,
				this.panelY() + 17, UiKit.TEXT_FAINT, false);

		UiKit.button(graphics, this.font, membersX, buttonY, membersW, 16, Component.literal("Members"), mouseX, mouseY, false);
		this.addClick(membersX, buttonY, membersW, 16, () -> this.setMode(Mode.MEMBERS));
		String callLabel = inCall ? "Leave call" : (group.callActive() ? "Join call" : "Start call");
		UiKit.button(graphics, this.font, callX, buttonY, callW, 16, Component.literal(callLabel), mouseX, mouseY, inCall || !group.callActive() ? false : true);
		this.addClick(callX, buttonY, callW, 16, () -> this.toggleCall(group));

		// messages
		int areaTop = this.listTop();
		int areaBottom = this.composerTop() - 2;
		int innerX = x + 10;
		int innerW = width - 20;
		List<MessageDto> messages = this.state.messages(group.id());

		if (messages.isEmpty()) {
			graphics.centeredText(this.font, "This is the start of #" + group.name(), x + width / 2,
					(areaTop + areaBottom) / 2 - 4, UiKit.TEXT_FAINT);
		}

		int total = 0;
		for (MessageDto message : messages) {
			total += this.measureMessage(message, innerW);
		}
		int viewHeight = areaBottom - areaTop;
		double maxScroll = Math.max(0, total - viewHeight);
		this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));

		graphics.enableScissor(x + 1, areaTop, x + width - 1, areaBottom);
		int y = areaBottom - total + (int) this.scroll;
		for (MessageDto message : messages) {
			int height = this.measureMessage(message, innerW);
			if (y + height >= areaTop && y <= areaBottom) {
				this.drawMessage(graphics, message, innerX, y, innerW, mouseX, mouseY);
			}
			y += height;
		}
		graphics.disableScissor();

		if (maxScroll > 0) {
			int barHeight = Math.max(18, (int) (viewHeight * (viewHeight / (double) total)));
			int travel = viewHeight - barHeight;
			double progress = 1.0D - (this.scroll / maxScroll); // 1 = newest (bottom), 0 = oldest (top)
			int barY = areaTop + (int) Math.round(travel * progress);
			UiKit.fill(graphics, x + width - 4, barY, 2, barHeight, 0x60FFFFFF);
		}

		// composer buttons
		int composerY = this.composerTop() + 6;
		int sendW = 32;
		int iconW = 22;
		int coordsW = 46;
		int sendX = x + width - 6 - sendW;
		int stickerX = sendX - 4 - iconW;
		int imageX = stickerX - 4 - iconW;
		int gifX = imageX - 4 - iconW;
		int coordsX = gifX - 4 - coordsW;
		boolean canSend = !this.composer.getValue().isBlank();
		UiKit.button(graphics, this.font, sendX, composerY, sendW, 16, Component.literal("Send"), mouseX, mouseY, true, canSend);
		this.addClick(sendX, composerY, sendW, 16, this::sendText);
		UiKit.button(graphics, this.font, imageX, composerY, iconW, 16, Component.literal("Img"), mouseX, mouseY, false);
		this.addClick(imageX, composerY, iconW, 16, () -> this.minecraft.setScreenAndShow(new ImagePickerScreen(this, this::sendImageFile)));
		UiKit.button(graphics, this.font, stickerX, composerY, iconW, 16, Component.literal("Stk"), mouseX, mouseY, false);
		this.addClick(stickerX, composerY, iconW, 16, () -> this.minecraft.setScreenAndShow(new StickerPickerScreen(this, this::sendSticker)));
		UiKit.button(graphics, this.font, gifX, composerY, iconW, 16, Component.literal("Gif"), mouseX, mouseY, false);
		this.addClick(gifX, composerY, iconW, 16, () -> this.minecraft.setScreenAndShow(new GifPickerScreen(this, this::sendGif)));
		boolean canShare = this.minecraft != null && this.minecraft.player != null;
		UiKit.button(graphics, this.font, coordsX, composerY, coordsW, 16, Component.literal("Coords"), mouseX, mouseY, false, canShare);
		if (canShare) {
			this.addClick(coordsX, composerY, coordsW, 16, this::sendLocation);
		}

	}

	private void renderCreate(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = this.rightX() + 12;
		int width = this.rightW() - 24;
		graphics.text(this.font, "New group", x, this.panelY() + 9, UiKit.TEXT, false);
		graphics.text(this.font, "Name", x, this.listTop() + 18, UiKit.TEXT_DIM, false);

		int listY = this.listTop() + 56;
		graphics.text(this.font, "Invite online players", x, listY, UiKit.TEXT_DIM, false);
		listY += 14;

		int rows = Math.min(this.onlineNames.size(), Math.max(1, (this.composerTop() - listY - 30) / 16));
		for (int i = 0; i < rows; i++) {
			String name = this.onlineNames.get(i);
			boolean selected = this.picked.contains(name);
			boolean hover = UiKit.hovered(x, listY, width, 14, mouseX, mouseY);
			if (hover) {
				UiKit.fill(graphics, x, listY, width, 14, 0x28FFFFFF);
			}
			UiKit.fill(graphics, x + 2, listY + 3, 8, 8, selected ? UiKit.ACCENT : 0xFF2A2A36);
			UiKit.outline(graphics, x + 2, listY + 3, 8, 8, selected ? UiKit.ACCENT : UiKit.BORDER);
			graphics.text(this.font, name, x + 16, listY + 3, selected ? UiKit.TEXT : UiKit.TEXT_DIM, false);
			final String pickedName = name;
			this.addClick(x, listY, width, 14, () -> {
				if (!this.picked.remove(pickedName)) {
					this.picked.add(pickedName);
				}
			});
			listY += 16;
		}
		if (this.onlineNames.isEmpty()) {
			graphics.text(this.font, "Nobody else is online right now", x, listY, UiKit.TEXT_FAINT, false);
		}

		int buttonY = this.composerTop() + 6;
		UiKit.button(graphics, this.font, x, buttonY, 70, 16, Component.literal("Create"), mouseX, mouseY, true,
				!this.createName.getValue().isBlank());
		this.addClick(x, buttonY, 70, 16, this::createGroup);
		UiKit.button(graphics, this.font, x + 76, buttonY, 60, 16, Component.literal("Cancel"), mouseX, mouseY, false);
		this.addClick(x + 76, buttonY, 60, 16, () -> this.setMode(Mode.CHAT));
	}

	private void renderInvites(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = this.rightX() + 12;
		int width = this.rightW() - 24;
		List<InviteDto> invites = this.state.invites();
		graphics.text(this.font, "Invites", x, this.panelY() + 9, UiKit.TEXT, false);
		int y = this.listTop() + 6;
		if (invites.isEmpty()) {
			graphics.text(this.font, "No pending invites. Ask a friend to /gchat invite you.", x, y, UiKit.TEXT_FAINT, false);
			return;
		}
		for (InviteDto invite : invites) {
			UiKit.fill(graphics, x, y, width, 34, UiKit.PANEL_SOFT);
			UiKit.outline(graphics, x, y, width, 34, UiKit.BORDER);
			graphics.text(this.font, "# " + invite.groupName(), x + 8, y + 6, UiKit.TEXT, false);
			graphics.text(this.font, "from " + invite.inviter(), x + 8, y + 18, UiKit.TEXT_FAINT, false);
			int buttonY = y + 9;
			int declineX = x + width - 8 - 58;
			int acceptX = declineX - 4 - 58;
			UiKit.button(graphics, this.font, acceptX, buttonY, 58, 16, Component.literal("Accept"), mouseX, mouseY, true);
			this.addClick(acceptX, buttonY, 58, 16, () -> this.respond(invite, true));
			UiKit.button(graphics, this.font, declineX, buttonY, 58, 16, Component.literal("Decline"), mouseX, mouseY, false);
			this.addClick(declineX, buttonY, 58, 16, () -> this.respond(invite, false));
			y += 38;
		}
	}

	private void renderMembers(GuiGraphicsExtractor graphics, int mouseX, int mouseY, GroupDto group) {
		int x = this.rightX() + 12;
		int width = this.rightW() - 24;
		if (group == null) {
			graphics.text(this.font, "No group selected", x, this.panelY() + 9, UiKit.TEXT_FAINT, false);
			return;
		}
		graphics.text(this.font, "Members of #" + group.name(), x, this.panelY() + 9, UiKit.TEXT, false);
		graphics.text(this.font, "Owner: " + group.owner(), x + width - this.font.width("Owner: " + group.owner()),
				this.panelY() + 9, UiKit.TEXT_FAINT, false);

		int y = this.listTop() + 4;
		boolean selfIsOwner = group.owner().equals(this.state.selfName());
		VoiceClient voice = GroupChatClient.INSTANCE.voice;
		for (MemberDto member : group.members()) {
			UiKit.fill(graphics, x, y, width, 18, 0x20000000);
			int dotColor = member.online() ? UiKit.GOOD : UiKit.TEXT_FAINT;
			if (voice.isSpeaking(member.name()) && member.inCall()) {
				dotColor = UiKit.ACCENT;
			}
			UiKit.fill(graphics, x + 4, y + 7, 4, 4, dotColor);
			String label = member.name().equals(this.state.selfName()) ? "You" : member.name();
			if (member.inCall()) {
				label += "  ·  call";
			}
			if (!member.online()) {
				label += "  ·  offline";
			}
			graphics.text(this.font, label, x + 12, y + 5, member.online() ? UiKit.TEXT : UiKit.TEXT_DIM, false);
			if (selfIsOwner && !member.name().equals(this.state.selfName())) {
				int kickX = x + width - 20;
				boolean hover = UiKit.hovered(kickX, y + 1, 16, 16, mouseX, mouseY);
				UiKit.fill(graphics, kickX, y + 1, 16, 16, hover ? 0x60E06C75 : 0x30000000);
				graphics.centeredText(this.font, "x", kickX + 8, y + 5, hover ? 0xFFFFFFFF : UiKit.BAD);
				final String target = member.name();
				this.addClick(kickX, y + 1, 16, 16, () -> this.kick(group, target));
			}
			y += 20;
		}

		UiKit.button(graphics, this.font, x + width - 52, this.panelY() + this.panelH() - 62, 52, 16,
				Component.literal("Invite"), mouseX, mouseY, true, !this.inviteName.getValue().isBlank());
		this.addClick(x + width - 52, this.panelY() + this.panelH() - 62, 52, 16, () -> this.invitePlayer(group));
		UiKit.button(graphics, this.font, x, this.panelY() + this.panelH() - 36, 76, 16,
				Component.literal("Leave group"), mouseX, mouseY, false);
		this.addClick(x, this.panelY() + this.panelH() - 36, 76, 16, () -> this.leaveGroup(group));
		UiKit.button(graphics, this.font, x + 82, this.panelY() + this.panelH() - 36, 60, 16,
				Component.literal("Back"), mouseX, mouseY, false);
		this.addClick(x + 82, this.panelY() + this.panelH() - 36, 60, 16, () -> this.setMode(Mode.CHAT));
	}

	private void renderZoom(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(0, 0, this.width, this.height, 0xE0000000);
		TextureCache.CachedTexture entry = GroupChatClient.INSTANCE.textures.get(this.zoomImageId);
		if (entry == null) {
			graphics.centeredText(this.font, "Loading image...", this.width / 2, this.height / 2 - 4, UiKit.TEXT_DIM);
			return;
		}
		int maxWidth = this.width - 40;
		int maxHeight = this.height - 60;
		float scale = Math.min((float) maxWidth / entry.width(), (float) maxHeight / entry.height());
		int drawWidth = Math.max(1, (int) (entry.width() * scale));
		int drawHeight = Math.max(1, (int) (entry.height() * scale));
		int drawX = (this.width - drawWidth) / 2;
		int drawY = (this.height - drawHeight) / 2;
		UiKit.blitTexture(graphics, entry.location(), drawX, drawY, drawWidth, drawHeight, entry.width(), entry.height());
		graphics.centeredText(this.font, "Click anywhere or press Esc to close", this.width / 2,
				this.height - 24, UiKit.TEXT_DIM);
	}

	// --------------------------------------------------------------- messages

	private int measureMessage(MessageDto message, int width) {
		return switch (message.kind()) {
			case MessageDto.KIND_SYSTEM -> 16;
			case MessageDto.KIND_STICKER -> 76;
			case MessageDto.KIND_LOCATION -> 50;
			case MessageDto.KIND_IMAGE -> {
				int[] size = this.thumbSize(message.imageId(), Math.min(width - 70, 260), 190);
				yield size[1] + 24;
			}
			case MessageDto.KIND_GIF -> {
				int[] size = this.gifSize(message.imageId(), Math.min(width - 70, 260), 190);
				yield size[1] + 24;
			}
			default -> {
				List<FormattedCharSequence> lines = this.font.split(Component.literal(message.text()), width - 16);
				yield 14 + lines.size() * 10 + 4;
			}
		};
	}

	private void drawMessage(GuiGraphicsExtractor graphics, MessageDto message, int x, int y, int width, int mouseX, int mouseY) {
		if (message.kind() == MessageDto.KIND_SYSTEM) {
			graphics.centeredText(this.font, message.text(), x + width / 2, y + 4, UiKit.TEXT_FAINT);
			return;
		}
		boolean mine = message.sender().equals(this.state.selfName());
		String sender = mine ? "You" : message.sender();
		int nameColor = mine ? UiKit.ACCENT : senderColor(message.sender());
		graphics.text(this.font, sender, x, y + 2, nameColor, false);
		String time = UiKit.formatTime(message.time());
		graphics.text(this.font, time, x + this.font.width(sender) + 6, y + 2, UiKit.TEXT_FAINT, false);

		int contentY = y + 13;
		switch (message.kind()) {
			case MessageDto.KIND_IMAGE -> this.drawImage(graphics, message, x, contentY, width, mouseX, mouseY);
			case MessageDto.KIND_GIF -> this.drawGif(graphics, message, x, contentY, width, mouseX, mouseY);
			case MessageDto.KIND_LOCATION -> this.drawLocation(graphics, message, x, contentY, width, mouseX, mouseY);
			case MessageDto.KIND_STICKER -> {
				UiKit.fill(graphics, x - 2, contentY - 2, 68, 68, 0x30000000);
				UiKit.blitTexture(graphics, StickerAssets.texture(message.text()), x + 1, contentY + 1, 64, 64, 128, 128);
			}
			default -> {
				List<FormattedCharSequence> lines = this.font.split(Component.literal(message.text()), width - 16);
				int lineY = contentY;
				for (FormattedCharSequence line : lines) {
					graphics.text(this.font, line, x, lineY, UiKit.TEXT, false);
					lineY += 10;
				}
			}
		}
	}

	private void drawImage(GuiGraphicsExtractor graphics, MessageDto message, int x, int y, int width, int mouseX, int mouseY) {
		TextureCache.CachedTexture entry = GroupChatClient.INSTANCE.textures.get(message.imageId());
		int maxWidth = Math.min(width - 70, 260);
		int[] size = this.thumbSize(message.imageId(), maxWidth, 190);
		boolean hover = UiKit.hovered(x, y, size[0], size[1], mouseX, mouseY);
		UiKit.fill(graphics, x - 2, y - 2, size[0] + 4, size[1] + 4, hover ? 0x50FFFFFF : 0x30000000);
		if (entry == null) {
			graphics.centeredText(this.font, "loading image...", x + size[0] / 2, y + size[1] / 2 - 4, UiKit.TEXT_FAINT);
			return;
		}
		UiKit.blitTexture(graphics, entry.location(), x, y, size[0], size[1], entry.width(), entry.height());
		final String imageId = message.imageId();
		this.addClick(x, y, size[0], size[1], () -> this.zoomImageId = imageId);
	}

	/** Draws an animated GIF, using the frame that is current right now. */
	private void drawGif(GuiGraphicsExtractor graphics, MessageDto message, int x, int y, int width, int mouseX, int mouseY) {
		GifTexture texture = GifCache.instance().get(message.imageId());
		if (texture == null) {
			graphics.centeredText(this.font, "loading GIF...", x + width / 2, y + 6, UiKit.TEXT_DIM);
			return;
		}
		int maxWidth = Math.min(width - 70, 260);
		int maxHeight = 190;
		float scale = Math.min((float) maxWidth / texture.frameWidth(),
				(float) maxHeight / texture.frameHeight());
		scale = Math.min(scale, 1.0F);
		int drawWidth = Math.max(1, Math.round(texture.frameWidth() * scale));
		int drawHeight = Math.max(1, Math.round(texture.frameHeight() * scale));
		UiKit.fill(graphics, x - 2, y - 2, drawWidth + 4, drawHeight + 4, 0x50000000);
		UiKit.blitTextureRegion(graphics, texture.location(), x, y, drawWidth, drawHeight,
				texture.sourceX(), 0, texture.frameWidth(), texture.frameHeight(),
				texture.sheetWidth(), texture.sheetHeight());
	}

	/** The on-screen size of a GIF, or a placeholder while it is still loading. */
	private int[] gifSize(String gifId, int maxWidth, int maxHeight) {
		GifTexture texture = GifCache.instance().get(gifId);
		if (texture == null) {
			return new int[]{Math.min(maxWidth, 180), 72};
		}
		float scale = Math.min((float) maxWidth / texture.frameWidth(), (float) maxHeight / texture.frameHeight());
		scale = Math.min(scale, 1.0F);
		return new int[]{Math.max(1, Math.round(texture.frameWidth() * scale)),
				Math.max(1, Math.round(texture.frameHeight() * scale))};
	}

	private void drawLocation(GuiGraphicsExtractor graphics, MessageDto message, int x, int y, int width, int mouseX, int mouseY) {
		SharedLocation location = SharedLocation.parse(message.text());
		if (location == null) {
			graphics.text(this.font, message.text(), x, y, UiKit.TEXT, false);
			return;
		}
		int boxWidth = Math.min(width - 70, 214);
		boolean hover = UiKit.hovered(x - 2, y - 2, boxWidth + 4, 36, mouseX, mouseY);
		UiKit.fill(graphics, x - 2, y - 2, boxWidth + 4, 36, hover ? 0x554B3FE0 : 0x26FFFFFF);
		UiKit.outline(graphics, x - 2, y - 2, boxWidth + 4, 36, hover ? UiKit.ACCENT : UiKit.BORDER);
		// little map pin
		UiKit.fill(graphics, x + 5, y + 3, 8, 8, UiKit.ACCENT);
		UiKit.fill(graphics, x + 7, y + 11, 4, 3, UiKit.ACCENT);
		graphics.text(this.font, location.pretty(), x + 17, y + 1, UiKit.TEXT, false);
		double distance = location.distanceFrom(this.minecraft);
		String meta = location.dimensionLabel() + (distance >= 0 ? "  ·  " + Math.round(distance) + " m away" : "  ·  other dimension");
		graphics.text(this.font, UiKit.trim(this.font, meta, boxWidth - 20), x + 17, y + 11, UiKit.TEXT_DIM, false);
		String hint = hover ? "> click to create waypoint" : "click to create waypoint";
		graphics.text(this.font, UiKit.trim(this.font, hint, boxWidth - 20), x + 17, y + 21, hover ? UiKit.ACCENT : UiKit.TEXT_FAINT, false);
		this.addClick(x - 2, y - 2, boxWidth + 4, 36, () -> this.createWaypoint(message, location));
	}

	private void createWaypoint(MessageDto message, SharedLocation location) {
		GroupDto group = this.state.group(message.groupId());
		WaypointManager.Waypoint added = WaypointManager.INSTANCE.addFromMessage(message,
				group == null ? "" : group.name());
		this.status(added == null ? "Waypoint already exists here" : "Waypoint added: " + location.pretty());
	}

	private void sendLocation() {
		GroupDto group = this.state.activeGroup();
		if (group == null || this.minecraft == null || this.minecraft.player == null) {
			return;
		}
		SharedLocation location = SharedLocation.of(this.minecraft.player);
		ClientPlayNetworking.send(new Payloads.SendLocation(group.id(), location.dimension(), location.x(),
				location.y(), location.z()));
		this.status("Coordinates shared with #" + group.name());
		this.scroll = 0;
	}

	private int[] thumbSize(String imageId, int maxWidth, int maxHeight) {
		TextureCache.CachedTexture entry = GroupChatClient.INSTANCE.textures.get(imageId);
		if (entry == null) {
			return new int[]{Math.min(maxWidth, 180), 72};
		}
		float scale = Math.min((float) maxWidth / entry.width(), (float) maxHeight / entry.height());
		scale = Math.min(scale, 1.0F);
		return new int[]{Math.max(1, Math.round(entry.width() * scale)), Math.max(1, Math.round(entry.height() * scale))};
	}

	private static int senderColor(String sender) {
		int hash = Math.abs(sender.hashCode());
		int[] palette = {0xFF89DDFF, 0xFFF7C873, 0xFF8CE99A, 0xFFFF9EB5, 0xFFC5A3FF, 0xFF9FE8D8};
		return palette[hash % palette.length];
	}

	// ------------------------------------------------------------------- input

	private void addClick(int x, int y, int width, int height, Runnable action) {
		this.clicks.add(new Click(x, y, width, height, action));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		if (this.zoomImageId != null) {
			this.zoomImageId = null;
			return true;
		}
		for (int i = this.clicks.size() - 1; i >= 0; i--) {
			Click click = this.clicks.get(i);
			if (UiKit.hovered(click.x(), click.y(), click.width(), click.height(), mouseX, mouseY)) {
				click.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX < this.rightX()) {
			this.leftScroll -= scrollY * 20;
			return true;
		}
		if (this.mode == Mode.CHAT) {
			// scroll counts pixels above the newest message, so "wheel up" increases it
			this.scroll += scrollY * 24;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (this.zoomImageId != null) {
				this.zoomImageId = null;
				return true;
			}
			if (this.mode != Mode.CHAT) {
				this.setMode(Mode.CHAT);
				return true;
			}
			this.onClose();
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			switch (this.mode) {
				case CHAT -> {
					this.sendText();
					return true;
				}
				case CREATE -> {
					this.createGroup();
					return true;
				}
				case MEMBERS -> {
					this.invitePlayer(this.state.activeGroup());
					return true;
				}
				default -> {
				}
			}
		}
		if (event.key() == InputConstants.KEY_TAB && this.mode == Mode.CHAT) {
			this.cycleGroup();
			return true;
		}
		return super.keyPressed(event);
	}

	/** Drag & drop: dropping an image file on the window sends it to the open conversation. */
	@Override
	public void onFilesDrop(List<Path> paths) {
		if (this.state.activeGroup() == null) {
			this.status("Open a group before dropping an image");
			return;
		}
		for (Path path : paths) {
			if (ImageUtil.looksLikeImage(path)) {
				this.sendImageFile(path);
				return;
			}
		}
		this.status("Only PNG, JPG, GIF and BMP files can be sent");
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(this.parent);
	}

	// ----------------------------------------------------------------- actions

	private void setMode(Mode newMode) {
		this.mode = newMode;
		this.clicks.clear();
		if (newMode == Mode.CREATE) {
			this.createName.setValue("");
			this.picked.clear();
			this.setFocused(this.createName);
		} else if (newMode == Mode.MEMBERS) {
			this.inviteName.setValue("");
			this.setFocused(this.inviteName);
		} else if (newMode == Mode.CHAT) {
			this.setFocused(this.composer);
		} else {
			this.setFocused(null);
		}
	}

	private void selectGroup(String groupId) {
		this.state.setActiveGroup(groupId);
		this.mode = Mode.CHAT;
		this.scroll = 0;
		GroupChatClient.INSTANCE.ensureHistory(groupId);
		this.setFocused(this.composer);
	}

	private void cycleGroup() {
		List<GroupDto> groups = this.state.groups();
		if (groups.isEmpty()) {
			return;
		}
		int index = 0;
		for (int i = 0; i < groups.size(); i++) {
			if (groups.get(i).id().equals(this.state.activeGroupId())) {
				index = i;
				break;
			}
		}
		this.selectGroup(groups.get((index + 1) % groups.size()).id());
	}

	private void sendText() {
		GroupDto group = this.state.activeGroup();
		if (group == null || this.composer == null) {
			return;
		}
		String text = this.composer.getValue().strip();
		if (text.isEmpty()) {
			return;
		}
		ClientPlayNetworking.send(new Payloads.SendText(group.id(), text));
		this.composer.setValue("");
		this.scroll = 0;
	}

	private void sendSticker(String stickerId) {
		GroupDto group = this.state.activeGroup();
		if (group == null) {
			return;
		}
		ClientPlayNetworking.send(new Payloads.SendSticker(group.id(), stickerId));
		this.minecraft.setScreenAndShow(this);
	}

	private void sendImageFile(Path path) {
		GroupDto group = this.state.activeGroup();
		if (group == null) {
			return;
		}
		this.status("Preparing image...");
		Util.backgroundExecutor().execute(() -> {
			try {
				byte[] png = ImageUtil.readAndNormalise(path);
				String imageId = Long.toString(System.nanoTime(), 36) + Long.toString((long) (Math.random() * 9_999_999L), 36);
				this.minecraft.execute(() -> {
					GroupChatClient.INSTANCE.textures.accept(imageId, png);
					ClientPlayNetworking.send(new Payloads.UploadImage(group.id(), imageId, png));
					this.status("Image sent to #" + group.name());
					this.scroll = 0;
				});
			} catch (Exception exception) {
				this.minecraft.execute(() -> this.status("Image failed: " + exception.getMessage()));
			}
		});
	}

	/** Uploads a picked GIF to the active group. */
	private void sendGif(GifPickerScreen.Selection selection) {
		GroupDto group = this.state.activeGroup();
		if (group == null) {
			this.status("Open a group before sending a GIF");
			return;
		}
		String gifId = Long.toString(System.nanoTime(), 36) + Long.toString((long) (Math.random() * 9_999_999L), 36);
		ClientPlayNetworking.send(new Payloads.UploadGif(group.id(), gifId, selection.data()));
		this.status("GIF sent to #" + group.name());
		this.scroll = 0;
	}

	private void createGroup() {
		String name = this.createName.getValue().strip();
		if (name.isEmpty()) {
			this.status("Give the group a name first");
			return;
		}
		ClientPlayNetworking.send(new Payloads.CreateGroup(name, new ArrayList<>(this.picked)));
		this.createName.setValue("");
		this.picked.clear();
		this.mode = Mode.CHAT;
	}

	private void respond(InviteDto invite, boolean accept) {
		ClientPlayNetworking.send(new Payloads.RespondInvite(invite.groupId(), accept));
		this.state.removeInvite(invite.groupId());
		if (accept) {
			this.state.setActiveGroup(invite.groupId());
			this.mode = Mode.CHAT;
		}
	}

	private void invitePlayer(GroupDto group) {
		if (group == null || this.inviteName == null) {
			return;
		}
		String name = this.inviteName.getValue().strip();
		if (name.isEmpty()) {
			this.status("Type a player name to invite");
			return;
		}
		ClientPlayNetworking.send(new Payloads.InvitePlayer(group.id(), name));
		this.inviteName.setValue("");
		this.status("Invite sent to " + name);
	}

	private void kick(GroupDto group, String target) {
		ClientPlayNetworking.send(new Payloads.KickMember(group.id(), target));
		this.status("Removed " + target);
	}

	private void leaveGroup(GroupDto group) {
		ClientPlayNetworking.send(new Payloads.LeaveGroup(group.id()));
		this.mode = Mode.CHAT;
	}

	private void toggleCall(GroupDto group) {
		boolean inCall = group.isInCall(this.state.selfName());
		ClientPlayNetworking.send(new Payloads.CallJoin(group.id(), !inCall));
		if (inCall) {
			GroupChatClient.INSTANCE.voice.leave();
		} else {
			GroupChatClient.INSTANCE.voice.join(group.id());
		}
	}

	/** Dev-only helper so the screenshot harness can scroll the conversation to the oldest message. */
	public void devScrollToTop() {
		this.scroll = 100_000.0D;
	}

	/** Dev-only helper so the screenshot harness can capture every panel. */
	public void devOpenPanel(String panel) {
		switch (panel) {
			case "create" -> this.setMode(Mode.CREATE);
			case "invites" -> this.setMode(Mode.INVITES);
			case "members" -> this.setMode(Mode.MEMBERS);
			default -> this.setMode(Mode.CHAT);
		}
	}

	private void status(String message) {
		this.statusMessage = message;
		this.statusUntil = System.currentTimeMillis() + 4_000L;
	}
}
