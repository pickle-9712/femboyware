package dev.femboyware.module.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.DiscordIpc;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;

/**
 * Shows femboyware and where you are (main menu, singleplayer, or the server) in your Discord
 * status, through the Discord app on your PC. Discord needs an application to show a card: make
 * one called femboyware at discord.com/developers/applications (Rich Presence art: an image named
 * "logo") and paste its Application ID here. Ported from the Anubis client's Discord Presence
 * (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class DiscordPresenceModule extends Module {
	private static final long GAME_STARTED = ProcessHandle.current().info().startInstant().orElseGet(Instant::now).getEpochSecond();

	private final StringSetting appId = add(new StringSetting("Application ID", "Your Discord application's ID (discord.com/developers); its name is what Discord shows", ""));
	private final StringSetting line = add(new StringSetting("Tagline", "Second line of the card (blank for none)", "femboyware on top"));
	private final BoolSetting showIp = add(new BoolSetting("Show Server IP", "Say which server you're on (off: just Multiplayer)", true));
	private final StringSetting buttonUrl = add(new StringSetting("Button Link", "A button on the card linking here (blank for none)", "https://femboyware.xyz"));

	private record Card(String details, String state, long startedAt, String button) {
	}

	private volatile Card card;
	private Link link;
	private Connection session;
	private long joinedAt;

	public DiscordPresenceModule() {
		super("Discord Presence", "Shows femboyware and your server in your Discord status", Category.MISC);
	}

	@Override
	protected void onEnable() {
		session = null;
		card = new Card("In Main Menu", line.get(), GAME_STARTED, buttonUrl.get().trim());
		link = new Link(link);
		refresh();
		link.start();
	}

	@Override
	protected void onDisable() {
		if (link != null) link.stop();
		session = null;
	}

	@Override
	public void onTick() {
		refresh();
	}

	@Override
	public void onFrame() {
		// Ticks stop in menus: keep the card current there too
		if (mc.level == null && link != null) refresh();
	}

	private void refresh() {
		ClientPacketListener listener = mc.getConnection();
		String details;
		long startedAt;
		if (listener != null) {
			if (listener.getConnection() != session) {
				session = listener.getConnection();
				joinedAt = System.currentTimeMillis() / 1000;
			}
			details = place(listener.getServerData());
			startedAt = joinedAt;
		} else {
			session = null;
			details = "In Main Menu";
			startedAt = GAME_STARTED;
		}
		Card next = new Card(details, line.get().trim(), startedAt, buttonUrl.get().trim());
		if (!next.equals(card)) {
			card = next;
			if (link != null) link.wake();
		}
	}

	private String place(ServerData server) {
		if (mc.isLocalServer()) return "Singleplayer";
		if (server == null) return "Multiplayer";
		if (server.isRealm()) return "Realms";
		if (server.isLan()) return "LAN World";
		return showIp.get() ? "Playing " + server.ip : "Multiplayer";
	}

	private static JsonObject activity(Card card) {
		JsonObject activity = new JsonObject();
		activity.addProperty("details", card.details());
		if (!card.state().isEmpty()) activity.addProperty("state", card.state());
		JsonObject timestamps = new JsonObject();
		timestamps.addProperty("start", card.startedAt());
		activity.add("timestamps", timestamps);
		JsonObject assets = new JsonObject();
		assets.addProperty("large_image", "logo");
		assets.addProperty("large_text", "femboyware");
		activity.add("assets", assets);
		if (card.button().startsWith("https://")) {
			JsonObject button = new JsonObject();
			button.addProperty("label", "femboyware");
			button.addProperty("url", card.button());
			JsonArray buttons = new JsonArray();
			buttons.add(button);
			activity.add("buttons", buttons);
		}
		return activity;
	}

	/** Keeps a connection to Discord on its own thread and sends the card when it changes. */
	private final class Link implements Runnable {
		private final Thread thread = new Thread(this, "femboyware Discord Presence");
		private volatile boolean running = true;
		private Thread previous;
		private DiscordIpc ipc;
		private Card sent;
		private long sentAt, retryMs = 5000;

		Link(Link previous) {
			this.previous = previous != null ? previous.thread : null;
			thread.setDaemon(true);
		}

		void start() {
			thread.start();
		}

		void wake() {
			LockSupport.unpark(thread);
		}

		void stop() {
			running = false;
			LockSupport.unpark(thread);
		}

		@Override
		public void run() {
			if (previous != null) {
				try {
					previous.join();
				} catch (InterruptedException e) {
					running = false;
				}
				previous = null;
			}
			while (running) {
				if (ipc == null && !connect()) {
					pause(retryMs);
					retryMs = Math.min(retryMs * 2, 30000);
					continue;
				}
				try {
					ipc.poll();
					Card next = card;
					long since = System.currentTimeMillis() - sentAt;
					if (sent == null || since >= (next.equals(sent) ? 60000 : 4000)) send(next);
				} catch (IOException lost) {
					hangUp();
					continue;
				}
				pause(1000);
			}
			if (ipc != null) {
				try {
					ipc.setActivity(null);
				} catch (IOException | DiscordIpc.RefusedException ignored) {
				}
				hangUp();
			}
		}

		private boolean connect() {
			String id = appId.get().trim();
			if (!id.matches("\\d{15,25}")) return false;
			try {
				ipc = DiscordIpc.connect(id);
			} catch (IOException failure) {
				return false;
			}
			if (ipc == null) return false;
			retryMs = 5000;
			sent = null;
			return true;
		}

		private void send(Card next) throws IOException {
			try {
				ipc.setActivity(activity(next));
			} catch (DiscordIpc.RefusedException ignored) {
			}
			sent = next;
			sentAt = System.currentTimeMillis();
		}

		private void hangUp() {
			ipc.close();
			ipc = null;
			sent = null;
		}

		private void pause(long millis) {
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
		}
	}
}
