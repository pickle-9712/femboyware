package dev.femboyware.manager;

import dev.femboyware.FemboyWare;
import dev.femboyware.util.Mc;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Says in chat that you're using the client every time you join a server, so nobody using it can
 * pretend they aren't. It opens the chat, types the message out a letter at a time and presses
 * enter, like you did it yourself. Deliberately not a module and has no setting: it can't be turned off.
 */
public final class JoinAnnouncer {
	/** Ticks after joining before it starts, so login screens, resource packs and spawn are out of the way. */
	private static final int DELAY = 60;
	/** Ticks between letters. */
	private static final int TYPE_EVERY = 1;

	private static int countdown = -1;
	/** The chat it opened and is typing into, or null when it isn't typing. */
	private static ChatScreen chat;
	private static int typed, nextKey;

	private JoinAnnouncer() {
	}

	/** The client's name as shown, from {@link FemboyWare#NAME}: "femboyware" becomes "Femboyware". */
	public static String clientName() {
		String name = FemboyWare.NAME;
		return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	public static String message() {
		return "Hi! I'm using " + clientName() + "!";
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			// Singleplayer has nobody to tell
			countdown = client.hasSingleplayerServer() ? -1 : DELAY;
			chat = null;
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			countdown = -1;
			chat = null;
		});
		ClientTickEvents.END_CLIENT_TICK.register(JoinAnnouncer::tick);
	}

	private static void tick(Minecraft mc) {
		if (countdown < 0) return;
		if (mc.player == null || mc.getConnection() == null) return;
		if (chat == null) {
			if (countdown > 0) countdown--;
			// Wait for any other screen (inventory, menu, a server's welcome GUI) to close first
			if (countdown > 0 || Mc.screen() != null) return;
			openChat(mc);
			return;
		}
		if (Mc.screen() != chat) {
			// The chat got closed or swapped for something else: start over once it's clear
			chat = null;
			return;
		}
		if (--nextKey > 0) return;
		nextKey = TYPE_EVERY;
		String message = message();
		if (typed < message.length()) {
			int codepoint = message.codePointAt(typed);
			//? if >=26.1 {
			/*chat.charTyped(new CharacterEvent(codepoint));
			*///?} else {
			chat.charTyped(new CharacterEvent(codepoint, 0));
			//?}
			typed += Character.charCount(codepoint);
			return;
		}
		// All typed: press enter, which sends it and closes the chat
		chat.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
		chat = null;
		countdown = -1;
	}

	private static void openChat(Minecraft mc) {
		chat = new ChatScreen("", false);
		Mc.setScreen(chat);
		typed = 0;
		nextKey = TYPE_EVERY;
	}
}
