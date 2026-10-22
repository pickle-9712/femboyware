package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Detaches the camera from your player so you can fly it around while your body stays put.
 * The camera, mouse and movement are redirected by the camera, entity and player mixins.
 */
public class FreeCamModule extends Module {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Camera speed in blocks per tick", 1, 0.1, 5, 0.1));

	private Vec3d pos = Vec3d.ZERO;
	private Vec3d lastPos = Vec3d.ZERO;
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
		pos = mc.gameRenderer.getCamera().getCameraPos();
		lastPos = pos;
		yaw = mc.player.getYaw();
		pitch = mc.player.getPitch();
		// Occlusion culling assumes the camera is at the player, which would hide chunks around the free camera
		mc.chunkCullingEnabled = false;
	}

	@Override
	protected void onDisable() {
		mc.chunkCullingEnabled = true;
	}

	@Override
	public void onMovementInput() {
		PlayerInput keys = mc.player.input.playerInput;
		double forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
		double strafe = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		double up = (keys.jump() ? 1 : 0) - (keys.sneak() ? 1 : 0);
		double radians = Math.toRadians(yaw);
		double x = strafe * Math.cos(radians) - forward * Math.sin(radians);
		double z = forward * Math.cos(radians) + strafe * Math.sin(radians);
		Vec3d move = new Vec3d(x, up, z);
		if (move.lengthSquared() > 0) move = move.normalize().multiply(keys.sprint() ? speed.get() * 2 : speed.get());
		lastPos = pos;
		pos = pos.add(move);

		// The body stands still
		mc.player.input.playerInput = PlayerInput.DEFAULT;
		mc.player.sidewaysSpeed = 0;
		mc.player.forwardSpeed = 0;
		mc.player.setJumping(false);
	}

	public void changeLook(double cursorDeltaX, double cursorDeltaY) {
		yaw += (float) cursorDeltaX * 0.15f;
		pitch = MathHelper.clamp(pitch + (float) cursorDeltaY * 0.15f, -90f, 90f);
	}

	public Vec3d getPos(float tickDelta) {
		return lastPos.lerp(pos, tickDelta);
	}

	public float getYaw() {
		return yaw;
	}

	public float getPitch() {
		return pitch;
	}
}
