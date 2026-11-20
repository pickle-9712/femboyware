package dev.femboyware.util;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Whether a block is in plain sight: on screen and reachable by a straight line from the camera without
 * anything else in the way. Block ESPs use it for "Hide When Visible", so a box and its tracer go away while
 * you can see the block yourself and come back once it is behind something. Results are kept for a short
 * while per block so a few hundred boxes don't each cast rays every frame.
 */
public final class BlockSight {
	private static final long KEEP_NANOS = 100_000_000L; // 0.1 s
	private static final double INSET = 0.02;
	/** pos -> (time << 1 | visible) */
	private static final Long2LongOpenHashMap CACHE = new Long2LongOpenHashMap();

	private BlockSight() {
	}

	/** Whether any face of the block at {@code pos} can be seen from the camera right now. */
	public static boolean visible(BlockPos pos) {
		return visible(pos, new AABB(pos));
	}

	/** Whether any part of {@code box} can be seen; {@code key} is the block the cache entry belongs to. */
	public static boolean visible(BlockPos key, AABB box) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return false;
		long now = System.nanoTime();
		long id = key.asLong();
		if (CACHE.containsKey(id)) {
			long entry = CACHE.get(id);
			if (now - (entry >> 1) < KEEP_NANOS) return (entry & 1) == 1;
		}
		if (CACHE.size() > 16384) CACHE.clear();
		boolean seen = compute(mc, box);
		CACHE.put(id, (now << 1) | (seen ? 1 : 0));
		return seen;
	}

	private static boolean compute(Minecraft mc, AABB box) {
		Vec3 eye = Mc.camera().position();
		if (box.contains(eye)) return true;
		Vec3 forward = Vec3.directionFromRotation(Mc.camera().xRot(), Mc.camera().yRot());
		Vec3 right = forward.cross(new Vec3(0, 1, 0));
		if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
		right = right.normalize();
		Vec3 up = right.cross(forward).normalize();
		double tanV = Math.tan(Math.toRadians(mc.options.fov().get()) / 2) * 1.05;
		double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
		double tanH = tanV * aspect;

		Vec3 c = box.getCenter();
		double hx = box.getXsize() / 2 - INSET, hy = box.getYsize() / 2 - INSET, hz = box.getZsize() / 2 - INSET;
		Vec3[] points = {
			c,
			c.add(hx, 0, 0), c.add(-hx, 0, 0),
			c.add(0, hy, 0), c.add(0, -hy, 0),
			c.add(0, 0, hz), c.add(0, 0, -hz),
		};
		for (Vec3 p : points) {
			Vec3 d = p.subtract(eye);
			double z = d.dot(forward);
			if (z <= 0.05) continue;
			if (Math.abs(d.dot(right)) / z > tanH || Math.abs(d.dot(up)) / z > tanV) continue;
			HitResult hit = mc.level.clip(new ClipContext(eye, p, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() == HitResult.Type.MISS) return true;
			if (hit instanceof BlockHitResult b && box.intersects(new AABB(b.getBlockPos()).deflate(0.01))) return true;
		}
		return false;
	}
}
