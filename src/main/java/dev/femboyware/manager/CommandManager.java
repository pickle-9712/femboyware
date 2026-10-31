package dev.femboyware.manager;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.GuardCommandModule;
import dev.femboyware.module.utility.NameProtectModule;
import dev.femboyware.util.ChatUtil;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Locale;

/**
 * Chat commands starting with {@value #PREFIX}, handled by the client and never sent to the
 * server: {@code .friend}, {@code .waypoint}, {@code .g}, {@code .np} and {@code .help}.
 */
public class CommandManager {
	public static final String PREFIX = ".";

	/** Runs {@code message} if it's a command; true if it was, so it isn't sent as chat. */
	public boolean handle(String message) {
		if (!message.startsWith(PREFIX) || message.length() == PREFIX.length()) return false;
		String[] args = message.substring(PREFIX.length()).trim().split("\\s+");
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "friend", "friends", "f" -> friend(args);
			case "waypoint", "waypoints", "wp" -> waypoint(args);
			case "guard", "g" -> FemboyWare.getModules().get(GuardCommandModule.class).command(message.substring(PREFIX.length()).trim().substring(args[0].length()).trim());
			case "nameprotect", "np" -> FemboyWare.getModules().get(NameProtectModule.class).command(args);
			case "help" -> help();
			default -> ChatUtil.info("Unknown command. Try " + PREFIX + "help");
		}
		return true;
	}

	private void friend(String[] args) {
		FriendManager friends = FemboyWare.getFriends();
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		switch (action) {
			case "add" -> {
				if (args.length < 3) ChatUtil.info("Usage: " + PREFIX + "friend add <name>");
				else ChatUtil.info(friends.add(args[2]) ? args[2] + " is now your friend <3" : args[2] + " is already your friend");
			}
			case "remove", "del", "delete" -> {
				if (args.length < 3) ChatUtil.info("Usage: " + PREFIX + "friend remove <name>");
				else ChatUtil.info(friends.remove(args[2]) ? args[2] + " is no longer your friend" : args[2] + " wasn't your friend");
			}
			case "clear" -> {
				friends.clear();
				ChatUtil.info("Cleared your friends list");
			}
			case "list" -> ChatUtil.info(friends.names().isEmpty() ? "No friends yet. Add one with " + PREFIX + "friend add <name>"
				: "Friends (" + friends.names().size() + "): " + String.join(", ", friends.names()));
			default -> ChatUtil.info("Usage: " + PREFIX + "friend <add|remove|list|clear> [name]");
		}
	}

	private void waypoint(String[] args) {
		WaypointManager waypoints = FemboyWare.getWaypoints();
		MinecraftClient mc = MinecraftClient.getInstance();
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		switch (action) {
			case "add", "set" -> {
				if (mc.player == null) return;
				String name = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : "Waypoint " + (waypoints.onThisWorld().size() + 1);
				var waypoint = waypoints.add(name, mc.player.getBlockPos(), false);
				ChatUtil.info("Saved waypoint \"" + waypoint.name() + "\" at " + ChatUtil.coords(waypoint.pos()));
			}
			case "remove", "del", "delete" -> {
				if (args.length < 3) ChatUtil.info("Usage: " + PREFIX + "waypoint remove <name>");
				else {
					String name = String.join(" ", List.of(args).subList(2, args.length));
					ChatUtil.info(waypoints.remove(name) ? "Removed waypoint \"" + name + "\"" : "No waypoint called \"" + name + "\"");
				}
			}
			case "clear" -> ChatUtil.info("Removed " + waypoints.clear() + " waypoints on this world");
			case "list" -> {
				var list = waypoints.onThisWorld();
				if (list.isEmpty()) ChatUtil.info("No waypoints here yet. Save one with " + PREFIX + "waypoint add <name>");
				else for (var waypoint : list) ChatUtil.info(waypoint.name() + ": " + ChatUtil.coords(waypoint.pos()) + " (" + waypoint.dimension().replace("minecraft:", "") + ")");
			}
			default -> ChatUtil.info("Usage: " + PREFIX + "waypoint <add|remove|list|clear> [name]");
		}
	}

	private void help() {
		ChatUtil.info(PREFIX + "friend <add|remove|list|clear> [name]  (or middle click a player)");
		ChatUtil.info(PREFIX + "waypoint <add|remove|list|clear> [name]  (" + PREFIX + "wp for short)");
		ChatUtil.info(PREFIX + "g <order>  (privately order your bodyguards, like " + PREFIX + "g pause, " + PREFIX + "g rally or " + PREFIX + "g here; needs Guard Command on)");
		ChatUtil.info(PREFIX + "np <add|remove|list|clear> [name] [fake name]  (hide other players' names)");
	}
}
