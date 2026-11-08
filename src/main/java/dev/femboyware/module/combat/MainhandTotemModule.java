package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.world.item.Items;

/**
 * Keeps a totem stocked in one hotbar slot and switches to it when your health gets low, so you
 * have a second totem on top of the offhand one.
 */
public class MainhandTotemModule extends Module {
	private final NumberSetting slot = add(new NumberSetting("Slot", "Hotbar slot to keep the totem in", 9, 1, 9, 1));
	private final NumberSetting health = add(new NumberSetting("Health", "Hold the totem at or below this much health plus absorption", 8, 1, 36, 0.5));
	private final BoolSetting refill = add(new BoolSetting("Refill", "Move a totem from your inventory into the slot", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous slot once safe", true));

	private int previousSlot = -1;

	public MainhandTotemModule() {
		super("Mainhand Totem", "Holds a totem in your main hand at low health", Category.PROTECT);
	}

	@Override
	public String getInfo() {
		return nullCheck() ? null : String.valueOf(InventoryUtil.count(stack -> stack.is(Items.TOTEM_OF_UNDYING)));
	}

	@Override
	public void onTick() {
		int hotbar = slot.getInt() - 1;
		boolean stocked = mc.player.getInventory().getItem(hotbar).is(Items.TOTEM_OF_UNDYING);
		if (!stocked && refill.get() && mc.player.containerMenu == mc.player.inventoryMenu) {
			int spare = InventoryUtil.findMain(stack -> stack.is(Items.TOTEM_OF_UNDYING));
			if (spare >= 0) {
				InventoryUtil.swapWithHotbar(spare, hotbar);
				stocked = true;
			}
		}

		boolean danger = mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get();
		int selected = mc.player.getInventory().getSelectedSlot();
		if (danger && stocked && selected != hotbar) {
			previousSlot = selected;
			InventoryUtil.select(hotbar);
		} else if (!danger && previousSlot >= 0) {
			if (swapBack.get() && selected == hotbar) InventoryUtil.select(previousSlot);
			previousSlot = -1;
		}
	}
}
