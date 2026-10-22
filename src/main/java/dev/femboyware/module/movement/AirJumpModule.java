package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.FreeCamModule;
import dev.femboyware.setting.BoolSetting;

/**
 * Lets you jump in mid-air.
 * Ported from Meteor Client's AirJump (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AirJumpModule extends Module {
	private final BoolSetting maintainLevel = add(new BoolSetting("Maintain Level", "Hold jump to stay at the same height, sneak to go down a block", false));

	private int level;

	public AirJumpModule() {
		super("Air Jump", "Lets you jump in the air", Category.RAGE);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) return;
		level = mc.player.getBlockPos().getY();
		// Drop presses made while we were off
		while (mc.options.jumpKey.wasPressed()) ;
		while (mc.options.sneakKey.wasPressed()) ;
	}

	@Override
	public void onTick() {
		boolean blocked = FemboyWare.isEnabled(FreeCamModule.class) || mc.currentScreen != null || mc.player.isOnGround();
		while (mc.options.jumpKey.wasPressed()) {
			if (blocked) continue;
			level = mc.player.getBlockPos().getY();
			mc.player.jump();
		}
		while (mc.options.sneakKey.wasPressed()) {
			if (!blocked) level--;
		}
		if (blocked) return;
		if (maintainLevel.get() && mc.player.getBlockPos().getY() == level && mc.options.jumpKey.isPressed()) mc.player.jump();
	}
}
