package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;

/** Swaps the vanilla title screen for femboyware's own pink one. Turn it off for the vanilla menu. */
public class MainMenuModule extends Module {
	private final BoolSetting particles = add(new BoolSetting("Particles", "Floating hearts in the background", true));
	private final NumberSetting particleCount = add(new NumberSetting("Particle Count", "How many hearts float about", 40, 5, 120, 5)).visibleWhen(particles::get);
	private final BoolSetting curtains = add(new BoolSetting("Curtains", "Flowing pink curtains at the sides", true));
	private final BoolSetting skin = add(new BoolSetting("Skin", "Show your skin on the right", true));

	public MainMenuModule() {
		super("Main Menu", "Femboyware's own pink main menu", Category.COSMETIC);
		setEnabled(true);
	}

	public boolean particles() {
		return particles.get();
	}

	public int particleCount() {
		return particleCount.getInt();
	}

	public boolean curtains() {
		return curtains.get();
	}

	public boolean skin() {
		return skin.get();
	}
}
