package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Timer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Keeps the item you want in your offhand, falling back to a totem when you're in danger. */
public class OffhandModule extends Module {
	public enum Mode { TOTEM, CRYSTAL, GAPPLE }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Item to hold while safe", Mode.TOTEM));
	private final NumberSetting health = add(new NumberSetting("Health", "Switch to a totem at or below this much health plus absorption", 14, 1, 36, 0.5));
	private final BoolSetting swordGapple = add(new BoolSetting("Sword Gapple", "Hold a gapple while right clicking with a sword", true));
	private final BoolSetting fallSafety = add(new BoolSetting("Fall Safety", "Hold a totem while falling far", true));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between swaps", 50, 0, 500, 10));

	private final Timer timer = new Timer();

	public OffhandModule() {
		super("Offhand", "Manages the item in your offhand", Category.PROTECT);
	}

	@Override
	public String getInfo() {
		if (nullCheck()) return null;
		Item item = desired();
		return item == Items.TOTEM_OF_UNDYING ? "Totem" : item == Items.END_CRYSTAL ? "Crystal" : "Gapple";
	}

	@Override
	protected void onEnable() {
		// Both manage the offhand and would fight over it
		FemboyWare.getModules().get(AutoTotemModule.class).setEnabled(false);
	}

	@Override
	public void onTick() {
		if (mc.player.containerMenu != mc.player.inventoryMenu || !timer.passed(delay.get())) return;
		if (FemboyWare.getModules().get(QuiverModule.class).isBusy()) return;
		Item wanted = desired();
		if (mc.player.getOffhandItem().is(wanted)) return;

		int slot = InventoryUtil.findInventory(stack -> stack.is(wanted));
		// Out of the preferred item: a totem is always the safe fallback
		if (slot < 0 && wanted != Items.TOTEM_OF_UNDYING && !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
			slot = InventoryUtil.findInventory(stack -> stack.is(Items.TOTEM_OF_UNDYING));
		}
		if (slot < 0) return;
		InventoryUtil.swapWithOffhand(slot);
		timer.reset();
	}

	private Item desired() {
		float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
		boolean danger = hp <= health.get() || fallSafety.get() && mc.player.fallDistance > 10 && !mc.player.isFallFlying();
		if (danger) return Items.TOTEM_OF_UNDYING;
		if (swordGapple.get() && mc.options.keyUse.isDown() && mc.player.getMainHandItem().is(ItemTags.SWORDS)) {
			return bestApple();
		}
		return switch (mode.get()) {
			case TOTEM -> Items.TOTEM_OF_UNDYING;
			case CRYSTAL -> Items.END_CRYSTAL;
			case GAPPLE -> bestApple();
		};
	}

	private Item bestApple() {
		boolean enchanted = mc.player.getOffhandItem().is(Items.ENCHANTED_GOLDEN_APPLE)
			|| InventoryUtil.findInventory(stack -> stack.is(Items.ENCHANTED_GOLDEN_APPLE)) >= 0;
		return enchanted ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
	}
}
