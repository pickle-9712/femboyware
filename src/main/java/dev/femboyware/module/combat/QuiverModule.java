package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.Timer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Shoots tipped arrows of helpful effects straight up so they land on you. The arrow is moved
 * into the offhand for the shot, because the bow always fires the offhand arrow first.
 */
public class QuiverModule extends Module {
	private static final int ROTATION_PRIORITY = 100;

	private enum Stage { IDLE, CHARGING, RESTORE }

	private final NumberSetting charge = add(new NumberSetting("Charge", "Ticks to draw the bow", 5, 3, 20, 1));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between shots", 1000, 100, 5000, 50));
	private final NumberSetting minHealth = add(new NumberSetting("Min Health", "Don't give up your offhand below this much health", 10, 0, 36, 0.5));

	private final Timer timer = new Timer();
	private Stage stage = Stage.IDLE;
	private int ticks;
	private int arrowSlot;
	private int previousSlot;

	public QuiverModule() {
		super("Quiver", "Shoots yourself with helpful tipped arrows", Category.PROTECT);
	}

	/** Whether the offhand currently holds a borrowed arrow, so other modules leave it alone. */
	public boolean isBusy() {
		return isEnabled() && stage != Stage.IDLE;
	}

	@Override
	protected void onDisable() {
		if (stage != Stage.IDLE && !nullCheck()) restore();
	}

	@Override
	public void onPreMotion() {
		// Keep looking straight up for the whole draw so the release goes up too
		if (stage == Stage.CHARGING) {
			FemboyWare.getRotations().request(new Rotation(mc.player.getYaw(), -90f), ROTATION_PRIORITY, 180f, false);
		}
	}

	@Override
	public void onTick() {
		switch (stage) {
			case IDLE -> tryStart();
			case CHARGING -> {
				if (!mc.player.getMainHandStack().isOf(Items.BOW)) {
					restore();
					return;
				}
				ticks++;
				mc.options.useKey.setPressed(ticks <= charge.getInt());
				if (ticks > charge.getInt()) {
					stage = Stage.RESTORE;
					ticks = 0;
				}
			}
			case RESTORE -> {
				if (++ticks >= 2) restore();
			}
		}
	}

	private void tryStart() {
		if (!timer.passed(delay.get()) || mc.currentScreen != null || mc.player.isUsingItem()) return;
		if (mc.player.getHealth() + mc.player.getAbsorptionAmount() < minHealth.get()) return;
		int bow = InventoryUtil.findHotbar(stack -> stack.isOf(Items.BOW));
		int arrow = InventoryUtil.findInventory(this::isUsefulArrow);
		if (bow < 0 || arrow < 0) return;

		arrowSlot = arrow;
		previousSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.swapWithOffhand(arrowSlot);
		InventoryUtil.select(bow);
		stage = Stage.CHARGING;
		ticks = 0;
	}

	private void restore() {
		mc.options.useKey.setPressed(false);
		InventoryUtil.swapWithOffhand(arrowSlot);
		InventoryUtil.select(previousSlot);
		stage = Stage.IDLE;
		timer.reset();
	}

	/** A tipped arrow whose effects are all helpful and at least one of which we don't have yet. */
	private boolean isUsefulArrow(ItemStack stack) {
		if (!stack.isOf(Items.TIPPED_ARROW)) return false;
		PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
		if (contents == null) return false;
		boolean missing = false;
		for (StatusEffectInstance effect : contents.getEffects()) {
			if (!effect.getEffectType().value().isBeneficial()) return false;
			if (!mc.player.hasStatusEffect(effect.getEffectType())) missing = true;
		}
		return missing;
	}
}
