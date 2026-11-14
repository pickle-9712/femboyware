package dev.femboyware.module.combat;

import dev.femboyware.mixin.HandledScreenAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Mc;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

/**
 * With an inventory open, a totem you hover over goes straight into your offhand (or a hotbar
 * slot of your choice), the same swap pressing F over it does.
 */
public class HoverTotemModule extends Module {
	private final BoolSetting offhand = add(new BoolSetting("Offhand", "Move hovered totems into your offhand", true));
	private final BoolSetting hotbar = add(new BoolSetting("Hotbar", "Also keep a totem in a hotbar slot", false));
	private final NumberSetting hotbarSlot = add(new NumberSetting("Hotbar Slot", "Which hotbar slot, 1 to 9", 9, 1, 9, 1)).visibleWhen(hotbar::get);
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds to hover before it moves", 60, 0, 500, 10));

	private Slot hovered;
	private long hoveredSince;
	private long lastMove;

	public HoverTotemModule() {
		super("Hover Totem", "Move hovered totems into your offhand or chosen hotbar slot", Category.EQUIP);
	}

	@Override
	public void onFrame() {
		if (!(Mc.screen() instanceof AbstractContainerScreen<?> screen)) {
			hovered = null;
			return;
		}
		Slot slot = ((HandledScreenAccessor) screen).femboyware$getHoveredSlot();
		long now = System.currentTimeMillis();
		if (slot != hovered) {
			hovered = slot;
			hoveredSince = now;
		}
		if (slot == null || !slot.hasItem() || !slot.getItem().is(Items.TOTEM_OF_UNDYING) || !screen.getMenu().getCarried().isEmpty()) return;
		if (now - hoveredSince < delay.getInt() || now - lastMove < Math.max(50, delay.getInt())) return;

		Inventory inventory = mc.player.getInventory();
		int button = -1;
		if (offhand.get() && !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) button = 40;
		else if (hotbar.get()) {
			int index = hotbarSlot.getInt() - 1;
			// Not if it's that very slot, or that slot already has one
			boolean same = slot.container == inventory && slot.getContainerSlot() == index;
			if (!same && !inventory.getItem(index).is(Items.TOTEM_OF_UNDYING)) button = index;
		}
		if (button < 0) return;
		if (button == 40 && slot.container == inventory && slot.getContainerSlot() == Inventory.SLOT_OFFHAND) return;
		mc.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot.index, button, ClickType.SWAP, mc.player);
		lastMove = now;
	}
}
