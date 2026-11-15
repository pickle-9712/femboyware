package dev.femboyware.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows the player's momentum (no input, no jumping) tick by tick: gravity, air drag and block
 * collisions. Good enough to tell where a knock or a walk off an edge takes you, which is all Clutch
 * needs; it doesn't model water, ladders, slime or effects.
 */
public final class FallSimulator {
	private static final Minecraft mc = Minecraft.getInstance();

	private FallSimulator() {
	}

	/**
	 * Where the coming ticks take the player, one position per tick, up to the tick it has fallen
	 * further than {@code distance}; null if it lands first or hasn't fallen that far in {@code ticks}.
	 */
	public static List<Vec3> fallPath(float distance, int ticks) {
		var p = mc.player;
		AABB box = p.getBoundingBox();
		Vec3 pos = p.position();
		Vec3 motion = p.getDeltaMovement();
		double fallen = Math.max(0, p.fallDistance);
		double gravity = p.getGravity();
		List<Vec3> positions = new ArrayList<>();
		for (int i = 0; i < ticks; i++) {
			Vec3 moved = collide(box, motion);
			box = box.move(moved);
			pos = pos.add(moved);
			positions.add(pos);
			if (moved.y < 0) fallen -= moved.y;
			if (fallen > distance) return positions;
			boolean landed = motion.y < 0 && moved.y > motion.y + 1e-7;
			if (landed) {
				// Only what's left of the momentum carries on; with none it's a landing
				if (motion.horizontalDistanceSqr() < 1e-4) return null;
				fallen = 0;
				motion = new Vec3(moved.x * 0.546, 0, moved.z * 0.546);
				continue;
			}
			double mx = moved.x != motion.x ? 0 : motion.x;
			double mz = moved.z != motion.z ? 0 : motion.z;
			motion = new Vec3(mx * 0.91, (motion.y - gravity) * 0.98, mz * 0.91);
		}
		return null;
	}

	/** {@code motion} cut short by blocks, one axis at a time (Y first, as vanilla does). */
	private static Vec3 collide(AABB box, Vec3 motion) {
		double y = clipAxis(box, 0, motion.y, 0);
		box = box.move(0, y, 0);
		double x = clipAxis(box, motion.x, 0, 0);
		box = box.move(x, 0, 0);
		double z = clipAxis(box, 0, 0, motion.z);
		return new Vec3(x, y, z);
	}

	private static double clipAxis(AABB box, double dx, double dy, double dz) {
		double length = Math.abs(dx + dy + dz);
		if (length < 1e-9) return 0;
		int steps = Math.max(1, (int) Math.ceil(length / 0.25));
		double done = 0;
		for (int i = 1; i <= steps; i++) {
			double f = (double) i / steps;
			if (!mc.level.noCollision(mc.player, box.move(dx * f, dy * f, dz * f))) break;
			done = f;
		}
		return (dx + dy + dz) * done;
	}
}
