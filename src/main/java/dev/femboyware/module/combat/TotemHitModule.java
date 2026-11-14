package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;

/**
 * While you hold a totem in your main hand, your hits go out with your sword (or axe) and the
 * totem comes back to your hand straight after, so you keep the totem ready and still do damage.
 */
public class TotemHitModule extends Module {
	private final BoolSetting axes = add(new BoolSetting("Axes", "Use an axe if you have no sword", true));

	private int previousSlot = -1;

	public TotemHitModule() {
		super("Totem Hit", "Hit with your sword while holding a totem, then switch back", Category.ATTACK);
	}

	@Override
	public void onPreAttack(Entity target) {
		previousSlot = -1;
		if (!mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) return;
		int slot = InventoryUtil.findHotbar(stack -> stack.is(ItemTags.SWORDS));
		if (slot < 0 && axes.get()) slot = InventoryUtil.findHotbar(stack -> stack.is(ItemTags.AXES));
		if (slot < 0) return;
		previousSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
	}

	@Override
	public void onPostAttack(Entity target) {
		if (previousSlot >= 0) InventoryUtil.select(previousSlot);
		previousSlot = -1;
	}
}
