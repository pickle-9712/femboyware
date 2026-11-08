package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.world.phys.Vec3;

/** Better elytra flight. Applied to vanilla's gliding velocity by the gliding mixin. */
public class ElytraPlusModule extends Module {
	public enum Mode {
		/** Fly like creative: keys steer, no gravity. */
		CONTROL,
		/** Vanilla gliding with extra acceleration where you look. */
		BOOST
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "How elytra flight is changed", Mode.CONTROL));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Horizontal speed in blocks per tick", 1.8, 0.1, 5, 0.05)).visibleWhen(() -> mode.is(Mode.CONTROL));
	private final NumberSetting verticalSpeed = add(new NumberSetting("Vertical Speed", "Jump and sneak speed in blocks per tick", 1, 0, 5, 0.05)).visibleWhen(() -> mode.is(Mode.CONTROL));
	private final NumberSetting fall = add(new NumberSetting("Fall", "Constant sink speed while idle", 0, 0, 0.2, 0.005)).visibleWhen(() -> mode.is(Mode.CONTROL));
	private final NumberSetting acceleration = add(new NumberSetting("Acceleration", "Speed added per tick while holding forward", 0.05, 0.01, 0.5, 0.01)).visibleWhen(() -> mode.is(Mode.BOOST));
	private final NumberSetting maxSpeed = add(new NumberSetting("Max Speed", "Boost stops above this speed", 2.5, 0.5, 6, 0.1)).visibleWhen(() -> mode.is(Mode.BOOST));
	private final BoolSetting stopInWater = add(new BoolSetting("Stop In Water", "Don't change anything while in water", true));

	public ElytraPlusModule() {
		super("Elytra Plus", "Controllable elytra flight", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	/** Called with vanilla's gliding velocity for the local player. */
	public Vec3 modifyGliding(Vec3 velocity) {
		if (!isEnabled() || stopInWater.get() && mc.player.isInWater()) return velocity;
		return switch (mode.get()) {
			case CONTROL -> {
				Vec3 horizontal = MoveUtil.strafe(speed.get());
				double y = MoveUtil.vertical(verticalSpeed.get());
				yield new Vec3(horizontal.x, y == 0 ? -fall.get() : y, horizontal.z);
			}
			case BOOST -> {
				if (!mc.options.keyUp.isDown() || velocity.length() >= maxSpeed.get()) yield velocity;
				yield velocity.add(mc.player.getLookAngle().scale(acceleration.get()));
			}
		};
	}
}
