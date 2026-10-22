package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.util.math.Box;

/** Snaps you down ledges instead of falling slowly off them. */
public class ReverseStepModule extends Module {
	private final NumberSetting height = add(new NumberSetting("Height", "Highest drop to snap down", 3, 0.5, 10, 0.5));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Downward speed in blocks per tick", 1, 0.1, 5, 0.1));

	private boolean wasOnGround;

	public ReverseStepModule() {
		super("Reverse Step", "Walk down ledges instantly", Category.RAGE);
	}

	@Override
	public void onTick() {
		boolean onGround = mc.player.isOnGround();
		boolean walkedOff = wasOnGround && !onGround && mc.player.getVelocity().y <= 0;
		wasOnGround = onGround;
		if (!walkedOff || mc.options.jumpKey.isPressed() || mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isGliding()) return;

		// Only snap when there's ground within range, otherwise you'd slam into a long fall
		Box below = mc.player.getBoundingBox().offset(0, -height.get(), 0).union(mc.player.getBoundingBox());
		if (!mc.world.getBlockCollisions(mc.player, below).iterator().hasNext()) return;
		mc.player.setVelocity(mc.player.getVelocity().x, -speed.get(), mc.player.getVelocity().z);
	}
}
