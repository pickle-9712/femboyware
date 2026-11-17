package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;

/**
 * The phone's and the Ledger's notifications: banners and dings for texts, Discord, wires, Forbes,
 * Shopify, and Ledger pop-ups. With this off none of them show or make a sound; calls still ring.
 * Module toggle pop-ups are the separate Module Notifications module.
 */
public class NotificationsModule extends Module {
	public NotificationsModule() {
		super("Notifications", "Phone and Ledger notifications: banners, pop-ups and their sounds", Category.LARP);
		setEnabled(true);
	}

	/** Whether phone and Ledger notifications should show and ding. */
	public static boolean on() {
		NotificationsModule self = FemboyWare.getModules() == null ? null : FemboyWare.getModules().get(NotificationsModule.class);
		return self == null || self.isEnabled();
	}
}
