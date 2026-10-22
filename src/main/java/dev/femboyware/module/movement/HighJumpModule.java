package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;

/**
 * Jump higher than normal.
 * Ported from Meteor Client's HighJump (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class HighJumpModule extends Module {
	private final NumberSetting multiplier = add(new NumberSetting("Multiplier", "Jump speed multiplier", 1.5, 0, 5, 0.05));

	public HighJumpModule() {
		super("High Jump", "Jump higher", Category.RAGE);
	}

	/** Called from LivingEntityMixin for the local player's jump speed. */
	public float multiplier() {
		return isEnabled() ? multiplier.getFloat() : 1f;
	}
}
