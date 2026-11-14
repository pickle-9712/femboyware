package dev.femboyware.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Client-side chat messages from the client itself. Nothing is sent to the server. */
public final class ChatUtil {
	private ChatUtil() {
	}

	/** Above 0 while a dot-command runs: its replies belong in chat. */
	public static int replying;

	/**
	 * Something from the client. A command's reply goes in chat; anything else (alerts, warnings,
	 * things found) goes to the Notifications module, which shows it as a toast, in chat, or both.
	 */
	public static void info(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		if (replying == 0 && dev.femboyware.module.hud.NotificationsModule.alert(message)) return;
		chat(message);
	}

	/** Straight to chat, whatever the Notifications module says. */
	public static void chat(String message) {
		if (Minecraft.getInstance().gui == null) return;
		Mc.addChat(Component.literal("[femboyware] ").withStyle(ChatFormatting.LIGHT_PURPLE)
			.append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
	}

	public static String coords(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}
