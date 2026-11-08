package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.phys.AABB;

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
		boolean onGround = mc.player.onGround();
		boolean walkedOff = wasOnGround && !onGround && mc.player.getDeltaMovement().y <= 0;
		wasOnGround = onGround;
		if (!walkedOff || mc.options.keyJump.isDown() || mc.player.isInWater() || mc.player.isInLava() || mc.player.isFallFlying()) return;

		// Only snap when there's ground within range, otherwise you'd slam into a long fall
		AABB below = mc.player.getBoundingBox().move(0, -height.get(), 0).minmax(mc.player.getBoundingBox());
		if (!mc.level.getBlockCollisions(mc.player, below).iterator().hasNext()) return;
		mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, -speed.get(), mc.player.getDeltaMovement().z);
	}
}
