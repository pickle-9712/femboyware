package dev.femboyware.manager;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.combat.GuardCommandModule;
import dev.femboyware.module.utility.NameProtectModule;
import dev.femboyware.util.ChatUtil;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * Chat commands starting with {@value #PREFIX}, handled by the client and never sent to the
 * server: {@code .friend}, {@code .waypoint}, {@code .g}, {@code .np}, {@code .fp} and {@code .help}.
 */
public class CommandManager {
	public static final String PREFIX = ".";

	/** Runs {@code message} if it's a command; true if it was, so it isn't sent as chat. */
	public boolean handle(String message) {
		// What a command says back stays in chat; everything else is a notification
		dev.femboyware.util.ChatUtil.replying++;
		try {
			return run(message);
		} finally {
			dev.femboyware.util.ChatUtil.replying--;
		}
	}

	private boolean run(String message) {
		if (!message.startsWith(PREFIX) || message.length() == PREFIX.length()) return false;
		String[] args = message.substring(PREFIX.length()).trim().split("\\s+");
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "friend", "friends", "f" -> friend(args);
			case "waypoint", "waypoints", "wp" -> waypoint(args);
			case "guard", "g" -> FemboyWare.getModules().get(GuardCommandModule.class).command(message.substring(PREFIX.length()).trim().substring(args[0].length()).trim());
			case "nameprotect", "np" -> FemboyWare.getModules().get(NameProtectModule.class).command(args);
			case "fakeplayer", "fp" -> FemboyWare.getModules().get(FakePlayerModule.class).command(args);
			case "scoreboard", "sb" -> FemboyWare.getModules().get(dev.femboyware.module.render.ScoreboardEditModule.class).command(args);
			case "hat", "hats" -> FemboyWare.getModules().get(dev.femboyware.module.render.RobloxHatsModule.class).command(args);
			case "heli", "helicopter", "chopper" -> FemboyWare.getModules().get(dev.femboyware.module.larp.HelicopterModule.class).command(args);
			case "hangar", "hanger" -> FemboyWare.getModules().get(dev.femboyware.module.larp.HangarModule.class).command(args);
			case "mansion", "manson", "house", "home" -> FemboyWare.getModules().get(dev.femboyware.module.larp.MansionModule.class).command(args);
			case "yacht", "yaught", "yatch", "boat" -> FemboyWare.getModules().get(dev.femboyware.module.larp.YachtModule.class).command(args);
			case "tv", "television", "movie", "video" -> FemboyWare.getModules().get(dev.femboyware.module.larp.TvModule.class).command(args);
			// .mirror and .tiktok: TikTok on the TV, no browser needed
			case "mirror", "tiktok" -> {
				String[] tv = new String[args.length + 1];
				tv[0] = "tv";
				tv[1] = args[0].toLowerCase(java.util.Locale.ROOT);
				System.arraycopy(args, 1, tv, 2, args.length - 1);
				FemboyWare.getModules().get(dev.femboyware.module.larp.TvModule.class).command(tv);
			}
			case "fbi", "raid", "feds" -> FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).command(args);
			case "jet", "plane" -> FemboyWare.getModules().get(dev.femboyware.module.larp.JetModule.class).command(args);
			case "car", "cars" -> FemboyWare.getModules().get(dev.femboyware.module.larp.CarsModule.class).command(args);
			case "area", "areaminer" -> FemboyWare.getModules().get(dev.femboyware.module.utility.AutoAreaMinerModule.class).command(args);
			case "pilot", "autopilot", "fly" -> FemboyWare.getModules().get(dev.femboyware.module.movement.AutoElytraPilotModule.class).command(args);
			case "phone" -> FemboyWare.getModules().get(dev.femboyware.module.larp.PhoneModule.class).command(args);
			case "ledger", "wallet" -> FemboyWare.getModules().get(dev.femboyware.module.larp.LedgerModule.class).command(args);
			case "cosmetics", "cos" -> FemboyWare.getModules().get(dev.femboyware.module.render.CosmeticsModule.class).command(args);
			case "fakespawner", "fakespawners", "spawner" -> FemboyWare.getModules().get(dev.femboyware.module.larp.FakeSpawnerModule.class).command(args);
			case "swordmodel", "knife" -> FemboyWare.getModules().get(dev.femboyware.module.render.SwordModelsModule.class).command(args);
			case "skin", "skinchanger" -> FemboyWare.getModules().get(dev.femboyware.module.render.SkinChangerModule.class).command(args);
			case "casino" -> FemboyWare.getModules().get(dev.femboyware.module.larp.CasinoModule.class).command(args);
			case "fakepay" -> FemboyWare.getModules().get(dev.femboyware.module.larp.FakePayModule.class).command(args);
			case "larpas", "be" -> FemboyWare.getModules().get(dev.femboyware.module.larp.LarpAsModule.class).command(args);
			case "fakebal", "fakebalance", "fakemoney" -> dev.femboyware.module.utility.FakeEconomy.command(args);
			case "larp" -> FemboyWare.getModules().get(dev.femboyware.module.utility.ServerLarpModule.class).command(args);
			case "forcedispenser", "fd" -> FemboyWare.getModules().get(dev.femboyware.module.larp.ForceDispenserModule.class).command(args);
			case "emote", "emotes" -> FemboyWare.getModules().get(dev.femboyware.module.render.EmotesModule.class).command(args);
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
		Minecraft mc = Minecraft.getInstance();
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		switch (action) {
			case "add", "set" -> {
				if (mc.player == null) return;
				String name = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : "Waypoint " + (waypoints.onThisWorld().size() + 1);
				var waypoint = waypoints.add(name, mc.player.blockPosition(), false);
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
		ChatUtil.info(PREFIX + "g arena [first to] [radius]  (your guards fight in two teams where you're standing; " + PREFIX + "g bet <pink|blue> <coins>, " + PREFIX + "g arena stop)");
		ChatUtil.info(PREFIX + "np <add|remove|list|clear> [name] [fake name]  (hide other players' names)");
		ChatUtil.info(PREFIX + "fp <add|remove|list|clear> [names]  (spawn fake players wearing those accounts' skins)");
		ChatUtil.info(PREFIX + "fp random [how many|all]  (fake players with real accounts' names and skins, from Name Protect's Fake Names and then checked guesses)");
		ChatUtil.info(PREFIX + "fp crowd [on|off]  (fake players wander, jump, crouch and talk near where they spawned)");
		ChatUtil.info(PREFIX + "fp formation <name|off|here|list>  (fake players stand in a shape, built in or one you made)");
		ChatUtil.info(PREFIX + "fp maker  (draw and save your own formations)");
		ChatUtil.info(PREFIX + "sb <lines|value <label> <value>|replace <find> <with>|line <label> <text>|title <text>|list|remove|clear>  (Scoreboard Edit: change the sidebar on your screen)");
		ChatUtil.info(PREFIX + "fakespawner <add <mob> [stacks]|stack <n>|remove|list|clear>  (DonutSMP spawners on the block you look at, only you see them)");
		ChatUtil.info(PREFIX + "fakepay <player|random> <amount> [times]  (someone paying you, on your screen and fake balance)");
		ChatUtil.info(PREFIX + "swordmodel <karambit|butterfly|bayonet|katana|ledger|watch> [finish]  (what your sword looks like)");
		ChatUtil.info(PREFIX + "skin <name> [wide|slim] | off  (wear anyone's skin, on your screen)");
		ChatUtil.info(PREFIX + "casino <game> [pick] [bet] | win | lose | fair  (dispenser casino games while larping, on your screen only)");
		ChatUtil.info(PREFIX + "fakebal <amount|add <amount>|take <amount>|off>  (or /fakebal: what /bal and the sidebar's Money say; /sell and /shop use it while larping)");
		ChatUtil.info(PREFIX + "larpas <name> | off  (be someone else on your screen: their skin, cape and name)");
		ChatUtil.info(PREFIX + "larp <on|off|give <item> [count]|creative|survival|clear>  (Server Larp: play client-side while the server sees you standing still)");
		ChatUtil.info(PREFIX + "hat <add|remove|list|clear> [id or catalog link]  (wear Roblox hats)");
		ChatUtil.info(PREFIX + "heli <spawn|fly|land|remove>  (park a helicopter on a pad; board it while larping for a flight)");
		ChatUtil.info(PREFIX + "hangar <spawn|remove>  (a hangar with your jet and cars parked inside)");
		ChatUtil.info(PREFIX + "tv <place|play <file>|tiktok [link]|pause|resume|seek <time>|stop|remove|list|folder>  (a TV playing your MP4s in sync for everyone with Client Sync)");
		ChatUtil.info(PREFIX + "mirror  (TikTok on your TV, no browser needed; scroll while looking at it to swipe)");
		ChatUtil.info(PREFIX + "fbi <raid|stop|release>  (the FBI raids your mansion; release unfreezes your accounts)");
		ChatUtil.info(PREFIX + "jet <spawn|fly|land|remove>  (park a private jet; board it while larping and walk round inside as it flies a lap)");
		ChatUtil.info(PREFIX + "car <spawn [suv|jesko]|remove|clear>  (park flex cars next to you; only you see them)");
		ChatUtil.info(PREFIX + "phone <open|give|dm [name] [message]|bank [amount]|robux [amount]>  (the fake phone: DMs, bank, stocks, Roblox; only you see it)");
		ChatUtil.info(PREFIX + "ledger <open|give|deposit [coin] [amount]|set <coin> <amount>|price <coin> <dollars>|history [clear]|reset|total>  (the fake crypto wallet; only you see it)");
		ChatUtil.info(PREFIX + "cos <give|take|list|clear> [name] [fiery|frozen|poisoned|random|none|same]  (give a player your Korblox, headless and horns; only you see them)");
		ChatUtil.info(PREFIX + "area <pos1|pos2|clear>  (mark a box for Auto Area Miner to dig out; marks the block you look at)");
		ChatUtil.info(PREFIX + "pilot <x> <z> | stop  (Auto Elytra Pilot flies you there and lands; ~ for where you are)");
		ChatUtil.info(PREFIX + "fp text <words>  (fake players spell it out, adding as many as it takes; / starts a new line)");
	}
}
