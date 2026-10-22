package dev.femboyware.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/** Client-side chat messages from the client itself. Nothing is sent to the server. */
public final class ChatUtil {
	private ChatUtil() {
	}

	public static void info(String message) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.inGameHud == null) return;
		mc.inGameHud.getChatHud().addMessage(Text.literal("[femboyware] ").formatted(Formatting.LIGHT_PURPLE)
			.append(Text.literal(message).formatted(Formatting.WHITE)));
	}

	public static String coords(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}
