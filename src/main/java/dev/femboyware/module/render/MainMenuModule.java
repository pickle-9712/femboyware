package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;

/** Swaps the vanilla title screen for femboyware's own pink one. Turn it off for the vanilla menu. */
public class MainMenuModule extends Module {
	private final NumberSetting swirlSpeed = add(new NumberSetting("Swirl Speed", "How fast the background swirls and drifts, 0 holds it still", 1, 0, 5, 0.1));
	private final BoolSetting skin = add(new BoolSetting("Skin", "Show your skin on the right", true));

	public MainMenuModule() {
		super("Main Menu", "Femboyware's own pink main menu", Category.COSMETIC);
		setEnabled(true);
	}
		
	public float swirlSpeed() {
		return swirlSpeed.getFloat();
	}

	public boolean skin() {
		return skin.get();
	}
}
