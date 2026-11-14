package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Timer;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Fills DonutSMP /order requests for you: opens /order for your item, picks the first order for
 * it, drops your stock into the delivery menu, closes it to deliver and presses confirm if asked.
 * It keeps going while you've got the item (less what you keep back).
 */
public class OrderDropperModule extends Module {
	private final StringSetting item = add(new StringSetting("Item", "Item id to deliver (like diamond, spruce_log, bone)", "bone"));
	private final StringSetting command = add(new StringSetting("Command", "The command that opens orders, without the /", "order"));
	private final NumberSetting keep = add(new NumberSetting("Keep", "How many to keep back", 0, 0, 2304, 1));
	private final NumberSetting interval = add(new NumberSetting("Interval", "Seconds between deliveries", 3, 1, 120, 1));
	private final NumberSetting perTick = add(new NumberSetting("Per Tick", "Stacks dropped in each tick", 2, 1, 9, 1));

	private enum State { IDLE, LIST, DELIVER, CONFIRM }

	private State state = State.IDLE;
	private final Timer timer = new Timer();
	private long since;
	private int menuId = -1;

	public OrderDropperModule() {
		super("Order Dropper", "Delivers your items to DonutSMP /order requests", Category.AUTO);
	}

	@Override
	protected void onDisable() {
		state = State.IDLE;
	}

	@Override
	public String getInfo() {
		return id();
	}

	private String id() {
		String id = item.get().trim().toLowerCase(Locale.ROOT);
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	private boolean matches(ItemStack stack) {
		return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals(id());
	}

	private int have() {
		int n = 0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = mc.player.getInventory().getItem(slot);
			if (matches(stack)) n += stack.getCount();
		}
		return n;
	}

	/** A container menu other than your own inventory that's new since {@link #menuId}, or null. */
	private AbstractContainerMenu newMenu() {
		AbstractContainerMenu menu = mc.player.containerMenu;
		return menu != mc.player.inventoryMenu && menu.containerId != menuId ? menu : null;
	}

	private void go(State next) {
		state = next;
		since = System.currentTimeMillis();
		menuId = mc.player.containerMenu.containerId;
	}

	@Override
	public void onTick() {
		long waited = System.currentTimeMillis() - since;
		switch (state) {
			case IDLE -> {
				if (!timer.passed(interval.get() * 1000) || mc.player.containerMenu != mc.player.inventoryMenu) return;
				if (have() <= keep.getInt()) return;
				String name = id().replace('_', ' ');
				mc.getConnection().sendCommand(command.get().replaceFirst("^/", "") + " " + name);
				timer.reset();
				go(State.LIST);
				menuId = -2;
			}
			case LIST -> {
				AbstractContainerMenu menu = mc.player.containerMenu;
				if (menu == mc.player.inventoryMenu) {
					if (waited > 4000) state = State.IDLE;
					return;
				}
				// The first order slot showing our item
				for (Slot slot : menu.slots) {
					if (slot.container instanceof Inventory || !matches(slot.getItem())) continue;
					mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, mc.player);
					go(State.DELIVER);
					return;
				}
				if (waited > 3000) {
					ChatUtil.info("Order Dropper: no orders for " + id().replace('_', ' ') + " right now");
					mc.player.closeContainer();
					state = State.IDLE;
				}
			}
			case DELIVER -> {
				AbstractContainerMenu menu = newMenu();
				if (menu == null) {
					if (waited > 4000) {
						mc.player.closeContainer();
						state = State.IDLE;
					}
					return;
				}
				int moved = 0, left = have() - keep.getInt();
				for (Slot slot : menu.slots) {
					if (left <= 0) break;
					if (!(slot.container instanceof Inventory) || !matches(slot.getItem())) continue;
					if (slot.getItem().getCount() > left) continue;
					left -= slot.getItem().getCount();
					mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
					if (++moved >= perTick.getInt()) return;
				}
				// All in: closing delivers it
				mc.player.closeContainer();
				go(State.CONFIRM);
				menuId = -2;
			}
			case CONFIRM -> {
				AbstractContainerMenu menu = mc.player.containerMenu;
				if (menu != mc.player.inventoryMenu) {
					for (Slot slot : menu.slots) {
						ItemStack stack = slot.getItem();
						if (slot.container instanceof Inventory || stack.isEmpty()) continue;
						String label = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
						if (label.contains("confirm") || BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().startsWith("lime_")) {
							mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, mc.player);
							state = State.IDLE;
							return;
						}
					}
				}
				if (waited > 1500) state = State.IDLE;
			}
		}
	}
}
