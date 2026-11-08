package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.world.phys.Vec3;

public class SpeedModule extends Module {
	public enum Mode {
		/** Bunny hops and keeps full speed in the air, in whichever direction you press. */
		STRAFE,
		/** Multiplies your ground speed. */
		VANILLA
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "How to go faster", Mode.STRAFE));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Strafe speed in blocks per tick", 0.32, 0.1, 2, 0.01)).visibleWhen(() -> mode.is(Mode.STRAFE));
	private final BoolSetting autoJump = add(new BoolSetting("Auto Jump", "Jump whenever you land", true)).visibleWhen(() -> mode.is(Mode.STRAFE));
	private final NumberSetting multiplier = add(new NumberSetting("Multiplier", "Ground speed multiplier", 1.3, 1, 3, 0.05)).visibleWhen(() -> mode.is(Mode.VANILLA));

	public SpeedModule() {
		super("Speed", "Move faster", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	@Override
	public void onPreMotion() {
		if (!MoveUtil.isMoving() || mc.player.isShiftKeyDown() || mc.player.isInWater() || mc.player.isInLava()
			|| mc.player.isFallFlying() || mc.player.isPassenger() || mc.player.getAbilities().flying) return;
		Vec3 velocity = mc.player.getDeltaMovement();
		switch (mode.get()) {
			case STRAFE -> {
				if (autoJump.get() && mc.player.onGround()) mc.player.jumpFromGround();
				Vec3 horizontal = MoveUtil.strafe(Math.max(speed.get(), MoveUtil.horizontalSpeed()));
				mc.player.setDeltaMovement(horizontal.x, mc.player.getDeltaMovement().y, horizontal.z);
			}
			case VANILLA -> {
				if (mc.player.onGround()) mc.player.setDeltaMovement(velocity.x * multiplier.get(), velocity.y, velocity.z * multiplier.get());
			}
		}
	}
}
