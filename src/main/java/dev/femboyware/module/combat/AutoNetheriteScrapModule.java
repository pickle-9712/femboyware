package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Timer;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * While a furnace or blast furnace is open, loads ancient debris and fuel and collects the
 * netherite scrap, so you can stock up on scrap between fights.
 */
public class AutoNetheriteScrapModule extends Module {
	private static final int INPUT = 0;
	private static final int FUEL = 1;
	private static final int OUTPUT = 2;
	private static final int PLAYER_START = 3;

	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between clicks", 100, 0, 500, 10));
	private final BoolSetting loadFuel = add(new BoolSetting("Load Fuel", "Also move fuel into the furnace", true));
	private final BoolSetting autoClose = add(new BoolSetting("Auto Close", "Close the furnace once you're out of debris and it's empty", false));

	private final Timer timer = new Timer();

	public AutoNetheriteScrapModule() {
		super("Auto Netherite Scrap", "Smelts ancient debris into netherite scrap in open furnaces", Category.AUTO);
	}

	@Override
	public void onTick() {
		if (!(mc.player.containerMenu instanceof AbstractFurnaceMenu handler) || !timer.passed(delay.get())) return;
		int size = handler.slots.size();

		if (handler.getSlot(OUTPUT).getItem().is(Items.NETHERITE_SCRAP)) {
			click(handler, OUTPUT);
			return;
		}
		ItemStack input = handler.getSlot(INPUT).getItem();
		if (input.isEmpty() || input.is(Items.ANCIENT_DEBRIS) && input.getCount() < input.getMaxStackSize()) {
			int debris = findPlayerSlot(handler, size, stack -> stack.is(Items.ANCIENT_DEBRIS));
			if (debris >= 0) {
				click(handler, debris);
				return;
			}
		}
		if (loadFuel.get() && handler.getSlot(FUEL).getItem().isEmpty()) {
			//? if >=26.3 {
			/*int fuel = findPlayerSlot(handler, size, stack -> !stack.is(Items.ANCIENT_DEBRIS) && stack.has(net.minecraft.core.component.DataComponents.COOKING_FUEL));
			*///?} else {
			int fuel = findPlayerSlot(handler, size, stack -> !stack.is(Items.ANCIENT_DEBRIS) && mc.level.fuelValues().isFuel(stack));
			//?}
			if (fuel >= 0) {
				click(handler, fuel);
				return;
			}
		}
		if (autoClose.get() && input.isEmpty() && handler.getSlot(OUTPUT).getItem().isEmpty()
			&& findPlayerSlot(handler, size, stack -> stack.is(Items.ANCIENT_DEBRIS)) < 0) {
			mc.player.closeContainer();
		}
	}

	private static int findPlayerSlot(AbstractFurnaceMenu handler, int size, java.util.function.Predicate<ItemStack> predicate) {
		for (int i = PLAYER_START; i < size; i++) {
			if (predicate.test(handler.getSlot(i).getItem())) return i;
		}
		return -1;
	}

	/** Shift-clicks a slot; the furnace sorts debris into the input and fuel into the fuel slot itself. */
	private void click(AbstractFurnaceMenu handler, int slot) {
		mc.gameMode.handleInventoryMouseClick(handler.containerId, slot, 0, ClickType.QUICK_MOVE, mc.player);
		timer.reset();
	}
}
