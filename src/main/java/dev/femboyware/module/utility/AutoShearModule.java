package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.Timer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

/** Shears every sheep in reach. */
public class AutoShearModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "How far to reach", 4, 1, 6, 0.5));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between shears", 200, 0, 1000, 10));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to shears", SwitchMode.SILENT));

	private final Timer timer = new Timer();

	public AutoShearModule() {
		super("Auto Shear", "Shears nearby sheep", Category.AUTO);
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.get())) return;
		int slot = InventoryUtil.findHotbar(stack -> stack.isOf(Items.SHEARS));
		if (slot < 0) return;
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof SheepEntity sheep) || !sheep.isShearable() || mc.player.distanceTo(sheep) > range.get()) continue;
			InventoryUtil.withSlot(slot, switchMode.get(), () -> {
				mc.interactionManager.interactEntity(mc.player, sheep, Hand.MAIN_HAND);
				mc.player.swingHand(Hand.MAIN_HAND);
			});
			timer.reset();
			return;
		}
	}
}
