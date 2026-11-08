package dev.femboyware.module.movement;

import dev.femboyware.util.Mc;
import dev.femboyware.mixin.CreativeInventoryScreenAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.KeyUtil;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.item.CreativeModeTab;
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
		Screen screen = Mc.screen();
		if (screen == null || !allows(screen)) {
			wasActive = false;
			return;
		}
		wasActive = true;
		for (KeyMapping binding : movementKeys()) KeyUtil.sync(binding);
		if (sneak.get()) KeyUtil.sync(mc.options.keyShift);

		if (arrowLook.get()) {
			long handle = mc.getWindow().handle();
			float speed = lookSpeed.getFloat();
			if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) mc.player.setYRot(mc.player.getYRot() - speed);
			if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) mc.player.setYRot(mc.player.getYRot() + speed);
			if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) mc.player.setXRot(Mth.clamp(mc.player.getXRot() - speed, -90f, 90f));
			if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) mc.player.setXRot(Mth.clamp(mc.player.getXRot() + speed, -90f, 90f));
		}
	}

	private void release() {
		for (KeyMapping binding : movementKeys()) binding.setDown(false);
		mc.options.keyShift.setDown(false);
	}

	private KeyMapping[] movementKeys() {
		return new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump, mc.options.keySprint};
	}

	/** Screens with text input would type and walk at the same time, so they are left alone. */
	private static boolean allows(Screen screen) {
		if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen || screen instanceof AnvilScreen || screen instanceof BookEditScreen) return false;
		// The creative search tab has a text box
		return !(screen instanceof CreativeModeInventoryScreen) || CreativeInventoryScreenAccessor.femboyware$getSelectedTab().getType() != CreativeModeTab.Type.SEARCH;
	}
}
