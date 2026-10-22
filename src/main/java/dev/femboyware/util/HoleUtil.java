package dev.femboyware.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Holes are one-block pits walled by blast-proof blocks, where crystals can't reach your feet. */
public final class HoleUtil {
	private static final MinecraftClient mc = MinecraftClient.getInstance();
	/** Obsidian and up; anything weaker gets blown away by a crystal. */
	private static final float BLAST_PROOF = 600f;

	private HoleUtil() {
	}

	/** Whether {@code pos} is the open bottom of a safe hole: open space above a blast-proof floor and walls. */
	public static boolean isHole(BlockPos pos) {
		if (!isOpen(pos) || !isOpen(pos.up()) || !isOpen(pos.up(2))) return false;
		if (!isBlastProof(mc.world.getBlockState(pos.down()))) return false;
		for (Direction direction : Direction.Type.HORIZONTAL) {
			if (!isBlastProof(mc.world.getBlockState(pos.offset(direction)))) return false;
		}
		return true;
	}

	public static boolean isBlastProof(BlockState state) {
		return !state.isAir() && state.getBlock().getBlastResistance() >= BLAST_PROOF;
	}

	private static boolean isOpen(BlockPos pos) {
		return mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
	}
}
