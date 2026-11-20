package dev.femboyware.module.donut;

import java.util.Map;
import net.minecraft.client.Minecraft;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
final class TeleportConfirm {
	private static final long WINDOW_MS = 3000L;
	private final dev.femboyware.module.Module owner;
	private Object pressed;
	private boolean warned;
	private long sentAt;

	TeleportConfirm(dev.femboyware.module.Module owner) {
		this.owner = owner;
	}

	void reset() {
		this.pressed = null;
		this.warned = false;
		this.sentAt = 0L;
	}

	void sent() {
		this.sentAt = System.currentTimeMillis();
	}

	boolean tick(Minecraft mc) {
		Dialogs.View view = Dialogs.current(mc);
		if (view != null && view.titled("teleport")) {
			if (view.dialog() == this.pressed) {
				return true;
			}

			if (System.currentTimeMillis() - this.sentAt > 3000L) {
				return false;
			}

			Dialogs.Button confirm = view.labelled("confirm", "yes");
			if (confirm == null) {
				return false;
			}

			if (!this.warned) {
				this.warned = true;
				ModuleChat.send(
					this.owner,
					ModuleChat.body()
						.warn("Your teleport confirm menu is on")
						.text(". Turn it off in /settings so requests go out faster. Confirming each one for now.")
				);
			}

			this.pressed = view.dialog();
			Dialogs.press(mc, view, confirm, Map.of());
			return true;
		} else {
			return false;
		}
	}
}
