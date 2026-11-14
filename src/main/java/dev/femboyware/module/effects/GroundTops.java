package dev.femboyware.module.effects;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The ground open to the sky around you, for effects that lie on it (snow, wet ground): for each
 * column, the top of its highest solid block. Found again every so often as you move, not every frame.
 */
final class GroundTops {
	/** One column: where its top is, how high its four neighbours' tops are (for edges), and how lit it is. */
	record Top(int x, int z, float y, float[] neighbours, float light) {
	}

	private final List<Top> tops = new ArrayList<>();
	private int cx = Integer.MIN_VALUE, cz, radius;
	private long scannedAt;

	List<Top> around(int wantRadius) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			tops.clear();
			return tops;
		}
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		long now = System.currentTimeMillis();
		boolean moved = Math.abs(px - cx) > 2 || Math.abs(pz - cz) > 2 || wantRadius != radius;
		if (moved || now - scannedAt > 1500) scan(px, pz, wantRadius, now);
		return tops;
	}

	void clear() {
		tops.clear();
		cx = Integer.MIN_VALUE;
	}

	private void scan(int px, int pz, int r, long now) {
		cx = px;
		cz = pz;
		radius = r;
		scannedAt = now;
		tops.clear();
		Minecraft mc = Minecraft.getInstance();
		int size = r * 2 + 1;
		float[][] heights = new float[size + 2][size + 2];
		for (int dx = -r - 1; dx <= r + 1; dx++) {
			for (int dz = -r - 1; dz <= r + 1; dz++) heights[dx + r + 1][dz + r + 1] = top(px + dx, pz + dz);
		}
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				float y = heights[dx + r + 1][dz + r + 1];
				if (Float.isNaN(y)) continue;
				int i = dx + r + 1, j = dz + r + 1;
				// North, south, west, east
				float[] around = {heights[i][j - 1], heights[i][j + 1], heights[i - 1][j], heights[i + 1][j]};
				float light = dev.femboyware.module.larp.LarpLight.at(px + dx + 0.5, y + 0.5, pz + dz + 0.5);
				tops.add(new Top(px + dx, pz + dz, y, around, light));
			}
		}
	}

	/** The top of the highest thing in a column that something could lie on, or NaN. */
	private static float top(int x, int z) {
		Minecraft mc = Minecraft.getInstance();
		int height = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		BlockPos pos = new BlockPos(x, height - 1, z);
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty()) return Float.NaN;
		VoxelShape shape = state.getCollisionShape(mc.level, pos);
		if (shape.isEmpty()) return Float.NaN;
		return pos.getY() + (float) shape.max(Direction.Axis.Y);
	}
}
