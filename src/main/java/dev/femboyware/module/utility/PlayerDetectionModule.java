package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Webhook;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/**
 * Says the moment another player is loaded in your world (on DonutSMP, that's anyone near your
 * base) and can leave the server, toggle modules and ping a Discord webhook. Ported from the
 * Anubis client's Player Detection (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client),
 * without its Panic Pay.
 */
public class PlayerDetectionModule extends Module {
	public enum Notify {
		CHAT, TOAST, BOTH
	}

	private final EnumSetting<Notify> notify = add(new EnumSetting<>("Notify", "How to tell you", Notify.BOTH));
	private final BoolSetting disconnect = add(new BoolSetting("Disconnect", "Leave the server half a second after someone shows up (Auto Reconnect won't rejoin)", true));
	private final BoolSetting turnOff = add(new BoolSetting("Turn Off", "Turn this off after it goes off once", true));
	private final StringSetting toggle = add(new StringSetting("Modules To Toggle", "Modules to flip on or off when someone shows up, comma separated", ""));
	private final NumberSetting range = add(new NumberSetting("Range", "Only players this close count (0 = anyone loaded)", 0, 0, 256, 8));
	private final NumberSetting confirmTicks = add(new NumberSetting("Confirm Ticks", "They must stay loaded this long first, so anticheat bots and people flying past the edge don't set it off", 10, 0, 100, 1));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Your friends list doesn't count", true));
	private final StringSetting whitelist = add(new StringSetting("Whitelist", "Names to ignore (your alts, friends), comma separated", ""));
	private final BoolSetting webhook = add(new BoolSetting("Webhook", "Also post to a Discord webhook", false));
	private final StringSetting webhookUrl = add(new StringSetting("Webhook URL", "Your Discord webhook link", ""));
	private final StringSetting discordId = add(new StringSetting("Ping ID", "Your Discord user ID, to be pinged (blank for no ping)", ""));

	private final Set<String> detected = new HashSet<>();
	/** How many ticks each candidate has stayed loaded, by name. */
	private final java.util.Map<String, Integer> seenFor = new java.util.HashMap<>();
	private static final java.util.regex.Pattern REAL_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.]{1,17}");
	private ClientLevel lastLevel;

	public PlayerDetectionModule() {
		super("Player Detection", "Alerts (and can leave) when another player loads in near you", Category.MISC);
	}

	@Override
	protected void onEnable() {
		detected.clear();
		seenFor.clear();
		lastLevel = null;
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null) {
			detected.clear();
			lastLevel = null;
			return;
		}
		if (mc.level != lastLevel) {
			detected.clear();
			lastLevel = mc.level;
		}
		Set<String> ignored = new HashSet<>();
		for (String n : whitelist.get().split(",")) if (!n.isBlank()) ignored.add(n.trim().toLowerCase(Locale.ROOT));
		String self = mc.player.getGameProfile().name();
		Set<String> now = new LinkedHashSet<>();
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == mc.player || player.getUUID().equals(mc.player.getUUID())) continue;
			String name = player.getGameProfile().name();
			if (name == null || name.equals(self) || name.equalsIgnoreCase("freecamera")) continue;
			if (FemboyWare.getModules().get(dev.femboyware.module.client.FakePlayerModule.class).isFake(player.getId())) continue;
			if (!isRealPlayer(player)) continue;
			if (range.get() > 0 && player.distanceTo(mc.player) > range.get()) continue;
			if (ignoreFriends.get() && FemboyWare.getFriends().isFriend(player)) continue;
			if (!ignored.contains(name.toLowerCase(Locale.ROOT))) now.add(name);
		}
		// Count up how long each one's been here; only those past Confirm Ticks go on
		seenFor.keySet().retainAll(now);
		for (String name : now) seenFor.merge(name, 1, Integer::sum);
		now.removeIf(name -> seenFor.get(name) < confirmTicks.getInt());
		if (now.isEmpty()) {
			detected.clear();
			return;
		}
		if (now.equals(detected)) return;
		// Only fire for someone new, not when one of several walks off
		boolean someoneNew = !detected.containsAll(now);
		detected.clear();
		detected.addAll(now);
		if (someoneNew) fire(now);
	}

	/**
	 * A real player, not an NPC, hologram or anticheat bot: in the tab list, an ordinary account id
	 * (NPC plugins hand out version 2 ids) and a name a real account can have (Bedrock names start with a dot).
	 */
	private boolean isRealPlayer(AbstractClientPlayer player) {
		String name = player.getGameProfile().name();
		if (name == null || !REAL_NAME.matcher(name).matches()) return false;
		if (player.getUUID().version() == 2) return false;
		return mc.getConnection() != null && mc.getConnection().getPlayerInfo(player.getUUID()) != null;
	}

	private void fire(Set<String> players) {
		String names = String.join(", ", players);
		if (notify.get() != Notify.TOAST) ChatUtil.chat((players.size() == 1 ? "Player detected: " : "Players detected: ") + names);
		if (notify.get() != Notify.CHAT)
			dev.femboyware.util.Toasts.show(getName(), "Player detected: " + names);
		for (Module module : modulesToToggle()) module.toggle();
		if (webhook.get()) sendWebhook(players);
		if (turnOff.get()) setEnabled(false);
		if (disconnect.get() && mc.getConnection() != null) {
			Connection session = mc.getConnection().getConnection();
			CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS, mc).execute(() -> {
				if (mc.getConnection() == null || mc.getConnection().getConnection() != session || !session.isConnected()) return;
				dev.femboyware.module.utility.AutoReconnectModule.holdOff();
				session.disconnect(Component.literal("[Player Detection] " + names));
			});
		}
	}

	private List<Module> modulesToToggle() {
		List<Module> list = new ArrayList<>();
		for (String entry : toggle.get().split(",")) {
			if (entry.isBlank()) continue;
			Module module = FemboyWare.getModules().get(entry.trim());
			if (module != null && module != this && !list.contains(module)) list.add(module);
		}
		return list;
	}

	private void sendWebhook(Set<String> players) {
		if (!Webhook.valid(webhookUrl.get())) {
			ChatUtil.info("Player Detection: the webhook URL isn't set or isn't a link");
			return;
		}
		var server = mc.getCurrentServer();
		String id = discordId.get().trim();
		Webhook.Embed embed = new Webhook.Embed("🚨 Player Detection",
			"**Players:** " + Webhook.escape(String.join(", ", players)) + "\n**Server:** " + Webhook.escape(server != null && server.ip != null ? server.ip : "Singleplayer")
				+ "\n**Time:** <t:" + System.currentTimeMillis() / 1000 + ":R>", 0xE74C3C).content(id.matches("\\d{5,25}") ? "<@" + id + ">" : "");
		Webhook.send(webhookUrl.get(), embed, sent -> ChatUtil.info(sent ? "Player Detection: webhook sent" : "Player Detection: webhook failed"));
	}
}
