package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.world.phys.Vec3;

/** Swim faster, horizontally and vertically. */
public class WaterSpeedModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Horizontal swim speed in blocks per tick", 0.4, 0.05, 2, 0.01));
	private final NumberSetting verticalSpeed = add(new NumberSetting("Vertical Speed", "Jump and sneak swim speed", 0.2, 0, 1, 0.01));

	public WaterSpeedModule() {
		super("Water Speed", "Swim faster", Category.RAGE);
	}

	@Override
	public void onPreMotion() {
		if (!mc.player.isInWater() || mc.player.isPassenger() || mc.player.getAbilities().flying) return;
		Vec3 horizontal = MoveUtil.isMoving() ? MoveUtil.strafe(speed.get()) : mc.player.getDeltaMovement().multiply(1, 0, 1);
		double y = MoveUtil.vertical(verticalSpeed.get());
		mc.player.setDeltaMovement(horizontal.x, y == 0 ? mc.player.getDeltaMovement().y : y, horizontal.z);
	}
}
