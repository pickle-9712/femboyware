package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.util.math.Vec3d;

/** Swim faster, horizontally and vertically. */
public class WaterSpeedModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Horizontal swim speed in blocks per tick", 0.4, 0.05, 2, 0.01));
	private final NumberSetting verticalSpeed = add(new NumberSetting("Vertical Speed", "Jump and sneak swim speed", 0.2, 0, 1, 0.01));

	public WaterSpeedModule() {
		super("Water Speed", "Swim faster", Category.RAGE);
	}

	@Override
	public void onPreMotion() {
		if (!mc.player.isTouchingWater() || mc.player.hasVehicle() || mc.player.getAbilities().flying) return;
		Vec3d horizontal = MoveUtil.isMoving() ? MoveUtil.strafe(speed.get()) : mc.player.getVelocity().multiply(1, 0, 1);
		double y = MoveUtil.vertical(verticalSpeed.get());
		mc.player.setVelocity(horizontal.x, y == 0 ? mc.player.getVelocity().y : y, horizontal.z);
	}
}
