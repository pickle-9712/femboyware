package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Walk up full blocks (or higher) without jumping, by raising your step height attribute. */
public class StepModule extends Module {
	private static final double VANILLA_STEP = 0.6;

	private final NumberSetting height = add(new NumberSetting("Height", "Step height in blocks", 1, 0.6, 4, 0.1));

	public StepModule() {
		super("Step", "Step up blocks instantly", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return String.valueOf(height.get());
	}

	@Override
	public void onTick() {
		AttributeInstance step = mc.player.getAttribute(Attributes.STEP_HEIGHT);
		// Sneaking keeps vanilla behaviour so you can still edge along without climbing
		double wanted = mc.player.isShiftKeyDown() ? VANILLA_STEP : height.get();
		if (step != null && step.getBaseValue() != wanted) step.setBaseValue(wanted);
	}

	@Override
	protected void onDisable() {
		if (nullCheck()) return;
		AttributeInstance step = mc.player.getAttribute(Attributes.STEP_HEIGHT);
		if (step != null) step.setBaseValue(VANILLA_STEP);
	}
}
