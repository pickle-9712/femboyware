package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;

/** Riptide anywhere, not just in water or rain, and with a stronger launch. Applied by the trident mixin. */
public class TridentBoostModule extends Module {
	private final BoolSetting anywhere = add(new BoolSetting("Anywhere", "Riptide without water or rain", true));
	private final NumberSetting charge = add(new NumberSetting("Charge Scale", "How much of the normal half-second charge you need (1 = vanilla); from the Anubis client's Trident Util", 1, 0, 1, 0.1));
	private final NumberSetting multiplier = add(new NumberSetting("Multiplier", "Riptide launch strength multiplier", 1.5, 0.5, 5, 0.1));

	public TridentBoostModule() {
		super("Trident Boost", "Better riptide tridents", Category.RAGE);
	}

	/** Ticks of charge a throw needs. */
	public int chargeTicks(int vanilla) {
		return isEnabled() ? Math.max(1, (int) Math.round(vanilla * charge.get())) : vanilla;
	}

	public boolean anywhere() {
		return isEnabled() && anywhere.get();
	}

	public float modifyStrength(float strength) {
		return isEnabled() ? strength * multiplier.getFloat() : strength;
	}
}
