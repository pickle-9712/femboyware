package dev.femboyware.module.combat;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Self Trap done like Trap's Legit mode: the ring at your feet, the ring at your head and a roof,
 * aimed and clicked like a player (it jumps to reach the top of the head ring for the roof's support).
 */
public class LegitSelfTrapModule extends LegitBoxModule {
	public LegitSelfTrapModule() {
		super("Legit Self Trap", "Boxes you in obsidian like a player would: feet ring, head ring and roof, flicking onto each block with real switches and clicks, one a tick, between Legit Aura's hits");
	}

	@Override
	protected Set<BlockPos> shape(Set<BlockPos> columns, int y) {
		Set<BlockPos> shape = new LinkedHashSet<>(ring(columns, y));
		shape.addAll(ring(columns, y + 1));
		for (BlockPos column : columns) shape.add(column.atY(y + 2));
		return shape;
	}
}
