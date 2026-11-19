package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.Mc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Sends your messages over and over. Bypass adds random letters so anti-spam doesn't see repeats.
 * Turns off when you leave unless you say otherwise. Ported from the Anubis client's Spam
 * (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class SpamModule extends Module {
	private final StringSetting messages = add(new StringSetting("Messages", "Messages to send, separated by | (start one with / for a command)", "femboyware on top"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between messages (20 = 1 second)", 20, 0, 1200, 1));
	private final BoolSetting random = add(new BoolSetting("Randomise", "Pick a random message each time instead of going in order", false));
	private final BoolSetting split = add(new BoolSetting("Auto Split", "Send long messages in pieces", false));
	private final NumberSetting splitLength = add(new NumberSetting("Split Length", "Characters per piece", 256, 1, 256, 1));
	private final NumberSetting splitDelay = add(new NumberSetting("Split Delay", "Ticks between pieces", 20, 0, 200, 1));
	private final BoolSetting bypass = add(new BoolSetting("Bypass", "Add random letters to the end so it isn't a repeat", false));
	private final BoolSetting uppercase = add(new BoolSetting("Uppercase", "Bypass letters can be capitals", true));
	private final NumberSetting length = add(new NumberSetting("Bypass Length", "How many random letters", 16, 1, 64, 1));
	private final BoolSetting offOnLeave = add(new BoolSetting("Off On Leave", "Turn off when you leave or switch worlds", true));

	private int index, timer, piece;
	private String text;
	private ClientLevel level;

	public SpamModule() {
		super("Spam", "Sends your messages in chat on a timer", Category.MISC);
	}

	@Override
	protected void onEnable() {
		timer = delay.get().intValue();
		index = 0;
		piece = 0;
		text = null;
		level = mc.level;
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null || mc.getConnection() == null) {
			if (offOnLeave.get()) setEnabled(false);
			return;
		}
		if (mc.level != level) {
			level = mc.level;
			if (offOnLeave.get()) {
				setEnabled(false);
				return;
			}
			timer = delay.get().intValue();
		}
		List<String> list = new ArrayList<>();
		for (String m : messages.get().split("\\|")) if (!m.isBlank()) list.add(m.trim());
		if (list.isEmpty()) return;
		if (timer > 0) {
			timer--;
			return;
		}
		if (text == null) {
			int i = random.get() ? ThreadLocalRandom.current().nextInt(list.size()) : index++ % list.size();
			text = list.get(i);
			if (bypass.get()) text += " " + letters(length.get().intValue(), uppercase.get());
		}
		int chunk = splitLength.get().intValue();
		if (split.get() && text.length() > chunk) {
			int pieces = (text.length() + chunk - 1) / chunk;
			int start = piece * chunk;
			send(text.substring(start, Math.min(start + chunk, text.length())));
			piece = (piece + 1) % pieces;
			timer = splitDelay.get().intValue();
			if (piece == 0) {
				timer = delay.get().intValue();
				text = null;
			}
			return;
		}
		send(text.length() > 256 ? text.substring(0, 256) : text);
		timer = delay.get().intValue();
		text = null;
	}

	private static String letters(int n, boolean upper) {
		String pool = upper ? "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ" : "abcdefghijklmnopqrstuvwxyz";
		StringBuilder out = new StringBuilder(n);
		for (int i = 0; i < n; i++) out.append(pool.charAt(ThreadLocalRandom.current().nextInt(pool.length())));
		return out.toString();
	}

	private void send(String message) {
		Mc.chat().addRecentChat(message);
		if (message.startsWith("/")) mc.getConnection().sendCommand(message.substring(1));
		else mc.getConnection().sendChat(message);
	}
}
