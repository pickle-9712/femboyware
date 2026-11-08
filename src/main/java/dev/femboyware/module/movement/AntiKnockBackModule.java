package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Scales the knockback you take from hits and explosions. Applied by the network handler mixin. */
public class AntiKnockBackModule extends Module {
	private final NumberSetting horizontal = add(new NumberSetting("Horizontal", "Percent of horizontal knockback to keep", 0, 0, 100, 1));
	private final NumberSetting vertical = add(new NumberSetting("Vertical", "Percent of vertical knockback to keep", 0, 0, 100, 1));
	private final BoolSetting hits = add(new BoolSetting("Hits", "Knockback from hits, arrows, fishing rods and anything else that shoves you", true));
	private final BoolSetting explosions = add(new BoolSetting("Explosions", "Knockback from crystals, anchors, TNT, beds and wind charges", true));

	public AntiKnockBackModule() {
		super("Anti Knock Back", "Stops knockback from moving you; at 0% you don't budge at all", Category.BASIC);
	}

	@Override
	public String getInfo() {
		return horizontal.getInt() + "% " + vertical.getInt() + "%";
	}

	/**
	 * Velocity the server set on us from a hit. At 0% the knockback is ignored outright, so we keep
	 * moving exactly as we were (standing still stays standing still) instead of being stopped dead.
	 */
	public Vec3 modifyVelocity(Vec3 velocity) {
		if (!isEnabled() || !hits.get()) return velocity;
		Vec3 current = mc.player.getDeltaMovement();
		double h = horizontal.get() / 100.0;
		double v = vertical.get() / 100.0;
		return new Vec3(Mth.lerp(h, current.x, velocity.x), Mth.lerp(v, current.y, velocity.y), Mth.lerp(h, current.z, velocity.z));
	}

	/** Velocity added to us by an explosion. */
	public Vec3 modifyExplosion(Vec3 knockback) {
		if (!isEnabled() || !explosions.get()) return knockback;
		return scale(knockback);
	}

	private Vec3 scale(Vec3 velocity) {
		double h = horizontal.get() / 100.0;
		double v = vertical.get() / 100.0;
		return new Vec3(velocity.x * h, velocity.y * v, velocity.z * h);
	}
}
