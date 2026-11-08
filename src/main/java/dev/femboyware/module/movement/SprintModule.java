package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.util.KeyUtil;

public class SprintModule extends Module {
	public enum Mode {
		/** Holds the sprint key, exactly like toggle sprint. */
		LEGIT,
		/** Sprints in every direction. */
		OMNI
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "How to sprint", Mode.LEGIT));

	public SprintModule() {
		super("Sprint", "Sprints automatically", Category.BASIC);
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	@Override
	protected void onDisable() {
		if (!nullCheck()) KeyUtil.sync(mc.options.keySprint);
	}

	@Override
	public void onTick() {
		if (mode.is(Mode.LEGIT)) {
			mc.options.keySprint.setDown(true);
			return;
		}
		boolean moving = mc.player.input.getMoveVector().lengthSquared() > 0;
		boolean hungry = mc.player.getFoodData().getFoodLevel() <= 6 && !mc.player.getAbilities().mayfly;
		if (moving && !hungry && !mc.player.isShiftKeyDown() && !mc.player.horizontalCollision) mc.player.setSprinting(true);
	}
}
