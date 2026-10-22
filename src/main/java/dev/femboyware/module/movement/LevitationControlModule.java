package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.entity.effect.StatusEffects;

/**
 * Lets you steer levitation: jump rises, sneak sinks and doing neither hovers. Applied after
 * vanilla air movement by the living entity mixin.
 */
public class LevitationControlModule extends Module {
	private final NumberSetting upSpeed = add(new NumberSetting("Up Speed", "Rise speed while holding jump", 0.3, 0, 2, 0.05));
	private final NumberSetting downSpeed = add(new NumberSetting("Down Speed", "Sink speed while sneaking", 0.3, 0, 2, 0.05));

	public LevitationControlModule() {
		super("Levitation Control", "Control your height while levitating", Category.RAGE);
	}

	public void afterTravel() {
		if (!isEnabled() || !mc.player.hasStatusEffect(StatusEffects.LEVITATION)) return;
		double y = 0;
		if (mc.options.jumpKey.isPressed()) y = upSpeed.get();
		else if (mc.options.sneakKey.isPressed()) y = -downSpeed.get();
		mc.player.setVelocity(mc.player.getVelocity().x, y, mc.player.getVelocity().z);
	}
}
