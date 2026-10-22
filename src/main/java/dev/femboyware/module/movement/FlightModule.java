package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.util.math.Vec3d;

public class FlightModule extends Module {
	public enum Mode {
		/** Creative flight abilities. */
		VANILLA,
		/** Sets your velocity directly from the keys. */
		VELOCITY
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "How to fly", Mode.VELOCITY));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Horizontal speed in blocks per tick", 1, 0.05, 5, 0.05));
	private final NumberSetting verticalSpeed = add(new NumberSetting("Vertical Speed", "Jump and sneak speed in blocks per tick", 0.6, 0.05, 5, 0.05)).visibleWhen(() -> mode.is(Mode.VELOCITY));
	private final BoolSetting antiKick = add(new BoolSetting("Anti Kick", "Dip down briefly every couple of seconds so the server doesn't kick you for flying", true));

	private int ticks;

	public FlightModule() {
		super("Flight", "Lets you fly", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	@Override
	protected void onDisable() {
		if (nullCheck() || !mode.is(Mode.VANILLA)) return;
		boolean creative = mc.player.isCreative() || mc.player.isSpectator();
		mc.player.getAbilities().flying = creative && mc.player.getAbilities().flying;
		mc.player.getAbilities().allowFlying = creative;
		mc.player.getAbilities().setFlySpeed(0.05f);
	}

	@Override
	public void onTick() {
		ticks++;
		boolean kick = antiKick.get() && ticks % 40 == 0;
		switch (mode.get()) {
			case VANILLA -> {
				mc.player.getAbilities().allowFlying = true;
				mc.player.getAbilities().flying = true;
				mc.player.getAbilities().setFlySpeed(speed.getFloat() / 10f);
				if (kick) mc.player.setVelocity(mc.player.getVelocity().x, -0.04, mc.player.getVelocity().z);
			}
			case VELOCITY -> {
				Vec3d horizontal = MoveUtil.strafe(speed.get());
				double y = kick ? -0.04 : MoveUtil.vertical(verticalSpeed.get());
				mc.player.setVelocity(horizontal.x, y, horizontal.z);
			}
		}
	}
}
