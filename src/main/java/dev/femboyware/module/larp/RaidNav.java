package dev.femboyware.module.larp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Walking routes round the mansion for the raid's agents, in the mansion's own space: a grid over
 * each floor with its walls and furniture blocked off, so they go round the sofa, the dining chairs
 * and the watch cases instead of through them.
 */
final class RaidNav {
	/** How far an agent's body reaches out from where they stand, and the grid's cell size. */
	private static final float RADIUS = 0.27f, CELL = 0.2f;
	private static final float X1 = -21, Z1 = -19, X2 = 21, Z2 = 32;
	private static final int NX = (int) ((X2 - X1) / CELL), NZ = (int) ((Z2 - Z1) / CELL);
	/** Each floor's grid, by its height: true where an agent can't stand. */
	private static final Map<Float, boolean[]> GRIDS = new HashMap<>();

	private RaidNav() {
	}

	private static boolean[] grid(float y) {
		float key = Math.round(y * 10) / 10f;
		return GRIDS.computeIfAbsent(key, k -> {
			List<float[]> boxes = new ArrayList<>();
			for (float[] b : MansionModule.navBoxes()) {
				if (b[1] < k + 1.7f && b[4] > k + 0.15f) boxes.add(b);
			}
			boolean[] blocked = new boolean[NX * NZ];
			for (float[] b : boxes) {
				int i1 = Math.max(0, (int) Math.floor((b[0] - RADIUS - X1) / CELL)), i2 = Math.min(NX - 1, (int) Math.floor((b[3] + RADIUS - X1) / CELL));
				int k1 = Math.max(0, (int) Math.floor((b[2] - RADIUS - Z1) / CELL)), k2 = Math.min(NZ - 1, (int) Math.floor((b[5] + RADIUS - Z1) / CELL));
				for (int i = i1; i <= i2; i++) {
					for (int j = k1; j <= k2; j++) blocked[i * NZ + j] = true;
				}
			}
			return blocked;
		});
	}

	private static int ix(float x) {
		return (int) Math.floor((x - X1) / CELL);
	}

	private static int iz(float z) {
		return (int) Math.floor((z - Z1) / CELL);
	}

	private static boolean inGrid(float x, float z) {
		return x > X1 && x < X2 && z > Z1 && z < Z2;
	}

	private static boolean blocked(boolean[] grid, float x, float z, float[] avoid, float avoidR) {
		if (!inGrid(x, z)) return false;
		if (avoid != null && Math.hypot(x - avoid[0], z - avoid[2]) < avoidR) return true;
		return grid[ix(x) * NZ + iz(z)];
	}

	private static boolean cellBlocked(boolean[] grid, int i, int k, float[] avoid, float avoidR) {
		if (i < 0 || k < 0 || i >= NX || k >= NZ) return true;
		if (grid[i * NZ + k]) return true;
		return avoid != null && Math.hypot(X1 + (i + 0.5f) * CELL - avoid[0], Z1 + (k + 0.5f) * CELL - avoid[2]) < avoidR;
	}

	/** Whether the straight walk from a to b (on a's floor) is clear. */
	static boolean clear(float[] a, float[] b, float[] avoid, float avoidR) {
		boolean[] grid = grid(a[1]);
		double d = Math.hypot(b[0] - a[0], b[2] - a[2]);
		int n = Math.max(1, (int) Math.ceil(d / 0.08));
		for (int s = 1; s < n; s++) {
			float t = s / (float) n;
			if (blocked(grid, a[0] + (b[0] - a[0]) * t, a[2] + (b[2] - a[2]) * t, avoid, avoidR)) return false;
		}
		return true;
	}

	/** Whether someone could stand at p. */
	static boolean free(float[] p) {
		return !inGrid(p[0], p[2]) || !blocked(grid(p[1]), p[0], p[2], null, 0);
	}

	/** The nearest spot to p where someone could stand, within {@code reach}, or p itself if there's none. */
	static float[] nearestFree(float[] p, float reach) {
		if (free(p)) return p;
		boolean[] grid = grid(p[1]);
		int ci = ix(p[0]), ck = iz(p[2]), rings = (int) Math.ceil(reach / CELL);
		float[] best = null;
		double bestD = Double.MAX_VALUE;
		for (int r = 1; r <= rings && best == null; r++) {
			for (int i = ci - r; i <= ci + r; i++) {
				for (int k = ck - r; k <= ck + r; k++) {
					if (Math.max(Math.abs(i - ci), Math.abs(k - ck)) != r || cellBlocked(grid, i, k, null, 0)) continue;
					float x = X1 + (i + 0.5f) * CELL, z = Z1 + (k + 0.5f) * CELL;
					double d = Math.hypot(x - p[0], z - p[2]);
					if (d < bestD) {
						bestD = d;
						best = new float[]{x, p[1], z};
					}
				}
			}
		}
		return best != null ? best : p;
	}

