package dev.femboyware.module.utility;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.combat.AutoTotemModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.InventoryUtil;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Refills hotbar and offhand stacks from your inventory as they run low.
 * Ported from Meteor Client's AutoReplenish (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoReplenishModule extends Module {
	/** Index into {@link #lastItems} standing for the offhand; 0-8 are the hotbar. */
	private static final int OFFHAND = 9;
	private static final int OFFHAND_SCREEN_SLOT = 45;

	private final NumberSetting minCount = add(new NumberSetting("Min Count", "Refill a stack once it drops to this many", 8, 1, 63, 1));
	private final NumberSetting tickDelay = add(new NumberSetting("Delay", "Ticks between refills", 1, 0, 20, 1));
	private final BoolSetting offhand = add(new BoolSetting("Offhand", "Refill the offhand too, unless Auto Totem is on", true));
	private final BoolSetting unstackable = add(new BoolSetting("Unstackable", "Replace unstackable items like tools when they break or run out", true));
	private final BoolSetting sameEnchants = add(new BoolSetting("Same Enchants", "Only replace unstackables with ones that have the same enchantments", true)).visibleWhen(unstackable::get);
	private final BoolSetting searchHotbar = add(new BoolSetting("Search Hotbar", "Take from other hotbar slots as a last resort", false));
	private final StringSetting excluded = add(new StringSetting("Excluded", "Comma separated item ids never to refill", ""));

	/** What each slot held last tick, so a stack that vanished in one tick can still be refilled. */
	private final ItemStack[] lastItems = new ItemStack[10];
	private Set<Item> excludedItems = Set.of();
	private String parsedFrom;
	private boolean hadScreen;
	private int delayLeft;

	public AutoReplenishModule() {
		super("Auto Replenish", "Refills your hotbar from your inventory", Category.EQUIP);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) return;
		snapshot();
		delayLeft = tickDelay.getInt();
		hadScreen = Mc.screen() != null;
	}

	@Override
	public void onTick() {
		if (lastItems[0] == null) snapshot();
		if (!excluded.get().equals(parsedFrom)) {
			excludedItems = parseItems(excluded.get());
			parsedFrom = excluded.get();
		}
		// Whatever changed while a screen was open was the player's doing
		if (Mc.screen() == null && hadScreen) snapshot();
		hadScreen = Mc.screen() != null;
		if (Mc.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu) return;
		if (delayLeft-- > 0) return;

		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) check(i, mc.player.getInventory().getItem(i));
		if (offhand.get() && !FemboyWare.isEnabled(AutoTotemModule.class)) check(OFFHAND, mc.player.getOffhandItem());
		delayLeft = tickDelay.getInt();
	}

	private void check(int slot, ItemStack stack) {
		ItemStack previous = lastItems[slot];
		lastItems[slot] = stack.copy();
		if (excludedItems.contains(stack.getItem()) || excludedItems.contains(previous.getItem())) return;

		int from = -1;
		// Still some left, but it just dropped to the threshold
		if (stack.isStackable() && !stack.isEmpty() && stack.getCount() <= minCount.getInt()) {
			from = find(stack, slot, minCount.getInt() - stack.getCount() + 1, true);
		}
		// Emptied in a single tick, which fast placing modules can do
		if (previous.isStackable() && stack.isEmpty() && !previous.isEmpty()) {
			from = find(previous, slot, minCount.getInt() + 1, false);
		}
		if (unstackable.get() && !previous.isStackable() && stack.isEmpty() && !previous.isEmpty()) {
			from = find(previous, slot, 1, false);
		}
		if (from == -1 || from == mc.player.getInventory().getSelectedSlot()) return;
		// Don't shuffle stacks back and forth between hotbar slots
		if (from < Inventory.SELECTION_SIZE && from < slot && slot != mc.player.getInventory().getSelectedSlot() && slot != OFFHAND) return;

		int target = slot == OFFHAND ? OFFHAND_SCREEN_SLOT : InventoryUtil.screenSlot(slot);
		InventoryUtil.swapSlots(InventoryUtil.screenSlot(from), target);
	}

	/** Fullest matching stack, stopping early at one with {@code enough} items. */
	private int find(ItemStack lookFor, int excludedSlot, int enough, boolean mustCombine) {
		int best = -1;
		int count = 0;
		int last = searchHotbar.get() ? 0 : Inventory.SELECTION_SIZE;
		for (int i = Inventory.INVENTORY_SIZE - 1; i >= last; i--) {
			if (i == excludedSlot) continue;
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (stack.getItem() != lookFor.getItem()) continue;
			if (mustCombine && !ItemStack.isSameItemSameComponents(lookFor, stack)) continue;
			if (sameEnchants.get() && !stack.getEnchantments().equals(lookFor.getEnchantments())) continue;
			if (stack.getCount() > count) {
				best = i;
				count = stack.getCount();
				if (count >= enough) break;
			}
		}
		return best;
	}

	private void snapshot() {
		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) lastItems[i] = mc.player.getInventory().getItem(i).copy();
		lastItems[OFFHAND] = mc.player.getOffhandItem().copy();
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
