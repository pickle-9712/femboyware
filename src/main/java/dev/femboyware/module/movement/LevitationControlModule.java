package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.effect.MobEffects;

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
		if (!isEnabled() || !mc.player.hasEffect(MobEffects.LEVITATION)) return;
		double y = 0;
		if (mc.options.keyJump.isDown()) y = upSpeed.get();
		else if (mc.options.keyShift.isDown()) y = -downSpeed.get();
		mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, y, mc.player.getDeltaMovement().z);
	}
}