	/**
	 * The turns to make walking from a to b on a's floor, round anything in the way and outside
	 * {@code avoidR} of {@code avoid} (if given); not including a or b. Null if there's no way round.
	 */
	static List<float[]> route(float[] a, float[] b, float[] avoid, float avoidR) {
		if (!inGrid(a[0], a[2]) || !inGrid(b[0], b[2])) return null;
		boolean[] grid = grid(a[1]);
		float[] from = nearestFree(a, 1.5f), to = nearestFree(b, 1.5f);
		int si = ix(from[0]), sk = iz(from[2]), gi = ix(to[0]), gk = iz(to[2]);
		if (cellBlocked(grid, si, sk, null, 0) || cellBlocked(grid, gi, gk, null, 0)) return null;
		int n = NX * NZ, start = si * NZ + sk, goal = gi * NZ + gk;
		float[] cost = new float[n];
		java.util.Arrays.fill(cost, Float.MAX_VALUE);
		int[] parent = new int[n];
		boolean[] done = new boolean[n];
		PriorityQueue<float[]> open = new PriorityQueue<>((p, q) -> Float.compare(p[0], q[0]));
		cost[start] = 0;
		parent[start] = -1;
		open.add(new float[]{h(si, sk, gi, gk), start});
		int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
		boolean found = false;
		int expanded = 0;
		while (!open.isEmpty() && expanded++ < 60_000) {
			int c = (int) open.poll()[1];
			if (done[c]) continue;
			done[c] = true;
			if (c == goal) {
				found = true;
				break;
			}
			int ci = c / NZ, ck = c % NZ;
			for (int[] s : steps) {
				int ni = ci + s[0], nk = ck + s[1];
				// Avoid zone only blocks cells away from the ends, so a walk can start or finish beside you
				if (cellBlocked(grid, ni, nk, null, 0)) continue;
				if (avoid != null && cellBlocked(grid, ni, nk, avoid, avoidR) && !(ni == gi && nk == gk)) continue;
				if (s[0] != 0 && s[1] != 0 && (cellBlocked(grid, ci + s[0], ck, null, 0) || cellBlocked(grid, ci, ck + s[1], null, 0))) continue;
				int nc = ni * NZ + nk;
				float g = cost[c] + (s[0] != 0 && s[1] != 0 ? 1.4142f : 1);
				if (g < cost[nc]) {
					cost[nc] = g;
					parent[nc] = c;
					open.add(new float[]{g + h(ni, nk, gi, gk), nc});
				}
			}
		}
		if (!found) return null;
		List<float[]> cells = new ArrayList<>();
		for (int c = goal; c != -1; c = parent[c]) cells.addFirst(new float[]{X1 + (c / NZ + 0.5f) * CELL, a[1], Z1 + (c % NZ + 0.5f) * CELL});
		// Pull the string tight: from each point, straight to the furthest one it can see
		cells.set(0, from);
		if (cells.size() == 1) cells.add(to);
		else cells.set(cells.size() - 1, to);
		List<float[]> out = new ArrayList<>();
		if (from != a) out.add(from);
		int last = cells.size() - 1, i = 0;
		while (i < last) {
			int far = i + 1;
			for (int j = last; j > i + 1; j--) {
				if (clear(cells.get(i), cells.get(j), avoid, avoidR)) {
					far = j;
					break;
				}
			}
			if (far < last) out.add(cells.get(far));
			i = far;
		}
		if (to != b) out.add(to);
		// Drop turns that are right on top of a or b
		out.removeIf(p -> Math.hypot(p[0] - a[0], p[2] - a[2]) < 0.05 || Math.hypot(p[0] - b[0], p[2] - b[2]) < 0.05);
		return out;
	}

	private static float h(int i, int k, int gi, int gk) {
		int dx = Math.abs(i - gi), dz = Math.abs(k - gk);
		return Math.max(dx, dz) + 0.4142f * Math.min(dx, dz);
	}
}
