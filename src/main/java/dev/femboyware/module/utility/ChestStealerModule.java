package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.LocalMenu;
import dev.femboyware.util.Mc;
import dev.femboyware.util.Timer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Empties chests, barrels and shulker boxes into your inventory as soon as you open them, one
 * shift-click at a time, then closes them. Leaves server menus alone (anything with a custom name,
 * if you like).
 */
public class ChestStealerModule extends Module {
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between items", 60, 0, 500, 10));
	private final BoolSetting close = add(new BoolSetting("Close", "Close the chest when it's empty or your inventory's full", true));
	private final BoolSetting skipNamed = add(new BoolSetting("Skip Named", "Leave chests with a custom title alone (most server menus have one)", true));

	private final Timer timer = new Timer();

	public ChestStealerModule() {
		super("ChestStealer", "Loot chests automatically", Category.AUTO);
	}

	@Override
	public void onTick() {
		if (mc.player == null || !(Mc.screen() instanceof AbstractContainerScreen<?> screen)) return;
		AbstractContainerMenu menu = mc.player.containerMenu;
		if (menu instanceof LocalMenu || !(menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu)) return;
		if (skipNamed.get() && !isVanillaTitle(screen.getTitle().getString())) return;
		int containerSlots = menu.slots.size() - 36;
		boolean full = mc.player.getInventory().getFreeSlot() < 0;
		for (int i = 0; i < containerSlots; i++) {
			Slot slot = menu.slots.get(i);
			if (!slot.hasItem()) continue;
			if (full) break;
			if (!timer.passed(delay.get())) return;
			mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
			timer.reset();
			if (delay.get() > 0) return;
		}
		if (close.get() && timer.passed(delay.get())) mc.player.closeContainer();
	}

	/** The game's own names for these, in English; a server's menu has its own title. */
	private static boolean isVanillaTitle(String title) {
		return switch (title) {
			case "Chest", "Large Chest", "Barrel", "Shulker Box", "Ender Chest", "Trapped Chest", "Minecart with Chest", "Boat with Chest" -> true;
			default -> title.endsWith("Shulker Box");
		};
	}
}
