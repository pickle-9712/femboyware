package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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
		if (!isEnabled() || mc.player == null || mc.world == null) return false;
		if (fallDistance.getInt() <= 1) return true;
		// Measure the drop straight down, ignoring the edge itself
		Vec3d feet = mc.player.getEntityPos();
		BlockHitResult hit = mc.world.raycast(new RaycastContext(feet, new Vec3d(feet.x, mc.world.getBottomY(), feet.z),
			RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.WATER, mc.player));
		if (hit.getType() == HitResult.Type.MISS) return true;
		return feet.y - (hit.getBlockPos().getY() + 1) >= fallDistance.getInt();
	}
}
