package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

/** Jumps for you. Ported from the Anubis client's Auto Jump (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public class AutoJumpModule extends Module {
	public enum Mode {
		JUMP, LOW_HOP
	}

	public enum When {
		ALWAYS, WALKING, SPRINTING
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "A normal jump, or a small hop of your chosen height", Mode.JUMP));
	private final EnumSetting<When> when = add(new EnumSetting<>("Jump If", "Always, only while moving, or only while sprinting", When.ALWAYS));
	private final NumberSetting height = add(new NumberSetting("Hop Height", "Upward speed of a Low Hop", 0.25, 0, 2, 0.05));

	public AutoJumpModule() {
		super("Auto Jump", "Jumps for you", Category.BASIC);
	}

	@Override
	public void onTick() {
		if (mc.player == null || !mc.player.onGround() || mc.player.isShiftKeyDown()) return;
		boolean moving = mc.player.zza != 0 || mc.player.xxa != 0;
		boolean go = switch (when.get()) {
			case ALWAYS -> true;
			case WALKING -> moving;
			case SPRINTING -> moving && mc.player.isSprinting();
		};
		if (!go) return;
		if (mode.is(Mode.JUMP)) mc.player.jumpFromGround();
		else {
			Vec3 v = mc.player.getDeltaMovement();
			mc.player.setDeltaMovement(v.x, height.get(), v.z);
		}
	}
}
