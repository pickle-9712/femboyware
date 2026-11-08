package dev.femboyware.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Holes are one-block pits walled by blast-proof blocks, where crystals can't reach your feet. */
public final class HoleUtil {
	private static final Minecraft mc = Minecraft.getInstance();
	/** Obsidian and up; anything weaker gets blown away by a crystal. */
	private static final float BLAST_PROOF = 600f;

	private HoleUtil() {
	}

	/** Whether {@code pos} is the open bottom of a safe hole: open space above a blast-proof floor and walls. */
	public static boolean isHole(BlockPos pos) {
		if (!isOpen(pos) || !isOpen(pos.above()) || !isOpen(pos.above(2))) return false;
		if (!isBlastProof(mc.level.getBlockState(pos.below()))) return false;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (!isBlastProof(mc.level.getBlockState(pos.relative(direction)))) return false;
		}
		return true;
	}

	public static boolean isBlastProof(BlockState state) {
		return !state.isAir() && state.getBlock().getExplosionResistance() >= BLAST_PROOF;
	}

	private static boolean isOpen(BlockPos pos) {
		return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
	}
}
