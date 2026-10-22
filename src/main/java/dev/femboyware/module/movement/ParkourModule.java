package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.util.math.Box;

/**
 * Jumps for you right at the edge of a block.
 * Ported from Meteor Client's Parkour (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class ParkourModule extends Module {
	private final NumberSetting edgeDistance = add(new NumberSetting("Edge Distance", "How close to the edge to jump", 0.001, 0.001, 0.1, 0.001));

	public ParkourModule() {
		super("Parkour", "Jumps at the edges of blocks", Category.BASIC);
	}

	/** After moving this tick, so the jump goes out with the next movement. */
	@Override
	public void onPreMotion() {
		if (!mc.player.isOnGround() || mc.options.jumpKey.isPressed()) return;
		if (mc.player.isSneaking() || mc.options.sneakKey.isPressed()) return;
		Box below = mc.player.getBoundingBox().offset(0, -0.5, 0).expand(-edgeDistance.get(), 0, -edgeDistance.get());
		if (mc.world.getBlockCollisions(mc.player, below).iterator().hasNext()) return;
		mc.player.jump();
	}
}
