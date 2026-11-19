package dev.femboyware.module.client;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ButtonSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
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
 * Tells you when staff go somewhere you can't see them, and when they were watching you. The
 * server never sends where a spectator is, so it reads the signs around it: someone switching to
 * spectator (and how close they were), someone coming back out of spectator or vanish right next
 * to you (they were watching; if they'd been far off, they teleported to you), someone dropping off
 * the tab list without leaving (vanish), and, every so often, names the server still offers when
 * tab-completing a command that aren't in the tab list (online, but hidden).
 */
public class AntiVanishModule extends Module {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	/** Our own suggestion requests: far from the ids the chat box uses. */
	private static final int PROBE_ID = 0x7A11_0000;

	private final BoolSetting spectator = add(new BoolSetting("Spectator Alerts", "Say when someone switches into or out of spectator mode", true));
	private final BoolSetting vanish = add(new BoolSetting("Vanish Alerts", "Say when someone drops off the tab list without leaving (vanished) and when they're back", true));
	private final NumberSetting watchDistance = add(new NumberSetting("Watch Distance", "Coming back this close to you (in blocks) counts as having watched you", 48, 8, 160, 4));
	private final BoolSetting probe = add(new BoolSetting("Find Hidden", "Every so often, quietly ask the server which names a command can tab-complete; ones not in the tab list are online but hidden", true));
	private final NumberSetting probeEvery = add(new NumberSetting("Find Every", "Seconds between those checks", 30, 10, 180, 5)).visibleWhen(probe::get);
	private final StringSetting probeCommand = add(new StringSetting("Find Command", "The command whose player names are checked (msg works on most servers; try tell or w)", "msg")).visibleWhen(probe::get);
	private final BoolSetting sound = add(new BoolSetting("Sound", "A ping with each alert", true));
	private final ButtonSetting report = add(new ButtonSetting("Who's Watching", "Lists who's in spectator, vanished or hidden right now", this::report));

	/** Everyone the tab list has told us about: name and game mode, kept after they vanish. */
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<UUID, GameType> modes = new HashMap<>();
	/** Where each player was last seen (as an entity near you), and when (game tick). */
	private record Seen(Vec3 pos, int tick) {
	}

	private final Map<UUID, Seen> lastSeen = new HashMap<>();
	/** Dropped off the tab list, waiting to see whether a leave message explains it. */
	private final Map<UUID, Integer> leaving = new HashMap<>();
	private final Set<UUID> vanished = new HashSet<>();
	/** Just came back (out of spectator or vanish): watched for their body turning up near you. */
	private record Return(String how, Seen before, int since) {
	}

	private final Map<UUID, Return> returning = new HashMap<>();
	/** Recent server messages, to tell leaving from vanishing. */
	private record Line(String text, int tick) {
	}

	private final List<Line> recentChat = new ArrayList<>();
	private final Set<String> hidden = new HashSet<>();
	private int ticks, nextProbe = 100;
	private Object connection;

	public AntiVanishModule() {
		super("Anti Vanish", "Tells you when staff go into spectator or vanish, and when they were watching you", Category.MISC);
	}

	@Override
	protected void onEnable() {
		reset();
	}

	private void reset() {
		names.clear();
		modes.clear();
		lastSeen.clear();
		leaving.clear();
		vanished.clear();
		returning.clear();
		recentChat.clear();
		hidden.clear();
		ticks = 0;
		nextProbe = 100;
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
		for (Map.Entry<UUID, GameType> e : modes.entrySet()) if (e.getValue() == GameType.SPECTATOR && !isMe(e.getKey())) spec++;
		int unseen = vanished.size() + hidden.size();
		if (spec == 0 && unseen == 0) return null;
		return (spec > 0 ? spec + " spec" : "") + (spec > 0 && unseen > 0 ? ", " : "") + (unseen > 0 ? unseen + " hidden" : "");
	}

	// ---------------------------------------------------------------- packets (netty thread: copied, then handled on the main thread)

	@Override
	public void onPacketReceive(PacketEvent event) {
		switch (event.getPacket()) {
			case ClientboundPlayerInfoUpdatePacket p -> {
				boolean added = p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
				boolean mode = p.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
				if (!added && !mode) return;
				List<Object[]> entries = new ArrayList<>();
				for (ClientboundPlayerInfoUpdatePacket.Entry e : p.entries()) {
					entries.add(new Object[]{e.profileId(), e.profile() == null ? null : e.profile().name(), mode ? e.gameMode() : null});
				}
				mc.execute(() -> {
					for (Object[] e : entries) updated((UUID) e[0], (String) e[1], added, (GameType) e[2]);
				});
			}
			case ClientboundPlayerInfoRemovePacket p -> {
				List<UUID> ids = List.copyOf(p.profileIds());
				mc.execute(() -> {
					for (UUID id : ids) removed(id);
				});
			}
			case ClientboundSystemChatPacket p -> {
				if (p.overlay()) return;
				String text = p.content().getString();
				mc.execute(() -> recentChat.add(new Line(text.toLowerCase(Locale.ROOT), ticks)));
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

	private void updated(UUID id, String name, boolean added, GameType mode) {
		if (isMe(id)) return;
		if (name != null) names.put(id, name);
		String who = names.getOrDefault(id, "Someone");
		if (added) {
			leaving.remove(id);
			if (vanished.remove(id)) {
				if (vanish.get() && !said(who, "join")) alert(who + " is back in the tab list (was vanished)");
				returning.put(id, new Return("unvanished", seenBefore(id), ticks));
			}
		}
		if (mode == null) return;
		GameType before = modes.put(id, mode);
		if (before == null || before == mode) {
			// Already in spectator when we first saw them
			if (before == null && mode == GameType.SPECTATOR && !added && spectator.get()) alert(who + " is in spectator");
			return;
		}
		if (mode == GameType.SPECTATOR) {
			if (!spectator.get()) return;
			Seen seen = seenBefore(id);
			double far = seen == null || mc.player == null ? -1 : seen.pos.distanceTo(mc.player.position());
			alert(who + " went into spectator" + (far >= 0 ? " " + Math.round(far) + " blocks from you" : ""));
		} else if (before == GameType.SPECTATOR) {
			if (spectator.get()) chat(who + " left spectator (" + mode.getName() + ")");
			returning.put(id, new Return("came out of spectator", seenBefore(id), ticks));
		}
	}

	private void removed(UUID id) {
		if (isMe(id) || !names.containsKey(id)) return;
		leaving.put(id, ticks);
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
		// A new server: start over with its tab list
		if (mc.getConnection() != connection) {
			connection = mc.getConnection();
			reset();
		}
		ticks++;
		// Where everyone around you is, so a vanish or a switch can say how close they were
		for (Player p : mc.level.players()) {
			if (p != mc.player) lastSeen.put(p.getUUID(), new Seen(p.position(), ticks));
		}
		recentChat.removeIf(line -> ticks - line.tick > 80);

		// Off the tab list for a couple of seconds with no leave message: vanished
		for (Iterator<Map.Entry<UUID, Integer>> it = leaving.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Integer> e = it.next();
			if (ticks - e.getValue() < 40) continue;
			it.remove();
			UUID id = e.getKey();
			String who = names.getOrDefault(id, "Someone");
			if (said(who, "left") || said(who, "quit") || said(who, "disconnect") || said(who, "leave")) {
				modes.remove(id);
				lastSeen.remove(id);
				continue;
			}
			vanished.add(id);
			if (!vanish.get()) continue;
			Seen seen = seenBefore(id);
			double far = seen == null ? -1 : seen.pos.distanceTo(mc.player.position());
			alert(who + " vanished (left the tab list without leaving)" + (far >= 0 && far <= watchDistance.get() ? ", " + Math.round(far) + " blocks from you" : ""));
		}

		// Back from spectator or vanish: if they turn up near you, they were watching you
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
			alert(who + " " + r.how + " " + Math.round(far) + " blocks from you: they were watching you" + (travelled ? " (and teleported to you to do it)" : ""));
		}

		if (probe.get() && ticks >= nextProbe) sendProbe();
	}

	/** Where someone was last seen near you, if it was in the last few seconds. */
	private Seen seenBefore(UUID id) {
		Seen seen = lastSeen.get(id);
		return seen != null && ticks - seen.tick <= 60 ? seen : null;
	}

	/** Whether the server said something like "<name> left" recently. */
	private boolean said(String name, String word) {
		String n = name.toLowerCase(Locale.ROOT);
		for (Line line : recentChat) if (line.text.contains(n) && line.text.contains(word)) return true;
		return false;
	}

	// ---------------------------------------------------------------- finding hidden players

	private void sendProbe() {
		nextProbe = ticks + (int) (probeEvery.get() * 20);
		String command = probeCommand.get().trim().replaceFirst("^/", "");
		if (command.isEmpty() || command.contains(" ")) return;
		mc.getConnection().send(new ServerboundCommandSuggestionPacket(PROBE_ID, "/" + command + " "));
	}

	private void probed(List<String> offered) {
		if (mc.getConnection() == null || mc.player == null) return;
		Set<String> listed = new HashSet<>();
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) listed.add(info.getProfile().name().toLowerCase(Locale.ROOT));
		String me = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
		Set<String> now = new HashSet<>();
		for (String name : offered) {
			if (!NAME.matcher(name).matches()) continue;
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.equals(me) || listed.contains(lower)) continue;
			now.add(name);
		}
		// Only real names are worth it: a command that offers anything else (items, words) is the wrong one
		if (now.size() > 8) return;
		for (String name : now) if (hidden.add(name)) alert(name + " is online but hidden from the tab list");
		for (Iterator<String> it = hidden.iterator(); it.hasNext(); ) {
			String name = it.next();
			if (!now.contains(name)) {
				it.remove();
				chat(name + " isn't hidden any more (left, or showed up in the tab list)");
			}
		}
	}

	// ---------------------------------------------------------------- telling you

	private void report() {
		List<String> spec = new ArrayList<>();
		for (Map.Entry<UUID, GameType> e : modes.entrySet()) {
			if (e.getValue() == GameType.SPECTATOR && !isMe(e.getKey()) && !vanished.contains(e.getKey())) spec.add(names.getOrDefault(e.getKey(), "?"));
		}
		List<String> gone = new ArrayList<>();
		for (UUID id : vanished) gone.add(names.getOrDefault(id, "?"));
		chat("In spectator: " + (spec.isEmpty() ? "nobody" : String.join(", ", spec)));
		chat("Vanished: " + (gone.isEmpty() ? "nobody" : String.join(", ", gone)));
		chat("Online but hidden: " + (hidden.isEmpty() ? (probe.get() ? "nobody found" : "Find Hidden is off") : String.join(", ", hidden)));
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
