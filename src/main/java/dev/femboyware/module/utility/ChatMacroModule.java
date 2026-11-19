package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.Mc;
import java.util.Locale;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Up to 8 keys that each send a chat message or command (or open chat with it typed). Ported from
 * the Anubis client's Chat Macro (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class ChatMacroModule extends Module {
	private static final int SLOTS = 8;

	private final BoolSetting openInChat = add(new BoolSetting("Open In Chat", "Put it in the chat box for you to send, instead of sending it", false));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Seconds between sends, so you don't get kicked for spam", 1, 0, 10, 0.5));
	private final KeySetting[] keys = new KeySetting[SLOTS];
	private final StringSetting[] texts = new StringSetting[SLOTS];
	private int pending = -1;
	private long lastSend;

	public ChatMacroModule() {
		super("Chat Macro", "Keys that send a chat message or command", Category.MISC);
		for (int i = 0; i < SLOTS; i++) {
			keys[i] = add(new KeySetting("Macro " + (i + 1) + " Key", "Press to send macro " + (i + 1), -1));
			texts[i] = add(new StringSetting("Macro " + (i + 1) + " Text", "What it sends (start with / for a command)", ""));
		}
	}

	@Override
	public void onKey(int key) {
		if (pending != -1) return;
		for (int i = 0; i < SLOTS; i++)
			if (keys[i].get() != -1 && keys[i].is(key) && !texts[i].get().isBlank()) {
				pending = i;
				return;
			}
	}

	@Override
	public void onTick() {
		int slot = pending;
		pending = -1;
		if (slot < 0 || mc.player == null || mc.getConnection() == null || Mc.screen() != null) return;
		String text = texts[slot].get().trim().replaceAll("\\s+", " ");
		if (text.isEmpty() || text.equals("/")) return;
		if (text.length() > 256) text = text.substring(0, 256);
		if (openInChat.get()) {
			Mc.setScreen(new ChatScreen(text, false));
			return;
		}
		long now = System.currentTimeMillis();
		long left = (long) (cooldown.get() * 1000) - (now - lastSend);
		if (left > 0) {
			Mc.actionBar(String.format(Locale.ROOT, "Chat Macro on cooldown, %.1fs left", left / 1000.0));
			return;
		}
		lastSend = now;
		Mc.chat().addRecentChat(text);
		if (text.startsWith("/")) mc.getConnection().sendCommand(text.substring(1));
		else mc.getConnection().sendChat(text);
	}
}
