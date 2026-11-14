package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Boxes and names on dropped items and experience orbs, through walls. */
public class ItemEspModule extends Module {
	private final BoolSetting items = add(new BoolSetting("Items", "Highlight dropped items", true));
	private final BoolSetting xp = add(new BoolSetting("XP Orbs", "Highlight experience orbs", true));
	private final BoolSetting names = add(new BoolSetting("Names", "Show the item's name and count above it", true));
	private final BoolSetting rarityColors = add(new BoolSetting("Rarity Colors", "Colour items by rarity (enchanted and rare items stand out)", true));
	private final ColorSetting itemColor = add(new ColorSetting("Item Color", "Colour of common items", 0xFFFF77C8));
	private final ColorSetting xpColor = add(new ColorSetting("XP Color", "Colour of experience orbs", 0xFF7CFC00)).visibleWhen(xp::get);
	private final NumberSetting range = add(new NumberSetting("Range", "Furthest to draw", 64, 8, 256, 8));
	private final NumberSetting textScale = add(new NumberSetting("Text Scale", "Size of the names", 1, 0.5, 3, 0.1)).visibleWhen(names::get);

	private int count;

	public ItemEspModule() {
		super("Item Esp", "Highlight dropped items and experience orbs", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(count);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		count = 0;
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double maxSq = range.get() * range.get();
		Vec3 cam = event.camera().pos;
		for (Entity entity : mc.level.entitiesForRendering()) {
			int color;
			String label = null;
			if (entity instanceof ItemEntity item && items.get()) {
				ItemStack stack = item.getItem();
				color = rarityColors.get() ? rarityColor(stack) : itemColor.get();
				if (names.get()) label = stack.getHoverName().getString() + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
			} else if (entity instanceof ExperienceOrb orb && xp.get()) {
				color = xpColor.get();
				if (names.get()) label = orb.getValue() + " xp";
			} else {
				continue;
			}
			Vec3 pos = entity.getPosition(tickDelta);
			if (pos.distanceToSqr(cam) > maxSq) continue;
			AABB box = entity.getBoundingBox().move(pos.subtract(entity.position()));
			RenderUtil.drawBox(event, box, (color & 0x00FFFFFF) | 0x40000000, color, 1.5f, true);
			if (label != null) RenderUtil.drawText(event, label, new Vec3(pos.x, box.maxY + 0.35, pos.z), color, textScale.getFloat() * 0.5f);
			count++;
		}
	}

	private int rarityColor(ItemStack stack) {
		Rarity rarity = stack.getRarity();
		if (stack.isEnchanted() && rarity == Rarity.COMMON) rarity = Rarity.UNCOMMON;
		return switch (rarity) {
			case COMMON -> itemColor.get();
			case UNCOMMON -> 0xFFFFFF55;
			case RARE -> 0xFF55FFFF;
			case EPIC -> 0xFFFF55FF;
		};
	}
}
