package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

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
		if (!(vehicle instanceof AbstractBoat)) return;

		Vec3 horizontal = MoveUtil.strafe(speed.get());
		double y = -fall.get();
		if (mc.options.keyJump.isDown()) y = verticalSpeed.get();
		else if (mc.options.keySprint.isDown()) y = -verticalSpeed.get();
		vehicle.setYRot(mc.player.getYRot());
		vehicle.setDeltaMovement(horizontal.x, y, horizontal.z);
	}
}
