package dev.femboyware.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class MoveUtil {
	private static final Minecraft mc = Minecraft.getInstance();

	private MoveUtil() {
	}

	public static boolean isMoving() {
		return mc.player.input.getMoveVector().lengthSquared() > 0;
	}

	/** Horizontal velocity of length {@code speed} pointing where the movement keys point, or zero. */
	public static Vec3 strafe(double speed) {
		Vec2 input = mc.player.input.getMoveVector();
		if (input.lengthSquared() == 0) return Vec3.ZERO;
		double radians = Math.toRadians(mc.player.getYRot());
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		// Same input-to-world mapping as vanilla's movementInputToVelocity: x is sideways (left positive), y forward
		double x = input.x * cos - input.y * sin;
		double z = input.y * cos + input.x * sin;
		double length = Math.sqrt(x * x + z * z);
		return new Vec3(x / length * speed, 0, z / length * speed);
	}

	/** Vertical speed from the jump and sneak keys: up, down or zero. */
	public static double vertical(double speed) {
		double y = 0;
		if (mc.options.keyJump.isDown()) y += speed;
		if (mc.options.keyShift.isDown()) y -= speed;
		return y;
	}

	public static double horizontalSpeed() {
		return mc.player.getDeltaMovement().horizontalDistance();
	}
}
