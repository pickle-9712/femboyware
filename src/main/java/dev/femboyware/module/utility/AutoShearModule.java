package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.Timer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Items;

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
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.SHEARS));
		if (slot < 0) return;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Sheep sheep) || !sheep.readyForShearing() || mc.player.distanceTo(sheep) > range.get()) continue;
			InventoryUtil.withSlot(slot, switchMode.get(), () -> {
				//? if >=26.1 {
				/*mc.gameMode.interact(mc.player, sheep, new net.minecraft.world.phys.EntityHitResult(sheep), InteractionHand.MAIN_HAND);
				*///?} else {
				mc.gameMode.interact(mc.player, sheep, InteractionHand.MAIN_HAND);
				//?}
				mc.player.swing(InteractionHand.MAIN_HAND);
			});
			timer.reset();
			return;
		}
	}
}
