package dev.femboyware.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class BlockUtil {
	private static final Minecraft mc = Minecraft.getInstance();

	private BlockUtil() {
	}

	/** Whether a straight line from {@code from} to {@code to} is free of block collision shapes. */
	public static boolean canSee(Vec3 from, Vec3 to) {
		HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS;
	}

	/** Whether {@code face} of {@code pos} is visible from {@code eyes} and not covered by a full block. */
	public static boolean isFaceExposed(BlockPos pos, Direction face, Vec3 eyes) {
		Vec3 center = Vec3.atCenterOf(pos);
		Vec3 toEyes = eyes.subtract(center);
		boolean facing = switch (face.getAxis()) {
			case X -> toEyes.x * face.getStepX() > 0.5;
			case Y -> toEyes.y * face.getStepY() > 0.5;
			case Z -> toEyes.z * face.getStepZ() > 0.5;
		};
		return facing && !mc.level.getBlockState(pos.relative(face)).isSolidRender();
	}

	public static Vec3 faceCenter(BlockPos pos, Direction face) {
		return Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
	}

	/**
	 * Finds a solid neighbour of {@code pos} to click so a block lands at {@code pos}.
	 *
	 * @param strict only accept faces that are visible from the player's eyes
	 */
	public static BlockHitResult getPlacement(BlockPos pos, boolean strict) {
		Vec3 eyes = mc.player.getEyePosition();
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (mc.level.getBlockState(neighbour).canBeReplaced()) continue;
			Direction face = direction.getOpposite();
			if (strict && !isFaceExposed(neighbour, face, eyes)) continue;
			Vec3 hitVec = faceCenter(neighbour, face);
			double distance = eyes.distanceToSqr(hitVec);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = new BlockHitResult(hitVec, face, neighbour, false);
			}
		}
		return best;
	}

	/** Whether something that would block a block placement occupies {@code pos}. */
	public static boolean hasBlockingEntity(BlockPos pos, boolean ignoreCrystals) {
		for (Entity entity : mc.level.getEntities(null, new AABB(pos))) {
			if (entity instanceof ItemEntity || entity instanceof ExperienceOrb || !entity.isAlive()) continue;
			if (ignoreCrystals && entity instanceof EndCrystal) continue;
			return true;
		}
		return false;
	}

	public static Vec3 closestPoint(AABB box, Vec3 point) {
		return new Vec3(
			Math.max(box.minX, Math.min(box.maxX, point.x)),
			Math.max(box.minY, Math.min(box.maxY, point.y)),
			Math.max(box.minZ, Math.min(box.maxZ, point.z)));
	}
}
