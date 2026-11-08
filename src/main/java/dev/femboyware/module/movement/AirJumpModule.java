package dev.femboyware.module.movement;

import dev.femboyware.util.Mc;
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
		level = mc.player.blockPosition().getY();
		// Drop presses made while we were off
		while (mc.options.keyJump.consumeClick()) ;
		while (mc.options.keyShift.consumeClick()) ;
	}

	@Override
	public void onTick() {
		boolean blocked = FemboyWare.isEnabled(FreeCamModule.class) || Mc.screen() != null || mc.player.onGround();
		while (mc.options.keyJump.consumeClick()) {
			if (blocked) continue;
			level = mc.player.blockPosition().getY();
			mc.player.jumpFromGround();
		}
		while (mc.options.keyShift.consumeClick()) {
			if (!blocked) level--;
		}
		if (blocked) return;
		if (maintainLevel.get() && mc.player.blockPosition().getY() == level && mc.options.keyJump.isDown()) mc.player.jumpFromGround();
	}
}
