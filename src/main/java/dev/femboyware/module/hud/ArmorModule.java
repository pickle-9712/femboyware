package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Your armour with how much durability each piece has left, and how many totems you carry. */
public class ArmorModule extends HudElement {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final int CELL = 20;

	private final BoolSetting percent = add(new BoolSetting("Percent", "Show durability as a percentage", true));
	private final BoolSetting totems = add(new BoolSetting("Totems", "Show how many totems you have", true));

	private final ColorSetting goodColor = add(new ColorSetting("Good Color", "Durability above 60%", 0xFF80FF90)).visibleWhen(this::usesCustomColors);
	private final ColorSetting okayColor = add(new ColorSetting("Okay Color", "Durability above 30%", 0xFFFFD060)).visibleWhen(this::usesCustomColors);
	private final ColorSetting lowColor = add(new ColorSetting("Low Color", "Low durability, and no totems left", 0xFFFF6060)).visibleWhen(this::usesCustomColors);

	public ArmorModule() {
		super("Armor", "Your armour durability and totem count", 50, 76);
	}

	@Override
	public int width(boolean editing) {
		return (SLOTS.length + (totems.get() ? 1 : 0)) * CELL + 6;
	}

	@Override
	public int height(boolean editing) {
		return 30;
	}

	@Override
	protected void draw(DrawContext context, int left, int top, boolean editing) {
		Glass.draw(context, left, top, left + width(editing), top + height(editing), 5, backgroundColor(Theme.list()), false);
		int x = left + 4;
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = mc.player == null ? ItemStack.EMPTY : mc.player.getEquippedStack(slot);
			if (!stack.isEmpty()) {
				context.drawItem(stack, x + 1, top + 3);
				context.drawStackOverlay(mc.textRenderer, stack, x + 1, top + 3);
				if (percent.get() && stack.isDamageable()) {
					int left2 = stack.getMaxDamage() - stack.getDamage();
					int pct = Math.round(left2 * 100f / stack.getMaxDamage());
					String text = pct + "%";
					context.getMatrices().pushMatrix();
					context.getMatrices().translate(x + 9 - mc.textRenderer.getWidth(text) * 0.35f, top + 21);
					context.getMatrices().scale(0.7f, 0.7f);
					context.drawTextWithShadow(mc.textRenderer, text, 0, 0, durabilityColor(pct));
					context.getMatrices().popMatrix();
				}
			}
			x += CELL;
		}
		if (totems.get()) {
			int count = mc.player == null ? 0 : InventoryUtil.count(stack -> stack.isOf(Items.TOTEM_OF_UNDYING));
			ItemStack totem = new ItemStack(Items.TOTEM_OF_UNDYING);
			context.drawItem(totem, x + 1, top + 3);
			String text = Integer.toString(count);
			context.drawTextWithShadow(mc.textRenderer, text, x + 9 - mc.textRenderer.getWidth(text) / 2, top + 20, count == 0 ? lowColor.get() : textColor());
		}
	}

	private int durabilityColor(int percent) {
		if (percent > 60) return goodColor.get();
		if (percent > 30) return okayColor.get();
		return lowColor.get();
	}
}
