/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified for femboyware: ported from Kotlin (utils/block/targetfinding: TargetFinding,
 * FaceTargetPositionFactory, BlockPosOffsets, PlacementVerification) to Java, debug drawing
 * removed, and wired to femboyware's rotation type.
 */
package dev.femboyware.lb;

import dev.femboyware.FemboyWare;
import dev.femboyware.util.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Finds where and how to click to place a block at a position, as LiquidBounce's scaffold does. */
public final class TargetFinding {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final double FACE_HIT_EPSILON = 1.0E-7;

	private TargetFinding() {
	}

	// ---------------------------------------------------------------- options

	/** Which point on a face to aim at. */
	public enum AimMode {
		CENTER, RANDOM, STABILIZED, NEAREST_ROTATION, REVERSE_YAW, DIAGONAL_YAW, ANGLE_YAW, EDGE_POINT
	}

	/**
	 * The player's state on placement: the aim is derived from this one state, which may be a
	 * prediction of where the player will be.
	 */
	public record PlayerLocation(Vec3 position, Pose pose, Rotation rotation, boolean movingHorizontally) {
		public static PlayerLocation of(Vec3 position, Pose pose) {
			var p = mc.player;
			var keys = p.input.keyPresses;
			return new PlayerLocation(position, pose, FemboyWare.getRotations().getServerRotation(),
				keys.forward() || keys.backward() || keys.left() || keys.right());
		}

		public Vec3 eyePos() {
			return position.add(0, mc.player.getEyeHeight(pose), 0);
		}
	}

	public record Options(List<? extends Vec3i> offsets, Comparator<BlockPos> priority, FacePositionFactory facePositionFactory,
						  boolean considerFacingAwayFaces, ItemStack stack, PlayerLocation location, double range) {
		public Options(List<? extends Vec3i> offsets, Comparator<BlockPos> priority, FacePositionFactory facePositionFactory,
					   boolean considerFacingAwayFaces, ItemStack stack, PlayerLocation location) {
			this(offsets, priority, facePositionFactory, considerFacingAwayFaces, stack, location, Double.MAX_VALUE);
		}
	}

	private static VoxelShape outline(BlockPos pos) {
		return mc.level.getBlockState(pos).getShape(mc.level, pos).move(pos.getX(), pos.getY(), pos.getZ());
	}

	/** Prefers positions whose block is closest to {@code line} (higher ranks first). */
	public static Comparator<BlockPos> leastBlockDistanceToLine(Geometry.Line line) {
		return Comparator.comparingDouble(pos -> {
			VoxelShape shape = outline(pos);
			if (shape.isEmpty()) return -line.distanceToSqr(Vec3.atCenterOf(pos));
			Geometry.NearestPoint nearest = line.nearestPointTo(shape);
			return -(nearest == null ? Double.POSITIVE_INFINITY : nearest.distanceSquared());
		});
	}

	/** Prefers positions whose block is closest to {@code point} (higher ranks first). */
	public static Comparator<BlockPos> leastBlockDistanceToPos(Vec3 point) {
		return Comparator.comparingDouble(pos -> {
			VoxelShape shape = outline(pos);
			if (shape.isEmpty()) return -pos.distToCenterSqr(point);
			double best = Double.POSITIVE_INFINITY;
			for (AABB box : shape.toAabbs()) best = Math.min(best, box.distanceToSqr(point));
			return -best;
		});
	}

	// ---------------------------------------------------------------- offsets

	public static final List<BlockPos> NO_OFFSET = List.of(BlockPos.ZERO);
	public static final List<BlockPos> NORMAL = scaffoldOffsets(0, -1, 1);
	public static final List<BlockPos> DOWN = scaffoldOffsets(0, -1, 1, -2, 2);
	public static final List<BlockPos> FULL = scaffoldOffsets(0, -1, 1, -2, 2, -3, 3, -4, 4);

