package dev.femboyware.module.client;

import dev.femboyware.gui.ClickGuiScreen;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import org.lwjgl.glfw.GLFW;

public class ClickGuiModule extends Module {
	private ClickGuiScreen screen;

	public ClickGuiModule() {
		super("ClickGUI", "Opens this menu", Category.MISC, GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	/** The accent lives in the Customize panel's Colors module. */
	public int getAccent() {
		return Theme.accent();
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) {
			setEnabled(false);
			return;
		}
		if (screen == null) screen = new ClickGuiScreen(this);
		mc.setScreen(screen);
	}

	@Override
	protected void onDisable() {
		// When the screen itself is closing it already disables us; only close it when toggled elsewhere
		if (screen != null && mc.currentScreen == screen && !screen.isClosing()) mc.setScreen(null);
	}
}
