package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.Vec3d;

/** Flies the boat you're sitting in. Jump goes up, sprint goes down. */
public class BoatFlyModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Horizontal speed in blocks per tick", 1, 0.1, 5, 0.05));
	private final NumberSetting verticalSpeed = add(new NumberSetting("Vertical Speed", "Up and down speed in blocks per tick", 0.4, 0, 2, 0.05));
	private final NumberSetting fall = add(new NumberSetting("Fall", "Constant sink speed while idle, to avoid fly kicks", 0.02, 0, 0.2, 0.01));

	public BoatFlyModule() {
		super("Boat Fly", "Fly with boats", Category.RAGE);
	}

	@Override
	public void onTick() {
		Entity vehicle = mc.player.getVehicle();
		if (!(vehicle instanceof AbstractBoatEntity)) return;

		Vec3d horizontal = MoveUtil.strafe(speed.get());
		double y = -fall.get();
		if (mc.options.jumpKey.isPressed()) y = verticalSpeed.get();
		else if (mc.options.sprintKey.isPressed()) y = -verticalSpeed.get();
		vehicle.setYaw(mc.player.getYaw());
		vehicle.setVelocity(horizontal.x, y, horizontal.z);
	}
}
