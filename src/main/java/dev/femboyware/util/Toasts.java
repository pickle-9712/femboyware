package dev.femboyware.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;

/** A small corner toast, the same on every Minecraft version. */
public final class Toasts {
	private Toasts() {
	}

	public static ToastManager manager() {
		Minecraft mc = Minecraft.getInstance();
		//? if >=26.1 {
		/*return mc.gui.toastManager();
		*///?} else {
		return mc.getToastManager();
		//?}
	}

	public static void show(String title, String message) {
		SystemToast.addOrUpdate(manager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(message));
	}
}
