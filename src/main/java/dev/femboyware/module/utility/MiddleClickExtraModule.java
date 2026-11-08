package dev.femboyware.module.utility;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Middle click to throw a pearl, drink XP, eat a gapple and so on, straight from the hotbar.
 * Replaces vanilla pick block while on.
 * Ported from Meteor Client's MiddleClickExtra (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class MiddleClickExtraModule extends Module {
	/** Ticks a held use can take to start before we give up on it. */
	private static final int START_TICKS = 4;

	public enum Mode {
		PEARL(Items.ENDER_PEARL, true),
		XP(Items.EXPERIENCE_BOTTLE, true),
		ROCKET(Items.FIREWORK_ROCKET, true),
		WIND_CHARGE(Items.WIND_CHARGE, true),
		BOW(Items.BOW, false),
		GAPPLE(Items.GOLDEN_APPLE, false),
		ENCHANTED_GAPPLE(Items.ENCHANTED_GOLDEN_APPLE, false),
		CHORUS(Items.CHORUS_FRUIT, false);

		private final Item item;
		/** Used with one click; the rest are held until they finish. */
		private final boolean immediate;

		Mode(Item item, boolean immediate) {
			this.item = item;
			this.immediate = immediate;
		}
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "What middle click uses", Mode.PEARL));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your slot afterwards", true));
	private final BoolSetting disableInCreative = add(new BoolSetting("Disable In Creative", "Keep normal pick block in creative", true));

	private boolean using;
	private int useTicks;
	private int returnSlot = -1;

	public MiddleClickExtraModule() {
		super("Middle Click Extra", "Middle click to use pearls, XP, gapples and more", Category.PLAYER);
	}

	@Override
	protected void onDisable() {
		if (!nullCheck()) stop();
	}

	@Override
	public void onTick() {
		if (using) {
			// Swallow extra middle clicks too, so they don't pick block mid-use
			while (mc.options.keyPickItem.consumeClick()) ;
			tickUse();
			return;
		}
		if (Mc.screen() != null || (disableInCreative.get() && mc.player.isCreative())) return;
		// Runs before vanilla handles input this tick, so taking the press here stops pick block
		boolean clicked = false;
		while (mc.options.keyPickItem.consumeClick()) clicked = true;
		// A middle click on a player adds or removes them as a friend instead
		if (clicked && !FemboyWare.getModules().get(FriendsModule.class).onMiddleClick()) use();
	}

	private void use() {
		Item item = mode.get().item;
		boolean held = mc.player.getMainHandItem().is(item);
		if (!held) {
			int slot = InventoryUtil.findHotbar(stack -> stack.is(item));
			if (slot == -1) return;
			returnSlot = mc.player.getInventory().getSelectedSlot();
			InventoryUtil.select(slot);
		}
		if (mode.get().immediate) {
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			mc.player.swing(InteractionHand.MAIN_HAND);
			stop();
			return;
		}
		mc.options.keyUse.setDown(true);
		using = true;
		useTicks = 0;
	}

	private void tickUse() {
		useTicks++;
		// A bow lets go once fully drawn; food and chorus stop when eaten
		boolean keepHolding = !(mc.player.getMainHandItem().getItem() instanceof BowItem)
			|| BowItem.getPowerForTime(mc.player.getTicksUsingItem()) < 1;
		if (useTicks > START_TICKS && !mc.player.isUsingItem() || !keepHolding) {
			stop();
			return;
		}
		mc.options.keyUse.setDown(true);
	}

	private void stop() {
		if (using) mc.options.keyUse.setDown(false);
		using = false;
		if (returnSlot != -1 && swapBack.get()) InventoryUtil.select(returnSlot);
		returnSlot = -1;
	}
}
