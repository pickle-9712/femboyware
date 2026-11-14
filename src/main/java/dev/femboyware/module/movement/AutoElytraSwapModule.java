package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Wear your chestplate and fly anyway: when you start falling it swaps your elytra on (and opens it
 * if you like), and once you've landed it puts the chestplate back.
 */
public class AutoElytraSwapModule extends Module {
	private final NumberSetting fall = add(new NumberSetting("Fall Distance", "Swap the elytra on after falling this far", 2, 0.5, 10, 0.5));
	private final BoolSetting deploy = add(new BoolSetting("Deploy", "Open the elytra straight after swapping it on", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Put the chestplate back on once you land", true));
	private final NumberSetting landTicks = add(new NumberSetting("Land Ticks", "Ticks on the ground before swapping back", 4, 1, 40, 1)).visibleWhen(swapBack::get);

	/** We put the elytra on, so we'll take it off again. */
	private boolean swapped;
	private int deployIn = -1, grounded;

	public AutoElytraSwapModule() {
		super("Auto Elytra Swap", "Swaps your elytra on when you fall and your chestplate back when you land", Category.BASIC);
	}

	@Override
	protected void onDisable() {
		swapped = false;
		deployIn = -1;
	}

	@Override
	public void onTick() {
		if (mc.player.containerMenu != mc.player.inventoryMenu) return;
		var p = mc.player;
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		grounded = p.onGround() || p.isInWater() ? grounded + 1 : 0;

		if (deployIn >= 0 && deployIn-- == 0 && !p.onGround() && !p.isFallFlying() && chest.is(Items.ELYTRA) && p.tryToStartFallFlying()) {
			mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		}

		// Falling: on with the elytra
		if (!chest.is(Items.ELYTRA) && !p.onGround() && !p.isInWater() && !p.getAbilities().flying && p.fallDistance >= fall.get()) {
			int elytra = InventoryUtil.findInventory(stack -> stack.is(Items.ELYTRA) && stack.getMaxDamage() - stack.getDamageValue() > 1);
			if (elytra < 0) return;
			InventoryUtil.swapSlots(InventoryUtil.screenSlot(elytra), InventoryUtil.armorScreenSlot(EquipmentSlot.CHEST));
			swapped = true;
			if (deploy.get()) deployIn = 1;
			return;
		}

		// Landed: the chestplate goes back on
		if (swapped && swapBack.get() && chest.is(Items.ELYTRA) && !p.isFallFlying() && grounded >= landTicks.getInt()) {
			int plate = best();
			if (plate >= 0) InventoryUtil.swapSlots(InventoryUtil.screenSlot(plate), InventoryUtil.armorScreenSlot(EquipmentSlot.CHEST));
			swapped = false;
		}
	}

	/** The chestplate with the most armour, or -1. */
	private int best() {
		int best = -1;
		double most = -1;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = mc.player.getInventory().getItem(slot);
			if (!stack.is(ItemTags.CHEST_ARMOR) || stack.is(Items.ELYTRA)) continue;
			double score = stack.getMaxDamage() + (stack.isEnchanted() ? 1000 : 0);
			if (score > most) {
				most = score;
				best = slot;
			}
		}
		return best;
	}
}
