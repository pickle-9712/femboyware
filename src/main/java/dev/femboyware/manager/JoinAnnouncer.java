package dev.femboyware.manager;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * Says in chat that you're using femboyware every time you join a server, so nobody using it can
 * pretend they aren't. Deliberately not a module and has no setting: it can't be turned off.
 */
public final class JoinAnnouncer {
	public static final String MESSAGE = "Hi! I'm using Femboyware!";
	/** Ticks after joining before it's said, so login screens, resource packs and spawn are out of the way. */
	private static final int DELAY = 60;

	private static int countdown = -1;

	private JoinAnnouncer() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			// Singleplayer has nobody to tell
			countdown = client.hasSingleplayerServer() ? -1 : DELAY;
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> countdown = -1);
		ClientTickEvents.END_CLIENT_TICK.register(JoinAnnouncer::tick);
	}

	private static void tick(Minecraft mc) {
		if (countdown < 0) return;
		if (mc.player == null || mc.getConnection() == null) return;
		if (--countdown > 0) return;
		countdown = -1;
		mc.getConnection().sendChat(MESSAGE);
	}
}
