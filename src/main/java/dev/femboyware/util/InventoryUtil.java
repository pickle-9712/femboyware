package dev.femboyware.util;

import dev.femboyware.mixin.ClientPlayerInteractionManagerAccessor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

public final class InventoryUtil {
	private static final Minecraft mc = Minecraft.getInstance();

	public enum SwitchMode {
		NONE,
		/** Switch and stay on the new slot. */
		NORMAL,
		/** Switch, act, and switch back in the same tick. */
		SILENT,
		/**
		 * Swap the item into the held slot with an inventory click and back. The selected slot never
		 * changes, which gets past hotbar switch checks, and the item can come from anywhere in the
		 * inventory.
		 */
		ALT
	}

	private InventoryUtil() {
	}

	/** First hotbar slot whose stack matches, or -1. */
	public static int findHotbar(Predicate<ItemStack> predicate) {
		Inventory inventory = mc.player.getInventory();
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (predicate.test(inventory.getItem(slot))) return slot;
		}
		return -1;
	}

	/** First main inventory slot (0-35, hotbar included) whose stack matches, or -1. */
	public static int findInventory(Predicate<ItemStack> predicate) {
		Inventory inventory = mc.player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (predicate.test(inventory.getItem(slot))) return slot;
		}
		return -1;
	}

	/** Slot to hand to {@link #withSlot}: the whole inventory for {@link SwitchMode#ALT}, the hotbar otherwise. */
	public static int find(Predicate<ItemStack> predicate, SwitchMode mode) {
		return mode == SwitchMode.ALT ? findInventory(predicate) : findHotbar(predicate);
	}

	/** Like {@link #findInventory} but skips the hotbar. */
	public static int findMain(Predicate<ItemStack> predicate) {
		Inventory inventory = mc.player.getInventory();
		for (int slot = Inventory.SELECTION_SIZE; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (predicate.test(inventory.getItem(slot))) return slot;
		}
		return -1;
	}

	public static int count(Predicate<ItemStack> predicate) {
		Inventory inventory = mc.player.getInventory();
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (predicate.test(stack)) total += stack.getCount();
		}
		if (predicate.test(mc.player.getOffhandItem())) total += mc.player.getOffhandItem().getCount();
		return total;
	}

	public static int getEnchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
			if (entry.getKey().is(enchantment)) return entry.getIntValue();
		}
		return 0;
	}

	/** Player screen handler slot id of a main inventory index. */
	public static int screenSlot(int inventorySlot) {
		return inventorySlot < Inventory.SELECTION_SIZE ? inventorySlot + 36 : inventorySlot;
	}

	/** Player screen handler slot id of an armor slot. */
	public static int armorScreenSlot(EquipmentSlot slot) {
		return 8 - slot.getIndex();
	}

	/** Swaps a main inventory slot with the offhand in one click. */
	public static void swapWithOffhand(int inventorySlot) {
		click(screenSlot(inventorySlot), 40, ClickType.SWAP);
	}

	/** Swaps a main inventory slot with a hotbar slot in one click. */
	public static void swapWithHotbar(int inventorySlot, int hotbarSlot) {
		click(screenSlot(inventorySlot), hotbarSlot, ClickType.SWAP);
	}

	/** Swaps two arbitrary player screen handler slots with three pickup clicks. */
	public static void swapSlots(int screenSlotA, int screenSlotB) {
		click(screenSlotA, 0, ClickType.PICKUP);
		click(screenSlotB, 0, ClickType.PICKUP);
		click(screenSlotA, 0, ClickType.PICKUP);
	}

	public static void click(int screenSlot, int button, ClickType action) {
		mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, screenSlot, button, action, mc.player);
	}

	public static void select(int slot) {
		mc.player.getInventory().setSelectedSlot(slot);
		((ClientPlayerInteractionManagerAccessor) mc.gameMode).femboyware$syncSelectedSlot();
	}

	/**
	 * Runs {@code action} while holding {@code slot}. Returns false without running it if the switch
	 * mode does not allow changing to a different slot.
	 */
	public static boolean withSlot(int slot, SwitchMode mode, Runnable action) {
		int previous = mc.player.getInventory().getSelectedSlot();
		if (slot == previous) {
			action.run();
			return true;
		}
		if (slot < 0 || mode == SwitchMode.NONE) return false;
		if (mode == SwitchMode.ALT) {
			// Clicks go to the player inventory, which would desync any other open container
			if (mc.player.containerMenu != mc.player.inventoryMenu) return false;
			swapWithHotbar(slot, previous);
			action.run();
			swapWithHotbar(slot, previous);
			return true;
		}
		select(slot);
		action.run();
		if (mode == SwitchMode.SILENT) select(previous);
		return true;
	}
}
