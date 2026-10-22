package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Swaps to a Breach mace for the attack packet and straight back, so hits against armoured
 * targets ignore part of their armor while you keep holding your weapon.
 */
public class BreachSwapModule extends Module {
	private final NumberSetting minArmor = add(new NumberSetting("Min Armor", "Only swap when the target has at least this many armor points", 8, 0, 30, 1));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Switch back to the previous slot after the hit", true));

	private int previousSlot = -1;

	public BreachSwapModule() {
		super("Breach Swap", "Hits with a Breach mace while holding another weapon", Category.ATTACK);
	}

	@Override
	public void onPreAttack(Entity target) {
		previousSlot = -1;
		if (!(target instanceof LivingEntity living) || living.getArmor() < minArmor.getInt()) return;
		if (isBreachMace(mc.player.getMainHandStack())) return;
		int slot = InventoryUtil.findHotbar(BreachSwapModule::isBreachMace);
		if (slot < 0) return;
		previousSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
	}

	@Override
	public void onPostAttack(Entity target) {
		if (previousSlot >= 0 && swapBack.get()) InventoryUtil.select(previousSlot);
		previousSlot = -1;
	}

	private static boolean isBreachMace(ItemStack stack) {
		return stack.isOf(Items.MACE) && InventoryUtil.getEnchantmentLevel(stack, Enchantments.BREACH) > 0;
	}
}
