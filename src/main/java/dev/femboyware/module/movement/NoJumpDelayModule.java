package dev.femboyware.module.movement;

import dev.femboyware.mixin.LivingEntityAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;

/** Jump again the moment you land: no wait between jumps when you hold space. */
public class NoJumpDelayModule extends Module {
	public NoJumpDelayModule() {
		super("No Jump Delay", "No wait between jumps on the ground", Category.BASIC);
	}

	@Override
	public void onTick() {
		if (mc.player != null) ((LivingEntityAccessor) mc.player).femboyware$setNoJumpDelay(0);
	}
}
