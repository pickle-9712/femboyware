package dev.femboyware.module.larp;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The phone and the Ledger as items in your inventory. They're made up on your side only: paper
 * dressed with our own item model and name, put in a free slot. The server never hears of them, so
 * anything that refreshes your inventory from the server (dying, some menus) can take them away;
 * just get them again.
 */
public final class LarpItems {
	public static final Identifier PHONE = Identifier.fromNamespaceAndPath("femboyware", "phone");
	public static final Identifier LEDGER = Identifier.fromNamespaceAndPath("femboyware", "ledger");

	private LarpItems() {
	}

	private static ItemStack make(Identifier model, String name, int color) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, model);
		stack.set(DataComponents.ITEM_NAME, Component.literal(name).withStyle(Style.EMPTY.withColor(color)));
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		return stack;
	}

	public static ItemStack phone() {
		return make(PHONE, "Phone", 0xFFFFFF);
	}

	public static ItemStack ledger() {
		return make(LEDGER, "Ledger Nano X", 0xBBB0FF);
	}

	/** Puts the item in a free slot. False if your inventory's full. */
	public static boolean give(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.getInventory().add(stack);
	}

	/** Whether you're holding one of ours in your main hand. */
	public static boolean holding(Identifier model) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		ItemStack stack = mc.player.getMainHandItem();
		return !stack.isEmpty() && model.equals(stack.get(DataComponents.ITEM_MODEL));
	}

	/** Ticks since Server Larp last stopped, so the server's inventory has a moment to come back first. */
	private static int sinceLarp = 1000;

	/**
	 * Opens the phone or the wallet when you right-click with one, and while you're not larping puts
	 * back any you own that the server's inventory wiped. Called every tick from the module manager.
	 */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || dev.femboyware.FemboyWare.getModules() == null) return;
		PhoneModule phone = dev.femboyware.FemboyWare.getModules().get(PhoneModule.class);
		LedgerModule ledger = dev.femboyware.FemboyWare.getModules().get(LedgerModule.class);
		if (usedThisTick(PHONE)) phone.open();
		else if (usedThisTick(LEDGER)) ledger.open();

		// While larping they're real items in the larp inventory, moved about freely, so leave them be
		if (larping()) {
			sinceLarp = 0;
			return;
		}
		if (++sinceLarp < 40 || mc.player.tickCount % 20 != 0 || mc.player.isDeadOrDying()) return;
		if (phone.ownsItem() && !has(PHONE)) give(phone());
		if (ledger.ownsItem() && !has(LEDGER)) give(ledger());
	}

	/** Whether Server Larp is running, so items given are real items in its inventory. */
	public static boolean larping() {
		return dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.utility.ServerLarpModule.class).isLarping();
	}

	/** Whether one of ours is anywhere in your inventory. */
	public static boolean has(Identifier model) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		var inventory = mc.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (model.equals(inventory.getItem(i).get(DataComponents.ITEM_MODEL))) return true;
		}
		return false;
	}

	/** Takes ours out of your inventory. */
	public static void remove(Identifier model) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		var inventory = mc.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (model.equals(inventory.getItem(i).get(DataComponents.ITEM_MODEL))) inventory.setItem(i, ItemStack.EMPTY);
		}
	}

	/**
	 * True if you right-clicked while holding the item. The click is used up, so the game never
	 * sends it to the server. Called at the start of the client tick, before the game reads clicks.
	 */
	public static boolean usedThisTick(Identifier model) {
		Minecraft mc = Minecraft.getInstance();
		if (dev.femboyware.util.Mc.screen() != null || !holding(model)) return false;
		boolean used = false;
		while (mc.options.keyUse.consumeClick()) used = true;
		return used;
	}
}
