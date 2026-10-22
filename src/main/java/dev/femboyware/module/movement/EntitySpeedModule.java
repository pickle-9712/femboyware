package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.Vec3d;

/** Makes horses, pigs, striders and other rideable mobs as fast as you want. */
public class EntitySpeedModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Horizontal speed in blocks per tick", 0.8, 0.1, 5, 0.05));
	private final BoolSetting onlyOnGround = add(new BoolSetting("Only On Ground", "Only speed up while the mount is on the ground", false));

	public EntitySpeedModule() {
		super("Entity Speed", "Speeds up the mob you're riding", Category.RAGE);
	}

	@Override
	public void onTick() {
		Entity vehicle = mc.player.getVehicle();
		if (vehicle == null || vehicle instanceof AbstractBoatEntity || !MoveUtil.isMoving()) return;
		if (onlyOnGround.get() && !vehicle.isOnGround()) return;
		Vec3d horizontal = MoveUtil.strafe(speed.get());
		vehicle.setVelocity(horizontal.x, vehicle.getVelocity().y, horizontal.z);
	}
}
