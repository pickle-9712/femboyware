package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
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
	private final StringSetting whitelist = add(new StringSetting("Whitelist", "Names to ignore (your alts, friends), comma separated", ""));
	private final BoolSetting webhook = add(new BoolSetting("Webhook", "Also post to a Discord webhook", false));
	private final StringSetting webhookUrl = add(new StringSetting("Webhook URL", "Your Discord webhook link", ""));
	private final StringSetting discordId = add(new StringSetting("Ping ID", "Your Discord user ID, to be pinged (blank for no ping)", ""));

	private final Set<String> detected = new HashSet<>();
	private ClientLevel lastLevel;

	public PlayerDetectionModule() {
		super("Player Detection", "Alerts (and can leave) when another player loads in near you", Category.MISC);
	}

	@Override
	protected void onEnable() {
		detected.clear();
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
			if (!ignored.contains(name.toLowerCase(Locale.ROOT))) now.add(name);
		}
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

	private void fire(Set<String> players) {
		String names = String.join(", ", players);
		if (notify.get() != Notify.TOAST) ChatUtil.chat((players.size() == 1 ? "Player detected: " : "Players detected: ") + names);
		if (notify.get() != Notify.CHAT)
			//? if >=26.1 {
			/*SystemToast.addOrUpdate(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(getName()), Component.literal("Player detected: " + names));
			*///?} else {
			SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(getName()), Component.literal("Player detected: " + names));
			//?}
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
