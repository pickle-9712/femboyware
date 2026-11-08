package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.Timer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Keeps an elytra flight going indefinitely: fires rockets whenever you slow down and swaps in a
 * fresh elytra before the worn one breaks.
 */
public class InfiniteElytraModule extends Module {
	private final BoolSetting rockets = add(new BoolSetting("Rockets", "Use fireworks to keep your speed up", true));
	private final NumberSetting minSpeed = add(new NumberSetting("Min Speed", "Fire a rocket below this speed in blocks per tick", 0.8, 0.1, 3, 0.05)).visibleWhen(rockets::get);
	private final NumberSetting rocketDelay = add(new NumberSetting("Rocket Delay", "Milliseconds between rockets", 1500, 200, 5000, 50)).visibleWhen(rockets::get);
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to fireworks", SwitchMode.SILENT)).visibleWhen(rockets::get);
	private final BoolSetting replace = add(new BoolSetting("Replace", "Swap to another elytra when this one is nearly broken", true));
	private final NumberSetting durability = add(new NumberSetting("Durability", "Swap when this much durability is left", 10, 1, 100, 1)).visibleWhen(replace::get);

	private final Timer rocketTimer = new Timer();

	public InfiniteElytraModule() {
		super("Infinite Elytra", "Keeps your elytra flight going with rockets and spare elytras", Category.BASIC);
	}

	@Override
	public void onTick() {
		if (replace.get() && mc.player.containerMenu == mc.player.inventoryMenu) replaceElytra();
		if (!mc.player.isFallFlying()) return;

		if (rockets.get() && mc.player.getDeltaMovement().length() < minSpeed.get() && rocketTimer.passed(rocketDelay.get())) {
			if (PlaceUtil.useItem(stack -> stack.is(Items.FIREWORK_ROCKET), switchMode.get())) rocketTimer.reset();
		}
	}

	private void replaceElytra() {
		ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
		if (!worn.is(Items.ELYTRA) || remaining(worn) > durability.getInt()) return;
		int spare = InventoryUtil.findInventory(stack -> stack.is(Items.ELYTRA) && remaining(stack) > durability.getInt());
		if (spare < 0) return;
		InventoryUtil.swapSlots(InventoryUtil.screenSlot(spare), InventoryUtil.armorScreenSlot(EquipmentSlot.CHEST));
	}

	private static int remaining(ItemStack stack) {
		return stack.getMaxDamage() - stack.getDamageValue();
	}
}
