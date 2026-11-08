package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

/**
 * Climb ladders, vines and scaffolding faster.
 * Ported from Meteor Client's FastClimb (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 * Meteor's Timer mode isn't included.
 */
public class FastClimbModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Upward speed while climbing, vanilla is 0.2", 0.2872, 0.2, 1, 0.01));

	public FastClimbModule() {
		super("Fast Climb", "Climb faster", Category.BASIC);
	}

	@Override
	public void onTick() {
		boolean pushing = mc.player.horizontalCollision || mc.options.keyJump.isDown();
		if (!pushing || !mc.player.onClimbable()) return;
		Vec3 velocity = mc.player.getDeltaMovement();
		mc.player.setDeltaMovement(velocity.x, speed.get(), velocity.z);
	}
}
