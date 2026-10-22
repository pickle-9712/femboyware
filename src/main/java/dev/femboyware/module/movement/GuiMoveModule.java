package dev.femboyware.module.movement;

import dev.femboyware.mixin.CreativeInventoryScreenAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.KeyUtil;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.ItemGroup;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/** Lets you keep walking while inventories and menus are open. */
public class GuiMoveModule extends Module {
	private final BoolSetting sneak = add(new BoolSetting("Sneak", "Also allow sneaking", false));
	private final BoolSetting arrowLook = add(new BoolSetting("Arrow Look", "Turn your head with the arrow keys", true));
	private final NumberSetting lookSpeed = add(new NumberSetting("Look Speed", "Degrees per tick for arrow look", 6, 1, 20, 0.5)).visibleWhen(arrowLook::get);

	private boolean wasActive;

	public GuiMoveModule() {
		super("Gui Move", "Move while menus are open", Category.BASIC);
	}

	@Override
	protected void onDisable() {
		if (wasActive && !nullCheck()) release();
		wasActive = false;
	}

	@Override
	public void onTick() {
		Screen screen = mc.currentScreen;
		if (screen == null || !allows(screen)) {
			wasActive = false;
			return;
		}
		wasActive = true;
		for (KeyBinding binding : movementKeys()) KeyUtil.sync(binding);
		if (sneak.get()) KeyUtil.sync(mc.options.sneakKey);

		if (arrowLook.get()) {
			long handle = mc.getWindow().getHandle();
			float speed = lookSpeed.getFloat();
			if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) mc.player.setYaw(mc.player.getYaw() - speed);
			if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) mc.player.setYaw(mc.player.getYaw() + speed);
			if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() - speed, -90f, 90f));
			if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + speed, -90f, 90f));
		}
	}

	private void release() {
		for (KeyBinding binding : movementKeys()) binding.setPressed(false);
		mc.options.sneakKey.setPressed(false);
	}

	private KeyBinding[] movementKeys() {
		return new KeyBinding[]{mc.options.forwardKey, mc.options.backKey, mc.options.leftKey, mc.options.rightKey, mc.options.jumpKey, mc.options.sprintKey};
	}

	/** Screens with text input would type and walk at the same time, so they are left alone. */
	private static boolean allows(Screen screen) {
		if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen || screen instanceof AnvilScreen || screen instanceof BookEditScreen) return false;
		// The creative search tab has a text box
		return !(screen instanceof CreativeInventoryScreen) || CreativeInventoryScreenAccessor.femboyware$getSelectedTab().getType() != ItemGroup.Type.SEARCH;
	}
}
