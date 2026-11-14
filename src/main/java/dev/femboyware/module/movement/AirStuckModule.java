package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.world.phys.Vec3;

/** Hold still exactly where you are, in the air or anywhere, until you turn it off. */
public class AirStuckModule extends Module {
	private final BoolSetting allowLook = add(new BoolSetting("Allow Look", "You can still look round", true));

	private Vec3 at;
	private float yaw, pitch;

	public AirStuckModule() {
		super("AirStuck", "Hold your position", Category.BASIC);
	}

	@Override
	protected void onEnable() {
		if (mc.player == null) return;
		at = mc.player.position();
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
	}

	@Override
	protected void onDisable() {
		at = null;
	}

	@Override
	public void onTick() {
		if (mc.player == null) return;
		if (at == null) onEnable();
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.setPos(at.x, at.y, at.z);
		mc.player.fallDistance = 0;
		if (!allowLook.get()) {
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
		}
	}
}
