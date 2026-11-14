package dev.femboyware.module.client;

import dev.femboyware.util.Mc;
import dev.femboyware.gui.ClickGuiScreen;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import org.lwjgl.glfw.GLFW;

public class ClickGuiModule extends Module {
	private ClickGuiScreen screen;
	/** The starred modules, by name, kept in the config; set from the menu, not shown in it. */
	private final dev.femboyware.setting.StringSetting favorites = add(new dev.femboyware.setting.StringSetting("Favorites", "Starred modules", "")).visibleWhen(() -> false);

	/** How the menu comes in and goes out; closing plays its animation backwards. */
	public enum Animation { NONE, POP, GROW, SLIDE_UP, SLIDE_DOWN, SLIDE_LEFT, SLIDE_RIGHT, DROP, UNFOLD, FLIP, SPIN }

	private final dev.femboyware.setting.EnumSetting<Animation> openAnimation = add(new dev.femboyware.setting.EnumSetting<>("Open Animation", "How the menu comes in", Animation.POP));
	private final dev.femboyware.setting.NumberSetting openTime = add(new dev.femboyware.setting.NumberSetting("Open Time", "Seconds the open animation takes", 0.3, 0.05, 1.5, 0.01)).visibleWhen(() -> openAnimation.get() != Animation.NONE);
	private final dev.femboyware.setting.EnumSetting<Animation> closeAnimation = add(new dev.femboyware.setting.EnumSetting<>("Close Animation", "How the menu goes away (the animation played backwards)", Animation.GROW));
	private final dev.femboyware.setting.NumberSetting closeTime = add(new dev.femboyware.setting.NumberSetting("Close Time", "Seconds the close animation takes", 0.2, 0.05, 1.5, 0.01)).visibleWhen(() -> closeAnimation.get() != Animation.NONE);

	public Animation openAnimation() {
		return openAnimation.get();
	}

	public Animation closeAnimation() {
		return closeAnimation.get();
	}

	public double openTime() {
		return openTime.get();
	}

	public double closeTime() {
		return closeTime.get();
	}

	public boolean isFavorite(dev.femboyware.module.Module m) {
		for (String name : favorites.get().split("[|]")) if (name.equals(m.getName())) return true;
		return false;
	}

	public void toggleFavorite(dev.femboyware.module.Module m) {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (String name : favorites.get().split("[|]")) if (!name.isEmpty()) names.add(name);
		if (!names.remove(m.getName())) names.add(m.getName());
		favorites.set(String.join("|", names));
	}

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
		// Still animating shut: bring it back rather than opening it again
		if (Mc.screen() == screen) screen.reopen();
		else Mc.setScreen(screen);
	}

	@Override
	protected void onDisable() {
		// When the screen itself is closing it already disables us; only close it when toggled elsewhere
		if (screen != null && Mc.screen() == screen && !screen.isClosing()) screen.onClose();
	}

	/** Whether the GUI hangs in the world in front of you (WorldGui). Not finished yet, so always off. */
	public boolean worldScreen() {
		return false;
	}

	/** How tall the world screen is, in blocks, at its usual distance. */
	public double worldSize() {
		return 1.6;
	}
}
