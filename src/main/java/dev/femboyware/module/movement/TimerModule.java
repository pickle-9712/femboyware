package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;

/** Speeds up (or slows down) the whole client tick rate. Applied by the render tick counter mixin. */
public class TimerModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Tick rate multiplier", 1.1, 0.1, 5, 0.01));

	public TimerModule() {
		super("Timer", "Changes how fast the game ticks", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return String.valueOf(speed.get());
	}

	public float getMultiplier() {
		return speed.getFloat();
	}
}
