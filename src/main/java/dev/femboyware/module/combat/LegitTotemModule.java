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
	private final BoolSetting glide = add(new BoolSetting("Glide Mouse", "Slide the cursor across to each slot instead of jumping, and only click once it's there", true));
	private final NumberSetting mouseSpeed = add(new NumberSetting("Mouse Speed", "How fast the cursor glides to a slot", 14, 3, 40, 1));
	private final NumberSetting spread = add(new NumberSetting("Slot Spread", "Land up to this many pixels off the slot's middle, a different spot each time", 4, 0, 7, 1));
	private final BoolSetting healthCheck = add(new BoolSetting("Health Check", "Only refill when you're low, so a pop at full health doesn't open your inventory mid-fight", false));
	private final NumberSetting health = add(new NumberSetting("Health", "Refill at or below this much health (hearts x2, absorption counts)", 12, 1, 36, 1));
	private final BoolSetting onlyPop = add(new BoolSetting("Only After Pop", "Only refill after a totem pops, not when the offhand is empty for another reason", false));

	private final Random random = new Random();
	private Step step;
	private int wait;
	/** Screen slot the totem was taken from, where the old offhand item goes back. */
	private int totemSlot;
	private InventoryScreen screen;
	/** Where the cursor is gliding to, in window pixels, and whether this step's cursor move has started. */
	private double targetX, targetY, cursorX, cursorY;
	private boolean aimed, gliding;
	private long lastFrame;
	private boolean hadTotem, poppedSince;

	public LegitTotemModule() {
		super("Legit Totem", "Opens your inventory and drags a totem into your offhand", Category.LEGIT);
		mouseSpeed.visibleWhen(glide::get);
		health.visibleWhen(healthCheck::get);
	}

	@Override
	public String getInfo() {
		return Integer.toString(InventoryUtil.count(stack -> stack.is(Items.TOTEM_OF_UNDYING)));
	}

	@Override
	protected void onDisable() {
		step = null;
		gliding = false;
	}

	@Override
	public void onTick() {
		boolean holdingTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
		boolean popped = hadTotem && !holdingTotem;
		hadTotem = holdingTotem;
		if (popped) poppedSince = true;
		if (step == null) {
			if (holdingTotem || Mc.screen() != null) return;
			if (onlyPop.get() && !poppedSince) return;
			if (healthCheck.get() && mc.player.getHealth() + mc.player.getAbsorptionAmount() > health.get()) return;
			int slot = InventoryUtil.findMain(stack -> stack.is(Items.TOTEM_OF_UNDYING));
			if (slot < 0) return;
			totemSlot = InventoryUtil.screenSlot(slot);
			poppedSince = false;
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
				if (!hover(totemSlot)) return;
				InventoryUtil.click(totemSlot, 0, ClickType.PICKUP);
				next(Step.PUT_DOWN);
			}
			case PUT_DOWN -> {
				if (!hover(OFFHAND_SLOT)) return;
				InventoryUtil.click(OFFHAND_SLOT, 0, ClickType.PICKUP);
				// Whatever was in the offhand is now on the cursor and goes where the totem was
				next(mc.player.inventoryMenu.getCarried().isEmpty() ? Step.CLOSE : Step.PUT_BACK);
			}
			case PUT_BACK -> {
				if (!hover(totemSlot)) return;
				InventoryUtil.click(totemSlot, 0, ClickType.PICKUP);
				next(Step.CLOSE);
			}
			case CLOSE -> {
				if (close.get()) mc.player.closeContainer();
				step = null;
				screen = null;
				gliding = false;
			}
		}
	}

	private void next(Step following) {
		step = following;
		aimed = false;
		gliding = false;
		wait = clickDelay.getInt() + jitter();
	}

	/** A random 0 to Random Delay extra ticks, so no two refills take the same time. */
	private int jitter() {
		return random.nextInt(randomDelay.getInt() + 1);
	}

	/**
	 * Moves the real cursor onto {@code slotId}, so you can watch it drag the totem across: true once
	 * it's there and the click can go. With Glide Mouse it slides over a few frames to a slightly
	 * different spot in the slot each time.
	 */
	private boolean hover(int slotId) {
		if (!moveMouse.get() || screen == null) return true;
		if (!aimed) {
			aimed = true;
			Slot slot = mc.player.inventoryMenu.getSlot(slotId);
			HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
			double scale = mc.getWindow().getGuiScale();
			int off = spread.getInt();
			double dx = off == 0 ? 0 : random.nextDouble(-off, off), dy = off == 0 ? 0 : random.nextDouble(-off, off);
			targetX = (accessor.femboyware$getX() + slot.x + 8 + dx) * scale;
			targetY = (accessor.femboyware$getY() + slot.y + 8 + dy) * scale;
			if (!glide.get()) {
				GLFW.glfwSetCursorPos(mc.getWindow().handle(), targetX, targetY);
				return true;
			}
			gliding = true;
			lastFrame = 0;
			cursorX = mc.mouseHandler.xpos();
			cursorY = mc.mouseHandler.ypos();
		}
		if (!glide.get()) return true;
		if (Math.hypot(targetX - cursorX, targetY - cursorY) <= 1.5) {
			gliding = false;
			return true;
		}
		return false;
	}

	/** The glide itself: eases the cursor towards the slot every frame, a little uneven like a hand. */
	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (!gliding || seconds <= 0 || screen == null || Mc.screen() != screen) return;
		double follow = (1 - Math.exp(-mouseSpeed.get() * seconds)) * (0.8 + random.nextDouble() * 0.4);
		double nx = cursorX + (targetX - cursorX) * follow, ny = cursorY + (targetY - cursorY) * follow;
		// Close enough: snap the last pixel so it doesn't crawl
		if (Math.hypot(targetX - nx, targetY - ny) < 1) {
			nx = targetX;
			ny = targetY;
		}
		cursorX = nx;
		cursorY = ny;
		GLFW.glfwSetCursorPos(mc.getWindow().handle(), nx, ny);
	}
}
