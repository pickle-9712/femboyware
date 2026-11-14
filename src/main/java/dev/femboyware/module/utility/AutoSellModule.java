package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.Mc;
import dev.femboyware.util.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * DonutSMP's /sell, on its own: every so often (or when your inventory fills up) it runs /sell,
 * shift-clicks the items you listed into the sell menu and closes it to sell them.
 */
public class AutoSellModule extends Module {
	private final StringSetting items = add(new StringSetting("Items", "Item ids to sell, comma separated (like cobblestone, raw_iron, bone)", "cobblestone, cobbled_deepslate, bone, rotten_flesh"));
	private final StringSetting command = add(new StringSetting("Command", "The command that opens the sell menu, without the /", "sell"));
	private final BoolSetting whenFull = add(new BoolSetting("When Full", "Only sell once your inventory is full", true));
	private final NumberSetting interval = add(new NumberSetting("Interval", "Seconds between sells", 30, 3, 600, 1));
	private final NumberSetting perTick = add(new NumberSetting("Per Tick", "Stacks moved into the menu each tick", 2, 1, 9, 1));

	private enum State { IDLE, WAITING, MOVING }

	private State state = State.IDLE;
	private final Timer timer = new Timer();
	private long waitingSince;

	public AutoSellModule() {
		super("Auto Sell", "Sells chosen items with /sell when your inventory fills", Category.AUTO);
	}

	@Override
	protected void onDisable() {
		state = State.IDLE;
	}

	private List<String> wanted() {
		List<String> ids = new ArrayList<>();
		for (String id : items.get().split(",")) {
			id = id.trim().toLowerCase(Locale.ROOT);
			if (id.startsWith("minecraft:")) id = id.substring(10);
			if (!id.isEmpty()) ids.add(id);
		}
		return ids;
	}

	private boolean sells(ItemStack stack, List<String> ids) {
		return !stack.isEmpty() && ids.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
	}

	@Override
	public void onTick() {
		List<String> ids = wanted();
		switch (state) {
			case IDLE -> {
				if (!timer.passed(interval.get() * 1000) || Mc.screen() != null) return;
				Inventory inv = mc.player.getInventory();
				boolean any = false, full = true;
				for (int slot = 0; slot < 36; slot++) {
					ItemStack stack = inv.getItem(slot);
					if (stack.isEmpty()) full = false;
					if (sells(stack, ids)) any = true;
				}
				if (!any || whenFull.get() && !full) return;
				mc.getConnection().sendCommand(command.get().replaceFirst("^/", ""));
				state = State.WAITING;
				waitingSince = System.currentTimeMillis();
				timer.reset();
			}
			case WAITING -> {
				if (Mc.screen() instanceof AbstractContainerScreen<?> && mc.player.containerMenu != mc.player.inventoryMenu) state = State.MOVING;
				else if (System.currentTimeMillis() - waitingSince > 4000) state = State.IDLE;
			}
			case MOVING -> {
				var menu = mc.player.containerMenu;
				if (menu == mc.player.inventoryMenu) {
					state = State.IDLE;
					return;
				}
				int moved = 0;
				for (Slot slot : menu.slots) {
					if (!(slot.container instanceof Inventory) || !sells(slot.getItem(), ids)) continue;
					mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
					if (++moved >= perTick.getInt()) return;
				}
				// Nothing left to put in: closing the menu sells it all
				mc.player.closeContainer();
				state = State.IDLE;
			}
		}
	}
}