	private static List<BlockPos> scaffoldOffsets(int... xz) {
		Set<BlockPos> set = new LinkedHashSet<>();
		for (int x : xz) {
			for (int z : xz) {
				set.add(new BlockPos(x, 0, z));
				set.add(new BlockPos(x, -1, z));
			}
		}
		List<BlockPos> list = new ArrayList<>(set);
		list.sort(Comparator.comparingLong((BlockPos p) -> (long) p.getX() * p.getX() + (long) p.getY() * p.getY() + (long) p.getZ() * p.getZ())
			.thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
		return List.copyOf(list);
	}

	// ---------------------------------------------------------------- the result

	/**
	 * A planned click: right-click {@code interactedBlockPos} on {@code direction} at
	 * {@code interactionPoint}, which places a block at {@code placedBlock}.
	 */
	public record PlacementTarget(BlockPos interactedBlockPos, BlockPos placedBlock, Direction direction, Vec3 interactionPoint,
								  double minPlacementY, Rotation rotation) {
		public BlockHitResult blockHitResult() {
			return new BlockHitResult(interactionPoint, direction, interactedBlockPos, false);
		}

		public boolean matches(BlockHitResult hit) {
			if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
			if (!hit.getBlockPos().equals(interactedBlockPos) || hit.getDirection() != direction) return false;
			return hit.getLocation().y >= minPlacementY - FACE_HIT_EPSILON;
		}

		/** The hit to place with when looking along {@code rotation} from your eyes now, or the planned hit / null if that misses. */
		public BlockHitResult verifyClick(Rotation rotation, boolean plannedOnFailure) {
			BlockHitResult hit = traceFromPlayer(rotation, Math.max(mc.player.blockInteractionRange(), mc.player.entityInteractionRange()));
			if (matches(hit)) return hit;
			return plannedOnFailure ? blockHitResult() : null;
		}
	}

	/** What you'd click looking along {@code rotation} from your eyes. */
	public static BlockHitResult traceFromPlayer(Rotation rotation, double range) {
		Vec3 eye = mc.player.getEyePosition();
		return traceFromPoint(eye, rotation.toVector(), range);
	}

	public static BlockHitResult traceFromPoint(Vec3 start, Vec3 direction, double range) {
		return mc.level.clip(new ClipContext(start, start.add(direction.scale(range)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
	}

	// ---------------------------------------------------------------- finding

	private record TargetPlan(BlockPos clickedBlockPos, Direction direction, Geometry.Face face, Vec3 point) {
		Vec3 interactionPoint() {
			return point.add(clickedBlockPos.getX(), clickedBlockPos.getY(), clickedBlockPos.getZ());
		}
	}

	private static boolean holdsSolidBlock(BlockPos pos) {
		return mc.level.getBlockState(pos).isFaceSturdy(mc.level, pos, Direction.UP, SupportType.CENTER);
	}

	/**
	 * The best way to place the options' stack at {@code pos}, or failing that at one of the
	 * offsets (in priority order); null if none of them can take it.
	 */
	/** femboyware: faces not to click (block clicked, face), e.g. ones the server just took a block back from; null = none. */
	public static java.util.function.BiPredicate<BlockPos, Direction> avoid;

	public static PlacementTarget findBest(BlockPos pos, Options options) {
		if (holdsSolidBlock(pos)) return null;
		List<Vec3i> offsets = new ArrayList<>(options.offsets());
		// Descending priority
		offsets.sort((a, b) -> options.priority().compare(pos.offset(b), pos.offset(a)));
		for (Vec3i offset : offsets) {
			BlockPos investigate = pos.offset(offset);
			TargetPlan plan = bestPlan(investigate, options);
			if (plan == null) continue;
			Vec3 point = plan.interactionPoint();
			return new PlacementTarget(plan.clickedBlockPos(), investigate, plan.direction(), point,
				plan.face().from.y + plan.clickedBlockPos().getY(), Rotation.between(options.location().eyePos(), point));
		}
		return null;
	}

	private static TargetPlan bestPlan(BlockPos investigate, Options options) {
		Vec3 eye = options.location().eyePos();
		TargetPlan best = null;
		double bestFacing = Double.NEGATIVE_INFINITY;
		CollisionContext context = CollisionContext.of(mc.player);
		VoxelShape targetShape = mc.level.getBlockState(investigate).getShape(mc.level, investigate, context);
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = investigate.relative(direction.getOpposite());
			VoxelShape neighbourShape = mc.level.getBlockState(neighbour).getShape(mc.level, neighbour, context);
			for (int i = 0; i < 2; i++) {
				BlockPos clicked = i == 0 ? neighbour : investigate;
				VoxelShape shape = i == 0 ? neighbourShape : targetShape;
				if (shape.isEmpty()) continue;
				if (avoid != null && avoid.test(clicked, direction)) continue;
				TargetPlan plan = plan(clicked, direction, investigate, shape, options);
				if (plan == null) continue;
				Vec3 toEye = eye.subtract(plan.interactionPoint());
				double distance = toEye.length();
				Vec3 normal = new Vec3(plan.direction().getStepX(), plan.direction().getStepY(), plan.direction().getStepZ());
				double facing = toEye.dot(normal) / distance;
				if (distance > options.range() || !options.considerFacingAwayFaces() && facing < 0.0) continue;
				if (facing > bestFacing) {
					best = plan;
					bestFacing = facing;
				}
			}
		}
		return best;
	}

	private static TargetPlan plan(BlockPos clicked, Direction direction, BlockPos investigate, VoxelShape shape, Options options) {
		Geometry.Face face = null;
		Vec3 point = null;
		Vec3 normal = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
		Vec3 eyeLocal = options.location().eyePos().subtract(clicked.getX(), clicked.getY(), clicked.getZ());
		double eyeNormal = eyeLocal.dot(normal);
		for (AABB box : shape.toAabbs()) {
			Geometry.Face candidate = Geometry.Face.get(direction, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
			if (!options.considerFacingAwayFaces() && eyeNormal - candidate.from.dot(normal) < 0.0) continue;
			Vec3 p = options.facePositionFactory().produce(candidate, clicked, options.location());
			if (p == null) continue;
			if (point == null || compareOnFace(direction, p, point) > 0) {
				face = candidate;
				point = p;
			}
		}
		if (point == null) return null;
		TargetPlan plan = new TargetPlan(clicked, direction, face, point);
		// Let vanilla decide where the block ends up
		boolean inside = shape.bounds().contains(eyeLocal);
		BlockPlaceContext context = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, options.stack(),
			new BlockHitResult(plan.interactionPoint(), direction, clicked, inside));
		return resolvesTo(context, investigate) ? plan : null;
	}

	private static int compareOnFace(Direction direction, Vec3 a, Vec3 b) {
		Vec3 n = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
		Vec3 da = a.subtract(0.5, 0.5, 0.5).multiply(n), db = b.subtract(0.5, 0.5, 0.5).multiply(n);
		int c = Double.compare(da.lengthSqr(), db.lengthSqr());
		return c != 0 ? c : Double.compare(a.y, b.y);
	}

	/** Whether clicking with this context puts the stack at {@code pos}. */
	private static boolean resolvesTo(BlockPlaceContext context, BlockPos pos) {
		if (!(context.getItemInHand().getItem() instanceof BlockItem item)) return context.getClickedPos().equals(pos) && context.canPlace();
		if (!item.getBlock().isEnabled(context.getLevel().enabledFeatures())) return false;
		BlockPlaceContext resolved = item.updatePlacementContext(context);
		return resolved != null && resolved.getClickedPos().equals(pos) && resolved.canPlace();
	}

	// ---------------------------------------------------------------- where on a face

	/** Picks a point (relative to the clicked block) on a face to aim at. */
	public interface FacePositionFactory {
		Vec3 produce(Geometry.Face face, BlockPos targetPos, PlayerLocation location);
	}

	public static FacePositionFactory factory(AimMode mode, Geometry.Line optimalLine) {
		return switch (mode) {
			case CENTER -> (face, pos, location) -> face.center();
			case RANDOM -> (face, pos, location) -> trim(face, 0.15).randomPoint();
			case STABILIZED -> stabilized(optimalLine);
			case NEAREST_ROTATION -> (face, pos, location) -> nearestToRotationLine(pos, trim(face, 0.15), location);
			case REVERSE_YAW -> yawFactory(180f);
			case DIAGONAL_YAW -> yawFactory(75f);
			case ANGLE_YAW -> yawFactory(45f);
			case EDGE_POINT -> edgePoint();
		};
	}

	/** Trims a face by {@code scale} of its size on each side. */
	public static Geometry.Face trim(Geometry.Face face, double scale) {
		Vec3 o = face.dimensions.scale(scale);
		Vec3 c = face.center();
		double lowX = face.from.x + o.x, highX = face.to.x - o.x;
		double lowY = face.from.y + o.y, highY = face.to.y - o.y;
		double lowZ = face.from.z + o.z, highZ = face.to.z - o.z;
		return new Geometry.Face(
			new Vec3(lowX > highX ? c.x : lowX, lowY > highY ? c.y : lowY, lowZ > highZ ? c.z : lowZ),
			new Vec3(lowX > highX ? c.x : highX, lowY > highY ? c.y : highY, lowZ > highZ ? c.z : highZ));
	}

	public static Vec3 nearestToRotationLine(BlockPos targetPos, Geometry.Face face, PlayerLocation location) {
		if (Mth.equal(face.area(), 0.0)) return face.from;
		Geometry.Line rotationLine = Geometry.Line.of(location.eyePos().subtract(Vec3.atLowerCornerOf(targetPos)), location.rotation().toVector());
		return face.nearestPointTo(rotationLine);
	}

	private static FacePositionFactory stabilized(Geometry.Line optimalLine) {
		return (face, targetPos, location) -> {
			Vec3 corner = Vec3.atLowerCornerOf(targetPos);
			Geometry.Face trimmed = trim(face, 0.15).offset(corner);
			Geometry.Face target = trimmed;
			if (optimalLine != null) {
				Vec3 player = location.position();
				Vec3 nearest = optimalLine.nearestPointTo(player);
				Vec3 toLine = player.subtract(nearest).normalize();
				Vec3 hit = null;
				try {
					hit = trimmed.toPlane().intersection(Geometry.Line.of(location.eyePos(), optimalLine.direction()));
				} catch (IllegalArgumentException ignored) {
				}
				if (hit != null) {
					Vec3 b = player.add(toLine.scale(2.0));
					AABB crop = new AABB(hit.x, player.y - 2.0, hit.z, b.x, player.y + 1.0, b.z);
					Geometry.Face clamped = trimmed.clamp(crop);
					if (clamped.area() >= 0.0001) target = clamped;
				}
			}
			return nearestToRotationLine(targetPos, target.offset(corner.scale(-1)), location);
		};
	}

	private static float yawOf(Vec3 v) {
		return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(v.z, v.x)) - 90f);
	}

	private static FacePositionFactory yawFactory(float angle) {
		return (face, targetPos, location) -> {
			Geometry.Face trimmed = trim(face, 0.15);
			if (!location.movingHorizontally()) return nearestToRotationLine(targetPos, trimmed, location);
			Vec3 yawPoint = nearestToYaw(targetPos, trimmed, location, angle);
			return yawPoint != null ? yawPoint : nearestToRotationLine(targetPos, trimmed, location);
		};
	}

	private static Vec3 nearestToYaw(BlockPos targetPos, Geometry.Face face, PlayerLocation location, float angle) {
		if (Mth.equal(face.area(), 0.0)) return face.from;
		Vec3 localEye = location.eyePos().subtract(Vec3.atLowerCornerOf(targetPos));
		float yaw = Mth.wrapDegrees(location.rotation().yaw());
		float high = Mth.wrapDegrees(yaw + angle), low = Mth.wrapDegrees(yaw - angle);
		Vec3 highPoint = null, lowPoint = null;
		float highTolerance = Float.MAX_VALUE, lowTolerance = Float.MAX_VALUE;
		try {
			Geometry.Plane facePlane = face.toPlane();
			Geometry.Line highLine = facePlane.intersection(Geometry.Plane.fromParams(localEye, new Vec3(0, 0, 1).yRot((float) Math.toRadians(high)), new Vec3(0, 1, 0)));
			Geometry.Line lowLine = facePlane.intersection(Geometry.Plane.fromParams(localEye, new Vec3(0, 0, 1).yRot((float) Math.toRadians(low)), new Vec3(0, 1, 0)));
			Geometry.Line highSegment = highLine == null ? null : face.coerceInFace(highLine);
			Geometry.Line lowSegment = lowLine == null ? null : face.coerceInFace(lowLine);
			if (highSegment == null && lowSegment == null) return null;
			if (highSegment != null) {
				highPoint = closestToYaw(highSegment, high, localEye);
				highTolerance = Math.abs(Mth.wrapDegrees(yawOf(highPoint.subtract(localEye)) - high));
			}
			if (lowSegment != null) {
				lowPoint = closestToYaw(lowSegment, low, localEye);
				lowTolerance = Math.abs(Mth.wrapDegrees(yawOf(lowPoint.subtract(localEye)) - low));
			}
		} catch (RuntimeException e) {
			return null;
		}
		float tolerance = 5f;
		if (highTolerance <= tolerance && lowTolerance <= tolerance) return highTolerance < lowTolerance ? highPoint : lowPoint;
		if (highTolerance <= tolerance) return highPoint;
		if (lowTolerance <= tolerance) return lowPoint;
		return null;
	}

	private static Vec3 closestToYaw(Geometry.Line segment, float targetYaw, Vec3 eye) {
		Vec3 start = segment.start(), end = segment.end();
		float startYaw = yawOf(start.subtract(eye)), endYaw = yawOf(end.subtract(eye));
		float yawDiff = Mth.wrapDegrees(endYaw - startYaw);
		float targetDiff = Mth.wrapDegrees(targetYaw - startYaw);
		float t = yawDiff != 0f ? targetDiff / yawDiff : 0f;
		return start.add(end.subtract(start).scale(Mth.clamp(t, 0f, 1f)));
	}

	private static FacePositionFactory edgePoint() {
		return (face, targetPos, location) -> {
			Geometry.Face trimmed = trim(face, 0.15);
			if (!location.movingHorizontally()) return nearestToRotationLine(targetPos, trimmed, location);
			Vec3 relative = location.position().subtract(Vec3.atLowerCornerOf(targetPos));
			Vec3 best = null;
			for (Vec3 vertex : trimmed.vertices()) {
				if (best == null || vertex.distanceToSqr(relative) > best.distanceToSqr(relative)) best = vertex;
			}
			return best != null ? best : nearestToRotationLine(targetPos, trimmed, location);
		};
	}
}
