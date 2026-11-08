package dev.femboyware.module.combat;

import dev.femboyware.util.Mc;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Eats a golden apple by holding the use key for you once your health drops low. */
public class AutoGappleModule extends Module {
	private final NumberSetting health = add(new NumberSetting("Health", "Eat at or below this much health plus absorption", 14, 1, 36, 0.5));
	private final BoolSetting preferEnchanted = add(new BoolSetting("Prefer Enchanted", "Eat enchanted golden apples first", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous slot after eating", true));

	private boolean eating;
	private int eatingTicks;
	private int previousSlot = -1;

	public AutoGappleModule() {
		super("Auto Gapple", "Eats golden apples when your health is low", Category.AUTO);
	}

	@Override
	protected void onDisable() {
		if (eating) stop();
	}

	public boolean isEating() {
		return eating;
	}

	@Override
	public void onTick() {
		if (eating) {
			eatingTicks++;
			boolean holding = isGapple(mc.player.getMainHandItem()) || isGapple(mc.player.getOffhandItem());
			// Using stops once the apple is eaten; give the server a few ticks to start the use first
			if (!holding || eatingTicks > 4 && !mc.player.isUsingItem()) {
				stop();
				return;
			}
			mc.options.keyUse.setDown(true);
			return;
		}

		if (mc.player.getHealth() + mc.player.getAbsorptionAmount() > health.get() || Mc.screen() != null) return;
		if (!isGapple(mc.player.getOffhandItem()) && !isGapple(mc.player.getMainHandItem())) {
			int slot = findApple();
			if (slot < 0) return;
			previousSlot = mc.player.getInventory().getSelectedSlot();
			InventoryUtil.select(slot);
		}
		eating = true;
		eatingTicks = 0;
		mc.options.keyUse.setDown(true);
	}

	private int findApple() {
		if (preferEnchanted.get()) {
			int enchanted = InventoryUtil.findHotbar(stack -> stack.is(Items.ENCHANTED_GOLDEN_APPLE));
			if (enchanted >= 0) return enchanted;
		}
		return InventoryUtil.findHotbar(AutoGappleModule::isGapple);
	}

	private void stop() {
		mc.options.keyUse.setDown(false);
		if (previousSlot >= 0 && swapBack.get()) InventoryUtil.select(previousSlot);
		previousSlot = -1;
		eating = false;
	}

	private static boolean isGapple(ItemStack stack) {
		return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
	}
}
