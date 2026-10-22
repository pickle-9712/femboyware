package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;

/** Climb walls by walking into them. */
public class SpiderModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Climb speed in blocks per tick", 0.2, 0.05, 1, 0.01));

	public SpiderModule() {
		super("Spider", "Climb walls like a spider", Category.RAGE);
	}

	@Override
	public void onTick() {
		if (!mc.player.horizontalCollision || !MoveUtil.isMoving()) return;
		mc.player.setVelocity(mc.player.getVelocity().x, speed.get(), mc.player.getVelocity().z);
	}
}
