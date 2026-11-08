package dev.femboyware.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Client-side chat messages from the client itself. Nothing is sent to the server. */
public final class ChatUtil {
	private ChatUtil() {
	}

	public static void info(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		Mc.addChat(Component.literal("[femboyware] ").withStyle(ChatFormatting.LIGHT_PURPLE)
			.append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
	}

	public static String coords(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}
