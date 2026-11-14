package dev.femboyware.module.utility;

import dev.femboyware.mixin.ClientPlayerInteractionManagerAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;

/** Start breaking the next block straight away, without the game's short pause after each one. */
public class NoBreakDelayModule extends Module {
	public NoBreakDelayModule() {
		super("No Break Delay", "No pause between breaking blocks", Category.PLAYER);
	}

	@Override
	public void onTick() {
		if (mc.gameMode != null) ((ClientPlayerInteractionManagerAccessor) mc.gameMode).femboyware$setDestroyDelay(0);
	}
}
