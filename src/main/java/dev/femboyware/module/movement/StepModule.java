package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;

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
		EntityAttributeInstance step = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
		// Sneaking keeps vanilla behaviour so you can still edge along without climbing
		double wanted = mc.player.isSneaking() ? VANILLA_STEP : height.get();
		if (step != null && step.getBaseValue() != wanted) step.setBaseValue(wanted);
	}

	@Override
	protected void onDisable() {
		if (nullCheck()) return;
		EntityAttributeInstance step = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
		if (step != null) step.setBaseValue(VANILLA_STEP);
	}
}
