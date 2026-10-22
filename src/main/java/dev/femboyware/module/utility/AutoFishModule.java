package dev.femboyware.module.utility;

import dev.femboyware.mixin.FishingBobberEntityAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

import java.util.Random;

/**
 * Casts, waits for a bite and reels in for you.
 * Ported from Meteor Client's AutoFish (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoFishModule extends Module {
	private enum State { IDLE, WAITING_FOR_BITE, WAITING_TO_REEL }

	private record Rod(Hand hand, int slot, int score) {}

	private final BoolSetting autoSwitch = add(new BoolSetting("Auto Switch", "Switch to a fishing rod", true));
	private final BoolSetting antiBreak = add(new BoolSetting("Anti Break", "Don't cast rods that would break", true));
	private final BoolSetting autoCast = add(new BoolSetting("Auto Cast", "Cast the rod again after reeling in", true));
	private final NumberSetting castDelay = add(new NumberSetting("Cast Delay", "Ticks between casts", 14, 1, 60, 1));
	private final NumberSetting castVariance = add(new NumberSetting("Cast Variance", "Random ticks added to the cast delay", 0, 0, 30, 1));
	private final NumberSetting catchDelay = add(new NumberSetting("Catch Delay", "Ticks to wait after a bite before reeling in", 6, 1, 20, 1));
	private final NumberSetting catchVariance = add(new NumberSetting("Catch Variance", "Random ticks added to the catch delay, 10 at most so no fish are missed", 0, 0, 10, 1));

	private final Random random = new Random();
	private State state = State.IDLE;
	private int castDelayLeft;
	private int catchDelayLeft;

	public AutoFishModule() {
		super("Auto Fish", "Fishes for you", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		state = State.IDLE;
		castDelayLeft = 0;
		catchDelayLeft = 0;
	}

	@Override
	public void onTick() {
		FishingBobberEntity bobber = mc.player.fishHook;
		if (bobber == null) {
			state = State.IDLE;
			Rod rod = bestRod();
			if (rod == null || !prepare(rod) || !autoCast.get()) return;
			if (castDelayLeft-- > 0) return;
			if (useRod()) state = State.WAITING_FOR_BITE;
			return;
		}

		switch (state) {
			case IDLE -> state = State.WAITING_FOR_BITE;
			case WAITING_FOR_BITE -> {
				if (bobber.getHookedEntity() != null) {
					reel();
				} else if (((FishingBobberEntityAccessor) bobber).femboyware$hasCaughtFish()) {
					catchDelayLeft = randomized(catchDelay.getInt(), catchVariance.getInt());
					state = State.WAITING_TO_REEL;
				}
			}
			case WAITING_TO_REEL -> {
				if (bobber.getHookedEntity() == null && catchDelayLeft-- > 0) return;
				reel();
			}
		}
	}

	private void reel() {
		if (useRod()) state = State.IDLE;
	}

	private boolean useRod() {
		Rod rod = bestRod();
		if (rod == null || !prepare(rod)) return false;
		mc.interactionManager.interactItem(mc.player, rod.hand);
		mc.player.swingHand(rod.hand);
		castDelayLeft = randomized(castDelay.getInt(), castVariance.getInt());
		return true;
	}

	private boolean prepare(Rod rod) {
		if (rod.hand == Hand.OFF_HAND || rod.slot == mc.player.getInventory().getSelectedSlot()) return true;
		if (!autoSwitch.get()) return false;
		InventoryUtil.select(rod.slot);
		return true;
	}

	/** Highest scoring rod, preferring the held one, then the hotbar in order, then the offhand. */
	private Rod bestRod() {
		int selected = mc.player.getInventory().getSelectedSlot();
		Rod best = null;
		if (usable(mc.player.getMainHandStack())) best = new Rod(Hand.MAIN_HAND, selected, score(mc.player.getMainHandStack()));
		for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
			if (i == selected) continue;
			ItemStack stack = mc.player.getInventory().getStack(i);
			if (!usable(stack)) continue;
			int score = score(stack);
			if (best == null || score > best.score) best = new Rod(Hand.MAIN_HAND, i, score);
		}
		ItemStack offhand = mc.player.getOffHandStack();
		if (usable(offhand) && (best == null || score(offhand) > best.score)) best = new Rod(Hand.OFF_HAND, -1, score(offhand));
		return best;
	}

	private boolean usable(ItemStack stack) {
		if (!(stack.getItem() instanceof FishingRodItem)) return false;
		return !antiBreak.get() || stack.getDamage() != stack.getMaxDamage() - 1;
	}

	private static int score(ItemStack stack) {
		return InventoryUtil.getEnchantmentLevel(stack, Enchantments.LUCK_OF_THE_SEA) + InventoryUtil.getEnchantmentLevel(stack, Enchantments.LURE)
			+ InventoryUtil.getEnchantmentLevel(stack, Enchantments.MENDING) + InventoryUtil.getEnchantmentLevel(stack, Enchantments.UNBREAKING);
	}

	/** {@code delay} plus normally distributed noise of up to {@code variance} either way. */
	private int randomized(int delay, int variance) {
		if (variance == 0) return delay;
		double noise = Math.clamp(random.nextGaussian(), -3, 3) / 3;
		return Math.max(1, delay + (int) Math.round(noise * variance));
	}
}
