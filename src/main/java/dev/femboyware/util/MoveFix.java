package dev.femboyware.util;

import dev.femboyware.mixin.InputAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Remaps WASD so that, moving relative to the server yaw, the player still goes roughly where the
 * camera points. Inputs stay one of the 8 vanilla key combinations, because anti-cheats simulate
 * movement by trying exactly those.
 *
 * <p>The keys themselves are rewritten, not just the resulting speeds: since 1.21.2 the client
 * tells the server which keys are held ({@code PlayerInputC2SPacket}), and the server simulates
 * with those. Rewriting the keys also lets vanilla's own sprint and slowdown logic see the
 * direction we actually move in.
 */
public final class MoveFix {
	private MoveFix() {
	}

	/** Call right after {@code Input.tick()}, before anything reads the keys. */
	public static void fixInput(LocalPlayer player, float serverYaw) {
		Input keys = player.input.keyPresses;
		int forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
		int strafe = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		if (forward == 0 && strafe == 0) return;

		double[] wanted = direction(forward, strafe, player.getYRot());
		int bestForward = 0;
		int bestStrafe = 0;
		double bestDot = -2;
		for (int f = -1; f <= 1; f++) {
			for (int s = -1; s <= 1; s++) {
				if (f == 0 && s == 0) continue;
				double[] candidate = direction(f, s, serverYaw);
				double dot = candidate[0] * wanted[0] + candidate[1] * wanted[1];
				if (dot > bestDot) {
					bestDot = dot;
					bestForward = f;
					bestStrafe = s;
				}
			}
		}

		player.input.keyPresses = new Input(bestForward > 0, bestForward < 0, bestStrafe > 0, bestStrafe < 0,
			keys.jump(), keys.shift(), keys.sprint());
		((InputAccessor) player.input).femboyware$setMovementVector(new Vec2(bestStrafe, bestForward).normalized());
	}

	/** Normalised world-space XZ direction of a key combination, matching vanilla's input-to-velocity maths. */
	private static double[] direction(int forward, int strafe, float yaw) {
		double radians = Math.toRadians(yaw);
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		double x = strafe * cos - forward * sin;
		double z = forward * cos + strafe * sin;
		double length = Math.sqrt(x * x + z * z);
		return new double[]{x / length, z / length};
	}
}
