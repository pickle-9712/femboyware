package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.phys.AABB;

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
		if (!mc.player.onGround() || mc.options.keyJump.isDown()) return;
		if (mc.player.isShiftKeyDown() || mc.options.keyShift.isDown()) return;
		AABB below = mc.player.getBoundingBox().move(0, -0.5, 0).inflate(-edgeDistance.get(), 0, -edgeDistance.get());
		if (mc.level.getBlockCollisions(mc.player, below).iterator().hasNext()) return;
		mc.player.jumpFromGround();
	}
}
