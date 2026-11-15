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
 * Modified for femboyware: ported from Kotlin (ScaffoldMovementPlanner) to Java, debug drawing removed.
 */
package dev.femboyware.lb;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Estimates the line you're trying to bridge along: one of the 8 compass directions, through the
 * blocks you just placed or, when you've turned, through your spot on the block you stand on.
 */
public final class ScaffoldMovementPlanner {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final int MAX_LAST_PLACE_BLOCKS = 4;
	private static final float DIRECTION_HYSTERESIS_DEGREES = 30f;
	private static final double SUPPORT_SURFACE_EPSILON = 1.0E-3;
	private static final double SUPPORT_OVERLAP_HYSTERESIS = 0.02;

	private final ArrayDeque<BlockPos> lastPlacedBlocks = new ArrayDeque<>(MAX_LAST_PLACE_BLOCKS);
	private BlockPos lastPosition;
	private float lastDirectionAngle = Float.NaN;

	private record Support(BlockPos pos, double overlapArea, double surfaceDelta, double distanceSqr) implements Comparable<Support> {
		@Override
		public int compareTo(Support other) {
			if (surfaceDelta + SUPPORT_SURFACE_EPSILON < other.surfaceDelta) return -1;
			if (other.surfaceDelta + SUPPORT_SURFACE_EPSILON < surfaceDelta) return 1;
			if (overlapArea > other.overlapArea + SUPPORT_OVERLAP_HYSTERESIS) return -1;
			if (overlapArea + SUPPORT_OVERLAP_HYSTERESIS < other.overlapArea) return 1;
			return Double.compare(distanceSqr, other.distanceSqr);
		}

		boolean stableComparedTo(Support best) {
			return surfaceDelta <= best.surfaceDelta + SUPPORT_SURFACE_EPSILON && overlapArea + SUPPORT_OVERLAP_HYSTERESIS >= best.overlapArea;
		}
	}

	/** The line to follow when moving towards {@code movementYaw} (degrees), or null with nothing under you. */
	public Geometry.Line optimalLine(float movementYaw) {
		Vec3 direction = chooseDirection(movementYaw);
		Support support = supportUnderPlayer();
		if (support == null) return null;
		Vec3 position = mc.player.position();
		Geometry.Line lastBlocks = lineThroughLastPlaced();
		Vec3 anchor;
		if (lastBlocks != null && lastBlocks.direction().dot(direction) >= 0.5) {
			anchor = lastBlocks.nearestPointTo(position);
		} else {
			anchor = new Vec3(position.x, position.y, position.z);
		}
		return Geometry.Line.of(new Vec3(anchor.x, position.y, anchor.z), direction);
	}

	private Geometry.Line lineThroughLastPlaced() {
		if (lastPlacedBlocks.size() < 2) return null;
		BlockPos[] blocks = lastPlacedBlocks.toArray(new BlockPos[0]);
		Vec3 a = Vec3.atBottomCenterOf(blocks[blocks.length - 2]), b = Vec3.atBottomCenterOf(blocks[blocks.length - 1]);
		Vec3 dir = b.subtract(a);
		if (dir.lengthSqr() < 1e-9) return null;
		return Geometry.Line.of(a.add(b).scale(0.5), dir.normalize());
	}

	private Support supportUnderPlayer() {
		AABB box = mc.player.getBoundingBox();
		int supportY = Mth.floor(box.minY) - 1;
		Map<BlockPos, Support> candidates = new HashMap<>();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
				BlockPos pos = new BlockPos(x, supportY, z);
				VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
				if (!shape.isEmpty()) candidates.put(pos, support(pos, shape, box));
			}
		}
		if (candidates.isEmpty()) {
			lastPosition = null;
			return null;
		}
		Support best = null;
		for (Support s : candidates.values()) if (best == null || s.compareTo(best) < 0) best = s;
		Support chosen = best;
		Support lastPlaced = lastPlacedBlocks.isEmpty() ? null : candidates.get(lastPlacedBlocks.peekLast());
		Support last = lastPosition == null ? null : candidates.get(lastPosition);
		if (lastPlaced != null && lastPlaced.stableComparedTo(best)) chosen = lastPlaced;
		else if (last != null && last.stableComparedTo(best)) chosen = last;
		lastPosition = chosen.pos();
		return chosen;
	}

	private Support support(BlockPos pos, VoxelShape shape, AABB player) {
		double bestDelta = Double.POSITIVE_INFINITY, overlap = 0;
		for (AABB local : shape.toAabbs()) {
			AABB b = local.move(pos);
			double ox = Math.min(player.maxX, b.maxX) - Math.max(player.minX, b.minX);
			double oz = Math.min(player.maxZ, b.maxZ) - Math.max(player.minZ, b.minZ);
			if (ox <= 0 || oz <= 0) continue;
			double delta = Math.abs(player.minY - b.maxY);
			if (delta + SUPPORT_SURFACE_EPSILON < bestDelta) {
				bestDelta = delta;
				overlap = ox * oz;
			} else if (Math.abs(delta - bestDelta) <= SUPPORT_SURFACE_EPSILON) {
				overlap += ox * oz;
			}
		}
		Vec3 p = mc.player.position();
		double dx = p.x - (pos.getX() + 0.5), dz = p.z - (pos.getZ() + 0.5);
		return new Support(pos, overlap, bestDelta, dx * dx + dz * dz);
	}

	/** Snaps an angle to one of the 8 compass directions, sticking with the last one near a boundary. */
	private Vec3 chooseDirection(float angle) {
		if (!Float.isNaN(lastDirectionAngle) && Mth.degreesDifferenceAbs(angle, lastDirectionAngle) <= DIRECTION_HYSTERESIS_DEGREES) {
			return Vec3.directionFromRotation(0f, lastDirectionAngle);
		}
		float number = Math.round(Mth.wrapDegrees(angle) / 180f * 4 + 4);
		lastDirectionAngle = Mth.wrapDegrees((number - 4) / 4f * 180f);
		return Vec3.directionFromRotation(0f, lastDirectionAngle);
	}

	public void trackPlacedBlock(BlockPos pos) {
		if (pos.equals(lastPlacedBlocks.peekLast())) return;
		while (lastPlacedBlocks.size() >= MAX_LAST_PLACE_BLOCKS) lastPlacedBlocks.removeFirst();
		lastPlacedBlocks.addLast(pos.immutable());
	}

	public void reset() {
		lastPosition = null;
		lastDirectionAngle = Float.NaN;
		lastPlacedBlocks.clear();
	}
}
