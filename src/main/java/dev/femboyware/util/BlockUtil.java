package dev.femboyware.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class BlockUtil {
	private static final MinecraftClient mc = MinecraftClient.getInstance();

	private BlockUtil() {
	}

	/** Whether a straight line from {@code from} to {@code to} is free of block collision shapes. */
	public static boolean canSee(Vec3d from, Vec3d to) {
		HitResult hit = mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS;
	}

	/** Whether {@code face} of {@code pos} is visible from {@code eyes} and not covered by a full block. */
	public static boolean isFaceExposed(BlockPos pos, Direction face, Vec3d eyes) {
		Vec3d center = Vec3d.ofCenter(pos);
		Vec3d toEyes = eyes.subtract(center);
		boolean facing = switch (face.getAxis()) {
			case X -> toEyes.x * face.getOffsetX() > 0.5;
			case Y -> toEyes.y * face.getOffsetY() > 0.5;
			case Z -> toEyes.z * face.getOffsetZ() > 0.5;
		};
		return facing && !mc.world.getBlockState(pos.offset(face)).isOpaqueFullCube();
	}

	public static Vec3d faceCenter(BlockPos pos, Direction face) {
		return Vec3d.ofCenter(pos).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
	}

	/**
	 * Finds a solid neighbour of {@code pos} to click so a block lands at {@code pos}.
	 *
	 * @param strict only accept faces that are visible from the player's eyes
	 */
	public static BlockHitResult getPlacement(BlockPos pos, boolean strict) {
		Vec3d eyes = mc.player.getEyePos();
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.offset(direction);
			if (mc.world.getBlockState(neighbour).isReplaceable()) continue;
			Direction face = direction.getOpposite();
			if (strict && !isFaceExposed(neighbour, face, eyes)) continue;
			Vec3d hitVec = faceCenter(neighbour, face);
			double distance = eyes.squaredDistanceTo(hitVec);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = new BlockHitResult(hitVec, face, neighbour, false);
			}
		}
		return best;
	}

	/** Whether something that would block a block placement occupies {@code pos}. */
	public static boolean hasBlockingEntity(BlockPos pos, boolean ignoreCrystals) {
		for (Entity entity : mc.world.getOtherEntities(null, new Box(pos))) {
			if (entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity || !entity.isAlive()) continue;
			if (ignoreCrystals && entity instanceof EndCrystalEntity) continue;
			return true;
		}
		return false;
	}

	public static Vec3d closestPoint(Box box, Vec3d point) {
		return new Vec3d(
			Math.max(box.minX, Math.min(box.maxX, point.x)),
			Math.max(box.minY, Math.min(box.maxY, point.y)),
			Math.max(box.minZ, Math.min(box.maxZ, point.z)));
	}
}
