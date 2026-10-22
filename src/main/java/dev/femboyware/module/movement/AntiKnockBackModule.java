package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.util.math.Vec3d;

/** Scales the knockback you take from hits and explosions. Applied by the network handler mixin. */
public class AntiKnockBackModule extends Module {
	private final NumberSetting horizontal = add(new NumberSetting("Horizontal", "Percent of horizontal knockback to keep", 0, 0, 100, 1));
	private final NumberSetting vertical = add(new NumberSetting("Vertical", "Percent of vertical knockback to keep", 0, 0, 100, 1));
	private final BoolSetting hits = add(new BoolSetting("Hits", "Scale knockback from attacks", true));
	private final BoolSetting explosions = add(new BoolSetting("Explosions", "Scale knockback from explosions", true));

	public AntiKnockBackModule() {
		super("Anti Knock Back", "Reduces the knockback you take", Category.BASIC);
	}

	@Override
	public String getInfo() {
		return horizontal.getInt() + "% " + vertical.getInt() + "%";
	}

	/** Velocity the server set on us from a hit. */
	public Vec3d modifyVelocity(Vec3d velocity) {
		if (!isEnabled() || !hits.get()) return velocity;
		return scale(velocity);
	}

	/** Velocity added to us by an explosion. */
	public Vec3d modifyExplosion(Vec3d knockback) {
		if (!isEnabled() || !explosions.get()) return knockback;
		return scale(knockback);
	}

	private Vec3d scale(Vec3d velocity) {
		double h = horizontal.get() / 100.0;
		double v = vertical.get() / 100.0;
		return new Vec3d(velocity.x * h, velocity.y * v, velocity.z * h);
	}
}
