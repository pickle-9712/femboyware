package dev.femboyware.module.combat;

import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Press the key and it swaps to your Lunge spear, jabs (which throws you forward) and swaps back
 * to what you were holding.
 */
public class LungeSwapModule extends Module {
	private final KeySetting key = add(new KeySetting("Key", "Press to swap to your Lunge spear and jab", -1));
	private final NumberSetting holdTicks = add(new NumberSetting("Hold Ticks", "Ticks to keep the spear before swapping back", 3, 1, 20, 1));
	private final BoolSetting anySpear = add(new BoolSetting("Any Spear", "Use a spear without Lunge if you have no Lunge one", false));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Switch back to your previous slot afterwards", true));

	private int previousSlot = -1;
	/** 0 idle, 1 swapped and about to jab, then counting down to swap back. */
	private int stage;
	private int left;

	public LungeSwapModule() {
		super("Lunge Swap", "Swap to your Lunge spear and jab with an activation key", Category.ATTACK);
	}

	@Override
	protected void onDisable() {
		finish();
	}

	@Override
	public void onKey(int pressed) {
		if (!key.is(pressed) || stage != 0 || Mc.screen() != null) return;
		int slot = InventoryUtil.findHotbar(LungeSwapModule::isLungeSpear);
		if (slot < 0 && anySpear.get()) slot = InventoryUtil.findHotbar(stack -> stack.is(ItemTags.SPEARS));
		if (slot < 0) return;
		int current = mc.player.getInventory().getSelectedSlot();
		previousSlot = slot == current ? -1 : current;
		InventoryUtil.select(slot);
		stage = 1;
	}

	@Override
	public void onTick() {
		if (stage == 1) {
			// A tick after the swap, so the server already has the spear in our hand
			((MinecraftClientAccessor) mc).femboyware$leftClick();
			stage = 2;
			left = holdTicks.getInt();
		} else if (stage == 2 && --left <= 0) {
			finish();
		}
	}

	private void finish() {
		if (previousSlot >= 0 && swapBack.get() && !nullCheck()) InventoryUtil.select(previousSlot);
		previousSlot = -1;
		stage = 0;
	}

	private static boolean isLungeSpear(ItemStack stack) {
		return stack.is(ItemTags.SPEARS) && InventoryUtil.getEnchantmentLevel(stack, Enchantments.LUNGE) > 0;
	}
}
