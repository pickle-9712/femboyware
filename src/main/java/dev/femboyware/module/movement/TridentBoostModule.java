package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;

/** Riptide anywhere, not just in water or rain, and with a stronger launch. Applied by the trident mixin. */
public class TridentBoostModule extends Module {
	private final BoolSetting anywhere = add(new BoolSetting("Anywhere", "Riptide without water or rain", true));
	private final NumberSetting multiplier = add(new NumberSetting("Multiplier", "Riptide launch strength multiplier", 1.5, 0.5, 5, 0.1));

	public TridentBoostModule() {
		super("Trident Boost", "Better riptide tridents", Category.RAGE);
	}

	public boolean anywhere() {
		return isEnabled() && anywhere.get();
	}

	public float modifyStrength(float strength) {
		return isEnabled() ? strength * multiplier.getFloat() : strength;
	}
}
