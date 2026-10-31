package dev.femboyware.module.effects;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.Setting;

/** Base for the Targets and Effects tabs: a shared clock and the colour palette. */
public abstract class FxModule extends Module {
	private static final long START = System.nanoTime();

	protected FxModule(String name, String description, Category category) {
		super(name, description, category);
	}

	/** Seconds since the game started; every effect animates off this, so they stay in step. */
	public static double time() {
		return (System.nanoTime() - START) / 1e9;
	}

	protected static float tickDelta() {
		return mc.getRenderTickCounter().getTickProgress(true);
	}

	/** Adds the colour settings at this point in the settings list. */
	protected Palette palette(int first, int second) {
		return Palette.create(this, first, second);
	}

	<S extends Setting<?>> S addSetting(S setting) {
		return add(setting);
	}
}
