package dev.femboyware.util;

import dev.femboyware.mixin.ClientPlayerInteractionManagerAccessor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

public final class InventoryUtil {
	private static final MinecraftClient mc = MinecraftClient.getInstance();

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
		PlayerInventory inventory = mc.player.getInventory();
		for (int slot = 0; slot < PlayerInventory.HOTBAR_SIZE; slot++) {
			if (predicate.test(inventory.getStack(slot))) return slot;
		}
		return -1;
	}

	/** First main inventory slot (0-35, hotbar included) whose stack matches, or -1. */
	public static int findInventory(Predicate<ItemStack> predicate) {
		PlayerInventory inventory = mc.player.getInventory();
		for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
			if (predicate.test(inventory.getStack(slot))) return slot;
		}
		return -1;
	}

	/** Slot to hand to {@link #withSlot}: the whole inventory for {@link SwitchMode#ALT}, the hotbar otherwise. */
	public static int find(Predicate<ItemStack> predicate, SwitchMode mode) {
		return mode == SwitchMode.ALT ? findInventory(predicate) : findHotbar(predicate);
	}

	/** Like {@link #findInventory} but skips the hotbar. */
	public static int findMain(Predicate<ItemStack> predicate) {
		PlayerInventory inventory = mc.player.getInventory();
		for (int slot = PlayerInventory.HOTBAR_SIZE; slot < PlayerInventory.MAIN_SIZE; slot++) {
			if (predicate.test(inventory.getStack(slot))) return slot;
		}
		return -1;
	}

	public static int count(Predicate<ItemStack> predicate) {
		PlayerInventory inventory = mc.player.getInventory();
		int total = 0;
		for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (predicate.test(stack)) total += stack.getCount();
		}
		if (predicate.test(mc.player.getOffHandStack())) total += mc.player.getOffHandStack().getCount();
		return total;
	}

	public static int getEnchantmentLevel(ItemStack stack, RegistryKey<Enchantment> enchantment) {
		for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : stack.getEnchantments().getEnchantmentEntries()) {
			if (entry.getKey().matchesKey(enchantment)) return entry.getIntValue();
		}
		return 0;
	}

	/** Player screen handler slot id of a main inventory index. */
	public static int screenSlot(int inventorySlot) {
		return inventorySlot < PlayerInventory.HOTBAR_SIZE ? inventorySlot + 36 : inventorySlot;
	}

	/** Player screen handler slot id of an armor slot. */
	public static int armorScreenSlot(EquipmentSlot slot) {
		return 8 - slot.getEntitySlotId();
	}

	/** Swaps a main inventory slot with the offhand in one click. */
	public static void swapWithOffhand(int inventorySlot) {
		click(screenSlot(inventorySlot), 40, SlotActionType.SWAP);
	}

	/** Swaps a main inventory slot with a hotbar slot in one click. */
	public static void swapWithHotbar(int inventorySlot, int hotbarSlot) {
		click(screenSlot(inventorySlot), hotbarSlot, SlotActionType.SWAP);
	}

	/** Swaps two arbitrary player screen handler slots with three pickup clicks. */
	public static void swapSlots(int screenSlotA, int screenSlotB) {
		click(screenSlotA, 0, SlotActionType.PICKUP);
		click(screenSlotB, 0, SlotActionType.PICKUP);
		click(screenSlotA, 0, SlotActionType.PICKUP);
	}

	public static void click(int screenSlot, int button, SlotActionType action) {
		mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot, button, action, mc.player);
	}

	public static void select(int slot) {
		mc.player.getInventory().setSelectedSlot(slot);
		((ClientPlayerInteractionManagerAccessor) mc.interactionManager).femboyware$syncSelectedSlot();
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
			if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return false;
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
