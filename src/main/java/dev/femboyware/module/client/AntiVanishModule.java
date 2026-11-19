package dev.femboyware.module.client;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.hud.StaffListModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ButtonSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.DonutStaff;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Tells you when staff go somewhere you can't see them, and when they were watching you. A server
 * never sends where a spectator is, so it reads the signs around it:
 * <ul>
 * <li>someone switching to spectator, and how close they were;</li>
 * <li>someone taken off the tab list while their body stays loaded, or hidden from it outright (vanish);</li>
 * <li>someone leaving the tab list with no leave message, on servers that do send them;</li>
 * <li>someone coming back out of spectator or vanish right by you: they were watching (and if
 * they'd been far off, they teleported to you to do it);</li>
 * <li>staff invisible near you;</li>
 * <li>names the server still offers when tab-completing /msg that aren't in your tab list.</li>
 * </ul>
 * Built for DonutSMP: it sends no leave messages (so a silent leave means nothing on its own), has
 * thousands of players across shards (so it only watches staff there, from their known names and
 * ranks), chat and /msg reach other shards (so names seen in chat aren't "hidden"), and a shard
 * change starts it over. It learns whether a server sends leave messages as it goes. The way it
 * reads these signs follows the Anubis client (GPL-3.0, by 4ldenz).
 */
public class AntiVanishModule extends Module {
	public enum Who { STAFF_ON_DONUT, STAFF, EVERYONE }

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	/** Our own suggestion requests: far from the ids the chat box uses. */
	private static final int PROBE_ID = 0x7A11_0000;
	/** Ticks after joining or changing shard before anything's said, while the tab list fills in. */
	private static final int SETTLE = 60;
	/** How long a body seen in the world still counts as "they were here", in ticks (10 minutes). */
	private static final int SEEN_FOR = 20 * 60 * 10;

	private final EnumSetting<Who> who = add(new EnumSetting<>("Watch", "Whose vanishing to report: staff only on DonutSMP (everyone elsewhere), staff only everywhere, or everyone", Who.STAFF_ON_DONUT));
	private final StringSetting staffNames = add(new StringSetting("Staff Names", "Names that count as staff, comma separated (DonutSMP's are in by default); anyone with a staff rank in the tab list counts too", DonutStaff.DEFAULT_TEXT));
	private final BoolSetting spectator = add(new BoolSetting("Spectator Alerts", "Say when someone switches into or out of spectator mode", true));
	private final BoolSetting vanish = add(new BoolSetting("Vanish Alerts", "Say when someone is hidden from the tab list but still here (vanished)", true));
	private final BoolSetting invisible = add(new BoolSetting("Invisible Alerts", "Say when someone you're watching for is invisible near you", true));
	private final NumberSetting watchDistance = add(new NumberSetting("Watch Distance", "Within this many blocks of you counts as watching you", 64, 16, 160, 4));
	private final BoolSetting probe = add(new BoolSetting("Find Hidden", "Quietly ask the server which names /msg can tab-complete; ones not in your tab list are online but hidden (or on another shard)", true));
	private final NumberSetting probeEvery = add(new NumberSetting("Find Every", "Seconds between those checks", 10, 5, 120, 5)).visibleWhen(probe::get);
	private final StringSetting probeCommand = add(new StringSetting("Find Command", "The command whose names are checked; auto uses minecraft:msg on DonutSMP and msg elsewhere", "auto")).visibleWhen(probe::get);
	private final BoolSetting title = add(new BoolSetting("Title", "Show it big on screen when someone was watching you", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "A ping with each alert", true));
	private final ButtonSetting report = add(new ButtonSetting("Who's Watching", "Lists who's in spectator, vanished or hidden right now", this::report));

	/** Everyone the tab list has told us about: name and game mode, kept after they vanish. */
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<UUID, GameType> modes = new HashMap<>();
	/** Where each player was last seen as a body in the world, and when (our tick). */
	private record Seen(Vec3 pos, int tick) {
	}

	private final Map<UUID, Seen> lastSeen = new HashMap<>();
	/** Dropped off the tab list, waiting a second to see what it was. */
	private final Map<UUID, Integer> leaving = new HashMap<>();
	/** Vanished, or left without a word on a server that has no leave messages (maybe vanished). */
	private final Set<UUID> vanished = new HashSet<>(), maybeVanished = new HashSet<>();
	/** Just came back (out of spectator or vanish): watched for their body turning up near you. */
	private record Return(String how, Seen before, int since) {
	}

	private final Map<UUID, Return> returning = new HashMap<>();
	/** Recent server messages: to tell leaving from vanishing, and who's chatting from other shards. */
	private record Line(String text, int tick) {
	}

	private final List<Line> recentChat = new ArrayList<>();
	private final Set<String> hidden = new HashSet<>();
	private final Map<UUID, Integer> invisibleSaid = new HashMap<>();
	/** Whether this server sends "X left the game", learned from the messages it sends. */
	private boolean leaveMessages;
	private int ticks, nextProbe = SETTLE + 40, settledAt;
	private Object connection, level;

	public AntiVanishModule() {
		super("Anti Vanish", "Tells you when staff go into spectator or vanish, and when they were watching you (built for DonutSMP)", Category.MISC);
	}

	@Override
	protected void onEnable() {
		reset(true);
	}

	/** Starts over: on a new server ({@code server}), or a new shard or world, keeping what it's learned about the server. */
	private void reset(boolean server) {
		names.clear();
		modes.clear();
		lastSeen.clear();
		leaving.clear();
		vanished.clear();
		maybeVanished.clear();
		returning.clear();
		hidden.clear();
		invisibleSaid.clear();
		if (server) {
			recentChat.clear();
			leaveMessages = false;
		}
		settledAt = ticks + SETTLE;
		nextProbe = ticks + SETTLE + 40;
		// Whoever's already here: their names and modes, without an alert for each
		if (mc.getConnection() != null) {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				names.put(info.getProfile().id(), info.getProfile().name());
				modes.put(info.getProfile().id(), info.getGameMode());
			}
		}
	}

	@Override
	public String getInfo() {
		int spec = 0;
		for (Map.Entry<UUID, GameType> e : modes.entrySet()) if (e.getValue() == GameType.SPECTATOR && !isMe(e.getKey()) && watched(e.getKey())) spec++;
		int unseen = vanished.size() + hidden.size();
		if (spec == 0 && unseen == 0) return DonutStaff.onDonut() ? "Donut" : null;
		return (spec > 0 ? spec + " spec" : "") + (spec > 0 && unseen > 0 ? ", " : "") + (unseen > 0 ? unseen + " hidden" : "");
	}

	// ---------------------------------------------------------------- who counts

	private boolean staffOnly() {
		return switch (who.get()) {
			case STAFF -> true;
			case EVERYONE -> false;
			case STAFF_ON_DONUT -> DonutStaff.onDonut();
		};
	}

	private boolean isStaff(String name, UUID id) {
		if (DonutStaff.listed(name, staffNames.get())) return true;
		if (DonutStaff.onDonut() && DonutStaff.DEFAULT.stream().anyMatch(n -> n.equalsIgnoreCase(name))) return true;
		PlayerInfo info = id == null || mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
		return StaffListModule.staffRank(info);
	}

	/** Whether alerts about this player are wanted. */
	private boolean watched(UUID id) {
		return !staffOnly() || isStaff(names.get(id), id);
	}

	private boolean watchedName(String name) {
		if (!staffOnly()) return true;
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(name);
		return isStaff(name, info == null ? null : info.getProfile().id());
	}

	private boolean settled() {
		return ticks >= settledAt;
	}

	// ---------------------------------------------------------------- packets (netty thread: copied, then handled on the main thread)

	@Override
	public void onPacketReceive(PacketEvent event) {
		switch (event.getPacket()) {
			case ClientboundPlayerInfoUpdatePacket p -> {
				boolean added = p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
				boolean mode = p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
				boolean listing = p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
				if (!added && !mode && !listing) return;
				// Big batches are the tab list filling in (joining, a new shard), not one person vanishing
				boolean batch = p.entries().size() > 3;
				List<Object[]> entries = new ArrayList<>();
				for (ClientboundPlayerInfoUpdatePacket.Entry e : p.entries()) {
					entries.add(new Object[]{e.profileId(), e.profile() == null ? null : e.profile().name(), mode ? e.gameMode() : null, listing && !added && !batch && !e.listed()});
				}
				mc.execute(() -> {
					for (Object[] e : entries) updated((UUID) e[0], (String) e[1], added, (GameType) e[2], (Boolean) e[3]);
				});
			}
			case ClientboundPlayerInfoRemovePacket p -> {
				if (p.profileIds().size() > 3) return;
				List<UUID> ids = List.copyOf(p.profileIds());
				mc.execute(() -> {
					for (UUID id : ids) if (!isMe(id) && names.containsKey(id)) leaving.put(id, ticks);
				});
			}
			case ClientboundSystemChatPacket p -> {
				if (p.overlay()) return;
				Component content = p.content();
				boolean vanillaLeave = content.getContents() instanceof TranslatableContents t && t.getKey().equals("multiplayer.player.left");
				String text = content.getString();
				mc.execute(() -> {
					if (vanillaLeave) leaveMessages = true;
					recentChat.add(new Line(text.toLowerCase(Locale.ROOT), ticks));
				});
			}
			case ClientboundCommandSuggestionsPacket p -> {
				if (p.id() != PROBE_ID) return;
				// Ours: the chat box never sees it
				event.cancel();
				List<String> offered = new ArrayList<>();
				for (ClientboundCommandSuggestionsPacket.Entry e : p.suggestions()) offered.add(e.text());
				mc.execute(() -> probed(offered));
			}
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- what happened

	private void updated(UUID id, String name, boolean added, GameType mode, boolean unlisted) {
		if (isMe(id)) return;
		if (name != null) names.put(id, name);
		String who = names.getOrDefault(id, "Someone");
		if (added) {
			leaving.remove(id);
			boolean wasGone = vanished.remove(id) | maybeVanished.remove(id);
			if (wasGone && settled() && watched(id)) {
				if (vanish.get()) chat(who + " is back in the tab list");
				returning.put(id, new Return("came back", seenBefore(id), ticks));
			}
		}
		// Taken off the tab list without leaving: that's vanish
		if (unlisted && settled() && watched(id) && vanished.add(id) && vanish.get()) {
			alert(who + " was hidden from the tab list (vanished)" + near(id));
		}
		if (mode == null) return;
		GameType before = modes.put(id, mode);
		if (before == null || before == mode || !settled() || !watched(id)) return;
		if (mode == GameType.SPECTATOR) {
			if (spectator.get()) alert(who + " went into spectator" + near(id));
		} else if (before == GameType.SPECTATOR) {
			if (spectator.get()) chat(who + " left spectator (" + mode.getName() + ")");
			returning.put(id, new Return("came out of spectator", seenBefore(id), ticks));
		}
	}

	/** ", 12 blocks from you" if they were seen near you just now, or nothing. */
	private String near(UUID id) {
		Seen seen = seenBefore(id);
		if (seen == null || mc.player == null || ticks - seen.tick > 60) return "";
		return ", " + Math.round(seen.pos.distanceTo(mc.player.position())) + " blocks from you";
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
		// A new server, or a new shard or world on it: start over
		if (mc.getConnection() != connection) {
			connection = mc.getConnection();
			level = mc.level;
			reset(true);
		} else if (mc.level != level) {
			level = mc.level;
			reset(false);
		}
		ticks++;
		// Where everyone around you is, so a vanish or a switch can say how close they were
		for (Player p : mc.level.players()) {
			if (p != mc.player) lastSeen.put(p.getUUID(), new Seen(p.position(), ticks));
		}
		recentChat.removeIf(line -> ticks - line.tick > 160);
		checkLeaving();
		checkReturning();
		checkInvisible();
		if (probe.get() && ticks >= nextProbe) sendProbe();
	}

	/** A second after someone drops off the tab list: did they leave, or vanish? */
	private void checkLeaving() {
		for (Iterator<Map.Entry<UUID, Integer>> it = leaving.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Integer> e = it.next();
			if (ticks - e.getValue() < 20) continue;
			it.remove();
			UUID id = e.getKey();
			String who = names.getOrDefault(id, "Someone");
			if (saidLeave(who)) {
				leaveMessages = true;
				forget(id);
				continue;
			}
			if (!settled() || !watched(id)) {
				forget(id);
				continue;
			}
			// Off the tab list but their body's still here: certainly vanished, and close
			Player body = mc.level.getPlayerByUUID(id);
			if (body != null) {
				vanished.add(id);
				double far = body.position().distanceTo(mc.player.position());
				if (vanish.get()) alert(who + " vanished but is still here, " + Math.round(far) + " blocks from you");
				if (far <= watchDistance.get()) watching(who, far, false);
				continue;
			}
			if (leaveMessages) {
				// This server says when people leave, and it didn't: vanished
				vanished.add(id);
				if (vanish.get()) alert(who + " left the tab list without leaving (vanished)" + near(id));
			} else {
				// No leave messages here (DonutSMP): they may just have logged off, so only noted
				maybeVanished.add(id);
				Seen seen = lastSeen.get(id);
				if (vanish.get() && seen != null && ticks - seen.tick <= SEEN_FOR) chat(who + " left the tab list (logged off, or vanished; they were near you recently)");
			}
		}
	}

	/** Back from spectator or vanish: if their body turns up near you, they were watching you. */
	private void checkReturning() {
		for (Iterator<Map.Entry<UUID, Return>> it = returning.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Return> e = it.next();
			Return r = e.getValue();
			if (ticks - r.since > 100) {
				it.remove();
				continue;
			}
			Player body = mc.level.getPlayerByUUID(e.getKey());
			if (body == null) continue;
			it.remove();
			double far = body.position().distanceTo(mc.player.position());
			if (far > watchDistance.get()) continue;
			String who = names.getOrDefault(e.getKey(), body.getName().getString());
			boolean travelled = r.before == null || r.before.pos.distanceTo(body.position()) > watchDistance.get();
			chat(who + " " + r.how + " " + Math.round(far) + " blocks from you");
			watching(who, far, travelled);
		}
	}

	/** Staff (or whoever you're watching for) invisible near you. */
	private void checkInvisible() {
		if (!invisible.get() || !settled() || ticks % 5 != 0) return;
		for (Player p : mc.level.players()) {
			if (p == mc.player || !p.isInvisible()) continue;
			double far = p.position().distanceTo(mc.player.position());
			if (far > watchDistance.get() || !watched(p.getUUID())) continue;
			Integer said = invisibleSaid.get(p.getUUID());
			if (said != null && ticks - said < 20 * 60) continue;
			invisibleSaid.put(p.getUUID(), ticks);
			alert(p.getName().getString() + " is invisible " + Math.round(far) + " blocks from you");
		}
	}

	private void forget(UUID id) {
		modes.remove(id);
		lastSeen.remove(id);
	}

	/** Where someone was last seen in the world, if recently enough to mean anything. */
	private Seen seenBefore(UUID id) {
		Seen seen = lastSeen.get(id);
		return seen != null && ticks - seen.tick <= SEEN_FOR ? seen : null;
	}

	/** Whether the server just said something like "<name> left". */
	private boolean saidLeave(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		for (Line line : recentChat) {
			if (ticks - line.tick > 100 || !line.text.contains(n)) continue;
			if (line.text.contains("left") || line.text.contains("quit") || line.text.contains("disconnect") || line.text.contains("logged out")) return true;
		}
		return false;
	}

	/** Whether {@code name} turned up in chat lately (on Donut, someone chatting from another shard). */
	private boolean chattedRecently(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		for (Line line : recentChat) if (line.text.contains(n)) return true;
		return false;
	}

	// ---------------------------------------------------------------- finding hidden players

	private void sendProbe() {
		nextProbe = ticks + (int) (probeEvery.get() * 20);
		String command = probeCommand.get().trim().replaceFirst("^/", "");
		if (command.equalsIgnoreCase("auto") || command.isEmpty()) command = DonutStaff.onDonut() ? "minecraft:msg" : "msg";
		if (command.contains(" ")) return;
		mc.getConnection().send(new ServerboundCommandSuggestionPacket(PROBE_ID, "/" + command + " "));
	}

	private void probed(List<String> offered) {
		if (mc.getConnection() == null || mc.player == null || !settled()) return;
		Set<String> listed = new HashSet<>();
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) listed.add(info.getProfile().name().toLowerCase(Locale.ROOT));
		String me = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
		Set<String> now = new HashSet<>();
		for (String name : offered) {
			if (!NAME.matcher(name).matches()) continue;
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.equals(me) || listed.contains(lower) || chattedRecently(name) || !watchedName(name)) continue;
			now.add(name);
		}
		// More than a few means the command offers players from everywhere (or words): not a vanish
		if (now.size() > 3) return;
		for (String name : now) {
			if (hidden.add(name)) alert(name + " is online but not in your tab list (vanished" + (DonutStaff.onDonut() ? ", or on another shard)" : ")"));
		}
		hidden.removeIf(name -> {
			if (now.contains(name)) return false;
			chat(name + " isn't hidden any more");
			return true;
		});
	}

	// ---------------------------------------------------------------- telling you

	/** The big one: someone was watching you. */
	private void watching(String who, double far, boolean teleported) {
		alert(who + " was watching you (" + Math.round(far) + " blocks away" + (teleported ? ", teleported to you" : "") + ")");
		if (title.get()) {
			//? if >=26.1 {
			/*var titles = mc.gui.hud;
			*///?} else {
			var titles = mc.gui;
			//?}
			titles.setTimes(5, 70, 15);
			titles.setSubtitle(Component.literal(who + ", " + Math.round(far) + " blocks away"));
			titles.setTitle(Component.literal("§d" + "Staff watching you"));
		}
	}

	private void report() {
		List<String> spec = new ArrayList<>();
		for (Map.Entry<UUID, GameType> e : modes.entrySet()) {
			if (e.getValue() == GameType.SPECTATOR && !isMe(e.getKey()) && !vanished.contains(e.getKey())) spec.add(names.getOrDefault(e.getKey(), "?"));
		}
		List<String> gone = new ArrayList<>(), maybe = new ArrayList<>();
		for (UUID id : vanished) gone.add(names.getOrDefault(id, "?"));
		for (UUID id : maybeVanished) maybe.add(names.getOrDefault(id, "?"));
		chat("In spectator: " + (spec.isEmpty() ? "nobody" : String.join(", ", spec)));
		chat("Vanished: " + (gone.isEmpty() ? "nobody" : String.join(", ", gone)) + (maybe.isEmpty() ? "" : "  (maybe: " + String.join(", ", maybe) + ")"));
		chat("Online but hidden: " + (hidden.isEmpty() ? (probe.get() ? "nobody found" : "Find Hidden is off") : String.join(", ", hidden)));
		chat((DonutStaff.onDonut() ? "On DonutSMP, watching staff only" : staffOnly() ? "Watching staff only" : "Watching everyone")
			+ "; this server " + (leaveMessages ? "sends" : "hasn't sent") + " leave messages");
	}

	private boolean isMe(UUID id) {
		return mc.player != null && mc.player.getUUID().equals(id);
	}

	private void alert(String text) {
		chat(text);
		if (sound.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.7f));
	}

	private void chat(String text) {
		ChatUtil.info("[Anti Vanish] " + text);
	}
}
