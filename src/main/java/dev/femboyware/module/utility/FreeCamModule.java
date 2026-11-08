package dev.femboyware.module.utility;

import dev.femboyware.util.Mc;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Detaches the camera from your player so you can fly it around while your body stays put.
 * The camera, mouse and movement are redirected by the camera, entity and player mixins.
 */
public class FreeCamModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Camera speed in blocks per tick", 1, 0.1, 5, 0.1));

	private Vec3 pos = Vec3.ZERO;
	private Vec3 lastPos = Vec3.ZERO;
	private float yaw;
	private float pitch;

	public FreeCamModule() {
		super("Free Cam", "Fly the camera away from your body", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) {
			setEnabled(false);
			return;
		}
		pos = Mc.camera().position();
		lastPos = pos;
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
		// Occlusion culling assumes the camera is at the player, which would hide chunks around the free camera
		mc.smartCull = false;
	}

	@Override
	protected void onDisable() {
		mc.smartCull = true;
	}

	@Override
	public void onMovementInput() {
		Input keys = mc.player.input.keyPresses;
		double forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
		double strafe = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		double up = (keys.jump() ? 1 : 0) - (keys.shift() ? 1 : 0);
		double radians = Math.toRadians(yaw);
		double x = strafe * Math.cos(radians) - forward * Math.sin(radians);
		double z = forward * Math.cos(radians) + strafe * Math.sin(radians);
		Vec3 move = new Vec3(x, up, z);
		if (move.lengthSqr() > 0) move = move.normalize().scale(keys.sprint() ? speed.get() * 2 : speed.get());
		lastPos = pos;
		pos = pos.add(move);

		// The body stands still
		mc.player.input.keyPresses = Input.EMPTY;
		mc.player.xxa = 0;
		mc.player.zza = 0;
		mc.player.setJumping(false);
	}

	public void changeLook(double cursorDeltaX, double cursorDeltaY) {
		yaw += (float) cursorDeltaX * 0.15f;
		pitch = Mth.clamp(pitch + (float) cursorDeltaY * 0.15f, -90f, 90f);
	}

	public Vec3 getPos(float tickDelta) {
		return lastPos.lerp(pos, tickDelta);
	}

	public float getYaw() {
		return yaw;
	}

	public float getPitch() {
		return pitch;
	}
}
