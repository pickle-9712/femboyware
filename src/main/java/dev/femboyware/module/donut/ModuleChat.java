package dev.femboyware.module.donut;

import dev.femboyware.module.Module;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import net.minecraft.network.chat.Component;

/**
 * The bits of the Anubis client's chat helper the DonutSMP macros talk through, printed to
 * femboyware's chat instead. Shape ported from Anubis (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client). Client-side only: nothing goes to the server.
 */
public final class ModuleChat {
	private ModuleChat() {
	}

	public static Body body() {
		return new Body();
	}

	public static void send(Module module, Body body) {
		ChatUtil.info(module.getName() + ": " + body);
	}

	public static void send(Module module, Component body) {
		ChatUtil.info(module.getName() + ": " + body.getString());
	}

	public static void send(Module module, String text) {
		ChatUtil.info(module.getName() + ": " + text);
	}

	public static void sendAlways(Module module, Body body) {
		send(module, body);
	}

	public static void actionBar(Module module, Body body) {
		Mc.actionBar(body.toString());
	}

	public static void actionBarAlways(Module module, Body body) {
		Mc.actionBar(body.toString());
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

		public Component build() {
			return Component.literal(text.toString());
		}

		@Override
		public String toString() {
			return text.toString();
		}
	}
}
