package dev.femboyware.module.combat;

import dev.femboyware.util.Mc;
import dev.femboyware.mixin.HandledScreenAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import org.lwjgl.glfw.GLFW;

import java.util.Random;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

/**
 * Refills your offhand totem like a person would: opens your inventory, moves the mouse to a
 * totem and picks it up, moves to the offhand slot and puts it down, then closes the inventory.
 * Real inventory clicks, at a human pace with uneven timing.
 */
public class LegitTotemModule extends Module {
	private enum Step { OPEN, PICK_UP, PUT_DOWN, PUT_BACK, CLOSE }

	/** The offhand's slot in the player inventory screen. */
	private static final int OFFHAND_SLOT = 45;

	private final NumberSetting openDelay = add(new NumberSetting("Open Delay", "Ticks after a pop before the inventory opens", 2, 0, 10, 1));
	private final NumberSetting clickDelay = add(new NumberSetting("Click Delay", "Ticks between each mouse move and click", 3, 1, 10, 1));
	private final NumberSetting randomDelay = add(new NumberSetting("Random Delay", "Up to this many extra ticks on every step, a different amount each time", 2, 0, 6, 1));
	private final BoolSetting moveMouse = add(new BoolSetting("Move Mouse", "Visibly move your cursor onto each slot, like dragging by hand", true));
	private final BoolSetting close = add(new BoolSetting("Close Inventory", "Close the inventory afterwards", true));

	private final Random random = new Random();
	private Step step;
	private int wait;
	/** Screen slot the totem was taken from, where the old offhand item goes back. */
	private int totemSlot;
	private InventoryScreen screen;

	public LegitTotemModule() {
		super("Legit Totem", "Opens your inventory and drags a totem into your offhand", Category.LEGIT);
	}

	@Override
	public String getInfo() {
		return Integer.toString(InventoryUtil.count(stack -> stack.is(Items.TOTEM_OF_UNDYING)));
	}

	@Override
	protected void onDisable() {
		step = null;
	}

	@Override
	public void onTick() {
		if (step == null) {
			if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || Mc.screen() != null) return;
			int slot = InventoryUtil.findMain(stack -> stack.is(Items.TOTEM_OF_UNDYING));
			if (slot < 0) return;
			totemSlot = InventoryUtil.screenSlot(slot);
			step = Step.OPEN;
			wait = openDelay.getInt() + jitter();
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		// Stop if something else took over the screen, like the player closing it
		if (step != Step.OPEN && Mc.screen() != screen) {
			step = null;
			return;
		}
		switch (step) {
			case OPEN -> {
				screen = new InventoryScreen(mc.player);
				Mc.setScreen(screen);
				next(Step.PICK_UP);
			}
			case PICK_UP -> {
				if (!mc.player.inventoryMenu.getSlot(totemSlot).getItem().is(Items.TOTEM_OF_UNDYING)) {
					step = null;
					return;
				}
				hover(totemSlot);
				InventoryUtil.click(totemSlot, 0, ClickType.PICKUP);
				next(Step.PUT_DOWN);
			}
			case PUT_DOWN -> {
				hover(OFFHAND_SLOT);
				InventoryUtil.click(OFFHAND_SLOT, 0, ClickType.PICKUP);
				// Whatever was in the offhand is now on the cursor and goes where the totem was
				next(mc.player.inventoryMenu.getCarried().isEmpty() ? Step.CLOSE : Step.PUT_BACK);
			}
			case PUT_BACK -> {
				hover(totemSlot);
				InventoryUtil.click(totemSlot, 0, ClickType.PICKUP);
				next(Step.CLOSE);
			}
			case CLOSE -> {
				if (close.get()) mc.player.closeContainer();
				step = null;
				screen = null;
			}
		}
	}

	private void next(Step following) {
		step = following;
		wait = clickDelay.getInt() + jitter();
	}

	/** A random 0 to Random Delay extra ticks, so no two refills take the same time. */
	private int jitter() {
		return random.nextInt(randomDelay.getInt() + 1);
	}

	/** Puts the real mouse cursor over {@code slotId}, so you can watch it drag the totem across. */
	private void hover(int slotId) {
		if (!moveMouse.get() || screen == null) return;
		Slot slot = mc.player.inventoryMenu.getSlot(slotId);
		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		double scale = mc.getWindow().getGuiScale();
		double x = (accessor.femboyware$getX() + slot.x + 8) * scale;
		double y = (accessor.femboyware$getY() + slot.y + 8) * scale;
		GLFW.glfwSetCursorPos(mc.getWindow().handle(), x, y);
	}
}
