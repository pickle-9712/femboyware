package dev.femboyware.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class KeyUtil {
	private static final Minecraft mc = Minecraft.getInstance();

	private KeyUtil() {
	}

	/** Whether the key or mouse button bound to {@code binding} is held down right now, ignoring screens. */
	public static boolean isPhysicallyPressed(KeyMapping binding) {
		InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(binding);
		if (key.getValue() == InputConstants.UNKNOWN.getValue()) return false;
		long handle = mc.getWindow().handle();
		if (key.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
		return isKeyDown(key.getValue());
	}

	/** Whether a keyboard key is held down right now. */
	public static boolean isKeyDown(int key) {
		//? if >=26.3 {
		/*return InputConstants.isKeyDown(key);
		*///?} else {
		return InputConstants.isKeyDown(mc.getWindow(), key);
		//?}
	}

	/** Puts {@code binding} back to whatever the physical key says. */
	public static void sync(KeyMapping binding) {
		binding.setDown(isPhysicallyPressed(binding));
	}
}
