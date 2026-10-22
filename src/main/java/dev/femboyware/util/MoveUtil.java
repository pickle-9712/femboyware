package dev.femboyware.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

public final class MoveUtil {
	private static final MinecraftClient mc = MinecraftClient.getInstance();

	private MoveUtil() {
	}

	public static boolean isMoving() {
		return mc.player.input.getMovementInput().lengthSquared() > 0;
	}

	/** Horizontal velocity of length {@code speed} pointing where the movement keys point, or zero. */
	public static Vec3d strafe(double speed) {
		Vec2f input = mc.player.input.getMovementInput();
		if (input.lengthSquared() == 0) return Vec3d.ZERO;
		double radians = Math.toRadians(mc.player.getYaw());
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		// Same input-to-world mapping as vanilla's movementInputToVelocity: x is sideways (left positive), y forward
		double x = input.x * cos - input.y * sin;
		double z = input.y * cos + input.x * sin;
		double length = Math.sqrt(x * x + z * z);
		return new Vec3d(x / length * speed, 0, z / length * speed);
	}

	/** Vertical speed from the jump and sneak keys: up, down or zero. */
	public static double vertical(double speed) {
		double y = 0;
		if (mc.options.jumpKey.isPressed()) y += speed;
		if (mc.options.sneakKey.isPressed()) y -= speed;
		return y;
	}

	public static double horizontalSpeed() {
		return mc.player.getVelocity().horizontalLength();
	}
}
