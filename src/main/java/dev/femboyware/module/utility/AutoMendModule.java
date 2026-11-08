package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.InventoryUtil;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Cycles damaged Mending items through your offhand so XP repairs them one after another.
 * Ported from Meteor Client's AutoMend (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoMendModule extends Module {
	/** Player screen handler slot id of the offhand. */
	private static final int OFFHAND_SCREEN_SLOT = 45;

	private final StringSetting blacklist = add(new StringSetting("Blacklist", "Comma separated item ids never to mend", ""));
	private final BoolSetting force = add(new BoolSetting("Force", "Replace whatever is in your offhand, not just mended items", false));
	private final BoolSetting autoDisable = add(new BoolSetting("Auto Disable", "Turn off once everything is repaired", true));

	private Set<Item> blacklisted = Set.of();
	private String parsedFrom;
	private boolean didMove;

	public AutoMendModule() {
		super("Auto Mend", "Puts damaged Mending items in your offhand one by one", Category.EQUIP);
	}

	@Override
	protected void onEnable() {
		didMove = false;
	}

	@Override
	public void onTick() {
		if (!blacklist.get().equals(parsedFrom)) {
			blacklisted = parseItems(blacklist.get());
			parsedFrom = blacklist.get();
		}
		if (mc.player.containerMenu != mc.player.inventoryMenu || shouldWait()) return;

		int slot = findDamaged();
		if (slot != -1) {
			InventoryUtil.swapWithOffhand(slot);
			didMove = true;
			return;
		}
		if (!autoDisable.get()) return;
		if (didMove) {
			int empty = InventoryUtil.findInventory(ItemStack::isEmpty);
			if (empty != -1) InventoryUtil.swapSlots(OFFHAND_SCREEN_SLOT, InventoryUtil.screenSlot(empty));
		}
		setEnabled(false);
	}

	/** Wait while the offhand holds a Mending item still being repaired, or anything else unless forced. */
	private boolean shouldWait() {
		ItemStack offhand = mc.player.getOffhandItem();
		if (offhand.isEmpty()) return false;
		if (InventoryUtil.getEnchantmentLevel(offhand, Enchantments.MENDING) > 0) return offhand.getDamageValue() != 0;
		return !force.get();
	}

	private int findDamaged() {
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (blacklisted.contains(stack.getItem())) continue;
			if (InventoryUtil.getEnchantmentLevel(stack, Enchantments.MENDING) > 0 && stack.getDamageValue() > 0) return i;
		}
		return -1;
	}

	private static Set<Item> parseItems(String text) {
		Set<Item> result = new HashSet<>();
		for (String part : text.split(",")) {
			if (part.isBlank()) continue;
			Identifier id = Identifier.tryParse(part.trim().toLowerCase());
			if (id != null) BuiltInRegistries.ITEM.getOptional(id).ifPresent(result::add);
		}
		return result;
	}
}
