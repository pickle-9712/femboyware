package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Stops you walking off edges, as if you were sneaking.
 * Ported from Meteor Client's SafeWalk (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class SafeWalkModule extends Module {
	private final NumberSetting fallDistance = add(new NumberSetting("Fall Distance", "Only stop at drops at least this deep", 1, 1, 20, 1));

	public SafeWalkModule() {
		super("Safe Walk", "Stops you from walking off edges", Category.BASIC);
	}

	/** Called from PlayerEntityMixin: whether the local player should stick to ledges. */
	public boolean shouldClip() {
		if (!isEnabled() || mc.player == null || mc.level == null) return false;
		if (fallDistance.getInt() <= 1) return true;
		// Measure the drop straight down, ignoring the edge itself
		Vec3 feet = mc.player.position();
		BlockHitResult hit = mc.level.clip(new ClipContext(feet, new Vec3(feet.x, mc.level.getMinY(), feet.z),
			ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, mc.player));
		if (hit.getType() == HitResult.Type.MISS) return true;
		return feet.y - (hit.getBlockPos().getY() + 1) >= fallDistance.getInt();
	}
}
