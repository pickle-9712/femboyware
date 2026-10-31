package dev.femboyware.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Finds a way to walk between two blocks with A*: flat ground, diagonals that don't clip a corner,
 * one-block steps up and drops of up to three, through loaded chunks only, keeping off things that hurt.
 * It doesn't break or place blocks, climb ladders or jump gaps. If the goal can't be reached, or is
 * too far to search, it returns the way to the closest place it found.
 */
public final class Pathfinder {
	private static final int MOST_DROP = 3;
	private static final double DIAGONAL = 1.414;
	private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private record Node(BlockPos pos, double cost, double total) {}

	private Pathfinder() {
	}

	/** Places to walk through in order, each at the middle of a block at foot height; null if there's nowhere better to go. */
	public static List<Vec3d> find(World world, BlockPos start, BlockPos goal, int mostNodes) {
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.total, b.total));
		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		Map<BlockPos, Double> costs = new HashMap<>();
		open.add(new Node(start, 0, guess(start, goal)));
		costs.put(start, 0.0);
		BlockPos closest = start;
		double closestGuess = guess(start, goal);
		int visited = 0;
		while (!open.isEmpty() && visited < mostNodes) {
			Node node = open.poll();
			if (node.cost > costs.get(node.pos)) continue;
			visited++;
			double left = guess(node.pos, goal);
			if (left < closestGuess) {
				closestGuess = left;
				closest = node.pos;
			}
			if (Math.abs(node.pos.getX() - goal.getX()) <= 1 && Math.abs(node.pos.getZ() - goal.getZ()) <= 1 && Math.abs(node.pos.getY() - goal.getY()) <= 2) {
				closest = node.pos;
				break;
			}
			for (int[] direction : DIRECTIONS) {
				BlockPos next = step(world, node.pos, direction[0], direction[1]);
				if (next == null) continue;
				double move = (direction[0] != 0 && direction[1] != 0 ? DIAGONAL : 1) + Math.abs(next.getY() - node.pos.getY()) * 0.5;
				double cost = node.cost + move;
				Double known = costs.get(next);
				if (known != null && known <= cost) continue;
				costs.put(next, cost);
				cameFrom.put(next, node.pos);
				open.add(new Node(next, cost, cost + guess(next, goal)));
			}
		}
		if (closest.equals(start)) return null;
		List<Vec3d> path = new ArrayList<>();
		for (BlockPos at = closest; at != null && !at.equals(start); at = cameFrom.get(at)) path.add(Vec3d.ofBottomCenter(at));
		Collections.reverse(path);
		return path;
	}

	/** Where moving this way from {@code from} ends up: level, a step up or a drop; null if it can't be walked. */
	private static BlockPos step(World world, BlockPos from, int dx, int dz) {
		BlockPos ahead = from.add(dx, 0, dz);
		boolean diagonal = dx != 0 && dz != 0;
		// Squeezing past a corner diagonally catches on it
		if (diagonal && !(clear(world, from.add(dx, 0, 0)) && clear(world, from.add(0, 0, dz)))) return null;
		if (canStand(world, ahead)) return ahead;
		BlockPos up = ahead.up();
		if (!diagonal && canStand(world, up) && passable(world, from.up(2))) return up;
		if (!clear(world, ahead)) return null;
		for (int drop = 1; drop <= MOST_DROP; drop++) {
			BlockPos below = ahead.down(drop);
			if (canStand(world, below)) return below;
			if (!passable(world, below)) return null;
		}
		return null;
	}

	private static double guess(BlockPos from, BlockPos to) {
		int x = Math.abs(from.getX() - to.getX()), z = Math.abs(from.getZ() - to.getZ());
		return Math.max(x, z) + (DIAGONAL - 1) * Math.min(x, z) + Math.abs(from.getY() - to.getY());
	}

	/** Room for a player's feet and head here, with ground (or water) to be on. */
	private static boolean canStand(World world, BlockPos pos) {
		if (!clear(world, pos)) return false;
		BlockPos below = pos.down();
		BlockState ground = world.getBlockState(below);
		if (hurts(ground)) return false;
		return !ground.getCollisionShape(world, below).isEmpty() || world.getFluidState(pos).isIn(FluidTags.WATER);
	}

	private static boolean clear(World world, BlockPos pos) {
		return passable(world, pos) && passable(world, pos.up());
	}

	private static boolean passable(World world, BlockPos pos) {
		if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
		BlockState state = world.getBlockState(pos);
		return !hurts(state) && state.getCollisionShape(world, pos).isEmpty();
	}

	private static boolean hurts(BlockState state) {
		return state.isIn(BlockTags.FIRE) || state.getFluidState().isIn(FluidTags.LAVA) || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK)
			|| state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.WITHER_ROSE) || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.COBWEB);
	}
}
