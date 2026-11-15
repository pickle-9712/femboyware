package dev.femboyware.module.combat;

import java.util.Set;
import net.minecraft.core.BlockPos;

/** Surround done like Trap's Legit mode: the ring of blocks round your feet, aimed and clicked like a player. */
public class LegitSurroundModule extends LegitBoxModule {
	public LegitSurroundModule() {
		super("Legit Surround", "Rings your feet with obsidian like a player would: centres you, flicks onto each block, real switches and clicks, one a tick, between Legit Aura's hits");
	}

	@Override
	protected Set<BlockPos> shape(Set<BlockPos> columns, int y) {
		return ring(columns, y);
	}
}
