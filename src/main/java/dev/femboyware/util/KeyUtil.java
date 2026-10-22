package dev.femboyware.util;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class KeyUtil {
	private static final MinecraftClient mc = MinecraftClient.getInstance();

	private KeyUtil() {
	}

	/** Whether the key or mouse button bound to {@code binding} is held down right now, ignoring screens. */
	public static boolean isPhysicallyPressed(KeyBinding binding) {
		InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(binding);
		if (key.getCode() == InputUtil.UNKNOWN_KEY.getCode()) return false;
		long handle = mc.getWindow().getHandle();
		if (key.getCategory() == InputUtil.Type.MOUSE) return GLFW.glfwGetMouseButton(handle, key.getCode()) == GLFW.GLFW_PRESS;
		return InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
	}

	/** Puts {@code binding} back to whatever the physical key says. */
	public static void sync(KeyBinding binding) {
		binding.setPressed(isPhysicallyPressed(binding));
	}
}
