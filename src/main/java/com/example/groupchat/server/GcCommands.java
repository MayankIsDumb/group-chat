package com.example.groupchat.server;

import com.example.groupchat.net.Payloads;
import com.example.groupchat.net.Payloads.MessageDto;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /gchat ...} — everything the GUI can do is also scriptable from the command line.
 */
public final class GcCommands {
	private GcCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
			dispatcher.register(Commands.literal("gchat")
					.then(Commands.literal("list").executes(GcCommands::list))
					.then(Commands.literal("create")
							.then(Commands.argument("name", StringArgumentType.greedyString())
									.executes(GcCommands::create)))
					.then(Commands.literal("invite")
							.then(Commands.argument("player", EntityArgument.player())
									.then(Commands.argument("group", StringArgumentType.word())
											.executes(GcCommands::invite))))
					.then(Commands.literal("accept")
							.then(Commands.argument("group", StringArgumentType.word())
									.executes(context -> respond(context, true))))
					.then(Commands.literal("decline")
							.then(Commands.argument("group", StringArgumentType.word())
									.executes(context -> respond(context, false))))
					.then(Commands.literal("leave")
							.then(Commands.argument("group", StringArgumentType.word())
									.executes(GcCommands::leave)))
					.then(Commands.literal("msg")
							.then(Commands.argument("group", StringArgumentType.word())
									.then(Commands.argument("text", StringArgumentType.greedyString())
											.executes(GcCommands::message))))
					.then(Commands.literal("call")
							.then(Commands.argument("group", StringArgumentType.word())
									.executes(context -> call(context, true))))
					.then(Commands.literal("hangup")
							.then(Commands.argument("group", StringArgumentType.word())
									.executes(context -> call(context, false)))));
		});
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		GroupManager manager = GroupManager.get();
		if (player == null || manager == null) {
			return 0;
		}
		String self = ServerNet.self(player);
		var groups = manager.groupsOf(self);
		if (groups.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("You are not in any groups. Try /gchat create <name>"), false);
			return 1;
		}
		context.getSource().sendSuccess(() -> Component.literal("Your groups:"), false);
		for (GroupData group : groups) {
			String line = " - " + group.name + " [" + group.id + "] " + group.members.size() + " members"
					+ (group.callActive ? " (call active)" : "");
			context.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
		}
		var invites = manager.invitesFor(self);
		if (!invites.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("Pending invites:").withStyle(ChatFormatting.GOLD), false);
			for (Payloads.InviteDto invite : invites) {
				String line = " - " + invite.groupName() + " [" + invite.groupId() + "] from " + invite.inviter();
				context.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
			}
		}
		return 1;
	}

	private static int create(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		GroupManager manager = GroupManager.get();
		if (player == null || manager == null) {
			return 0;
		}
		String error = manager.create(ServerNet.self(player), StringArgumentType.getString(context, "name"), java.util.List.of());
		if (error != null) {
			return fail(context, error);
		}
		manager.sendSnapshot(player);
		return ok(context, "Group created.");
	}

	private static int invite(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		GroupManager manager = GroupManager.get();
		if (player == null || manager == null) {
			return 0;
		}
		String self = ServerNet.self(player);
		GroupData group = resolve(manager, self, StringArgumentType.getString(context, "group"));
		if (group == null) {
			return fail(context, "No such group. Use /gchat list.");
		}
		String target;
		try {
			target = ServerNet.self(EntityArgument.getPlayer(context, "player"));
		} catch (Exception exception) {
			return fail(context, "That player is not online.");
		}
		String error = manager.invite(group.id, self, target);
		if (error != null) {
			return fail(context, error);
		}
		return ok(context, "Invited " + target + " to " + group.name + ".");
	}

	private static int respond(CommandContext<CommandSourceStack> context, boolean accept) {
		ServerPlayer player = context.getSource().getPlayer();
		GroupManager manager = GroupManager.get();
		if (player == null || manager == null) {
			return 0;
		}
		String self = ServerNet.self(player);
		String argument = StringArgumentType.getString(context, "group");
		String groupId = argument;
		for (Payloads.InviteDto invite : manager.invitesFor(self)) {
			if (invite.groupId().equals(argument) || invite.groupName().equalsIgnoreCase(argument)) {
				groupId = invite.groupId();
				break;
			}
		}
		String error = manager.respond(self, groupId, accept);
		if (error != null) {
			return fail(context, error);
		}
		manager.sendSnapshot(player);
		return ok(context, accept ? "Invite accepted." : "Invite declined.");
	}

	private static int leave(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		GroupManager manager = GroupManager.get();
		if (player == null || manager == null) {
			return 0;
		}
		String self = ServerNet.self(player);
		GroupData group = resolve(manager, self, StringArgumentType.getString(context, "group"));
		if (group == null) {
			return fail(context, "No such group.");
		}
		String error = manager.leave(self, group.id);
		if (error != null) {
			return fail(context, error);
		}
		manager.sendSnapshot(player);
		return ok(context, "Left " + group.name + ".");
	}

	private static int message(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		GroupManager manager = GroupManager.get();
		if (player == null || manager == null) {
			return 0;
		}
		String self = ServerNet.self(player);
		GroupData group = resolve(manager, self, StringArgumentType.getString(context, "group"));
		if (group == null) {
			return fail(context, "No such group.");
		}
		String text = StringArgumentType.getString(context, "text");
		if (text.length() > 900) {
			text = text.substring(0, 900);
		}
		manager.post(group, self, MessageDto.KIND_TEXT, text, "");
		return 1;
	}

	private static int call(CommandContext<CommandSourceStack> context, boolean join) {
		ServerPlayer player = context.getSource().getPlayer();
		GroupManager manager = GroupManager.get();
		if (player == null || manager == null) {
			return 0;
		}
		String self = ServerNet.self(player);
		GroupData group = resolve(manager, self, StringArgumentType.getString(context, "group"));
		if (group == null) {
			return fail(context, "No such group.");
		}
		manager.setCall(self, group.id, join);
		return ok(context, join ? "Joined the call." : "Left the call.");
	}

	private static GroupData resolve(GroupManager manager, String playerName, String argument) {
		GroupData byId = manager.find(playerName, argument);
		if (byId != null) {
			return byId;
		}
		for (GroupData group : manager.groupsOf(playerName)) {
			if (group.name.equalsIgnoreCase(argument)) {
				return group;
			}
		}
		return null;
	}

	private static int ok(CommandContext<CommandSourceStack> context, String message) {
		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> context, String message) {
		context.getSource().sendFailure(Component.literal(message));
		return 0;
	}

}
