package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * While Aura (or Legit Aura) is fighting a player a few blocks off, every so often it swaps to a
 * fishing rod in your hotbar, casts it at them for the knockback, and swaps back to your weapon.
 */
public class RodSwapModule extends Module {
	private final NumberSetting interval = add(new NumberSetting("Interval", "Milliseconds between casts", 1500, 300, 5000, 50));
	private final NumberSetting minDistance = add(new NumberSetting("Min Distance", "Only cast at targets at least this far", 3, 0, 8, 0.5));
	private final NumberSetting maxDistance = add(new NumberSetting("Max Distance", "Only cast at targets at most this far", 7, 2, 15, 0.5));
	private final NumberSetting holdTicks = add(new NumberSetting("Hold Ticks", "Ticks to keep the rod out so the hook reaches them", 5, 1, 15, 1));

	private long lastCast;
	private int previousSlot = -1;
	private int left;

	public RodSwapModule() {
		super("Rod Swap", "Kill Aura throws a hotbar rod at its player target on a delay, then returns to its weapon", Category.ATTACK);
	}

	@Override
	protected void onDisable() {
		swapBack();
	}

	@Override
	public void onPostMotion() {
		if (previousSlot >= 0) {
			if (--left <= 0) swapBack();
			return;
		}
		if (Mc.screen() != null || mc.player.isUsingItem() || mc.player.fishing != null) return;
		LivingEntity target = target();
		if (!(target instanceof Player)) return;
		double distance = mc.player.distanceTo(target);
		if (distance < minDistance.get() || distance > maxDistance.get()) return;
		long now = System.currentTimeMillis();
		if (now - lastCast < interval.getInt()) return;
		int rod = InventoryUtil.findHotbar(stack -> stack.is(Items.FISHING_ROD));
		if (rod < 0) return;
		int current = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(rod);
		// Cast right after this tick's rotation went out, so it flies where the aura aims
		mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		mc.player.swing(InteractionHand.MAIN_HAND);
		lastCast = now;
		previousSlot = rod == current ? -1 : current;
		left = holdTicks.getInt();
	}

	private void swapBack() {
		if (previousSlot >= 0 && !nullCheck()) InventoryUtil.select(previousSlot);
		previousSlot = -1;
	}

	private static LivingEntity target() {
		AuraModule aura = FemboyWare.getModules().get(AuraModule.class);
		if (aura.isEnabled() && aura.getTarget() != null) return aura.getTarget();
		LegitAuraModule legit = FemboyWare.getModules().get(LegitAuraModule.class);
		return legit.isEnabled() ? legit.getTarget() : null;
	}
}
