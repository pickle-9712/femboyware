package dev.femboyware.util.playerbypass;

import dev.femboyware.module.Module;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * The bits of the Anubis client's chat and toast helpers that Player Bypass talks through, sending to
 * femboyware's chat and toasts instead. Shape ported from Anubis (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client). Client-side only: nothing goes to the server.
 */
public final class ModuleChat {
	private ModuleChat() {
	}

	public static Body body() {
		return new Body();
	}

	public static void send(Module module, Body body) {
		ChatUtil.info(body.toString());
	}

	public static void send(Module module, String text) {
		ChatUtil.info(text);
	}

	public static void actionBarAlways(Module module, Body body) {
		Mc.actionBar(body.toString());
	}

	/** A Player Bypass toast; {@code icon} is unused (femboyware's toasts are text). */
	public static void toast(ItemStack icon, String title, Component message, boolean sound) {
		dev.femboyware.util.Toasts.show(title, message.getString());
		if (sound) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
	}

	public static final class Body {
		private final StringBuilder text = new StringBuilder();

		public Body text(String s) {
			text.append(s);
			return this;
		}

		public Body name(String s) {
			return text(s);
		}

		public Body danger(String s) {
			return text(s);
		}

		public Body good(String s) {
			return text(s);
		}

		public Body warn(String s) {
			return text(s);
		}

		public Body value(Object v) {
			return text(String.valueOf(v));
		}

		public Body coords(int x, int y, int z) {
			return text(x + ", " + y + ", " + z);
		}

		public Body append(Component c) {
			return text(c.getString());
		}

		@Override
		public String toString() {
			return text.toString();
		}
	}
}
