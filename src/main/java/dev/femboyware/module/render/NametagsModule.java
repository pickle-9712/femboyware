package dev.femboyware.module.render;

import dev.femboyware.util.Mc;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ProjectionUtil;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Player ESP: nametags with health, ping, armour and held items, drawn on top of everything. */
public class NametagsModule extends Module {
	private static final EquipmentSlot[] ARMOR_ORDER = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private final NumberSetting scale = add(new NumberSetting("Scale", "Tag size", 1.0, 0.3, 3, 0.05));
	private final BoolSetting distanceScaling = add(new BoolSetting("Distance Scaling", "Shrink tags with distance", true));
	private final BoolSetting health = add(new BoolSetting("Health", "Show health and absorption", true));
	private final BoolSetting ping = add(new BoolSetting("Ping", "Show ping", true));
	private final BoolSetting distance = add(new BoolSetting("Distance", "Show distance", false));
	private final BoolSetting armor = add(new BoolSetting("Armor", "Show armour pieces", true));
	private final BoolSetting durability = add(new BoolSetting("Durability", "Show armour durability %", true)).visibleWhen(armor::get);
	private final BoolSetting heldItems = add(new BoolSetting("Held Items", "Show main and off hand items", true));
	private final BoolSetting itemName = add(new BoolSetting("Item Name", "Show the held item's name", true)).visibleWhen(heldItems::get);
	private final BoolSetting background = add(new BoolSetting("Background", "Dark box behind the name", true));
	private final BoolSetting self = add(new BoolSetting("Self", "Show your own nametag in third person", false));
	private final BoolSetting box = add(new BoolSetting("Box", "Outline players in the world", false));
	private final ColorSetting boxColor = add(new ColorSetting("Box Color", "Player box colour", 0xFFFF77C8)).visibleWhen(box::get);

	private record Tag(Player player, Vec3 screen, double distance) {}

	public NametagsModule() {
		super("Nametags", "Shows player names, health, ping, armour and held items", Category.ESP);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!box.get()) return;
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		for (Player player : mc.level.players()) {
			if (player == mc.player || player.isInvisible()) continue;
			Vec3 lerped = player.getPosition(tickDelta);
			AABB bounds = player.getBoundingBox().move(lerped.subtract(player.position()));
			RenderUtil.drawBox(event, bounds, boxColor.withAlpha(25), boxColor.get(), 1.5f);
		}
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		if (Mc.hudHidden()) return;
		List<Tag> tags = new ArrayList<>();
		for (Player player : mc.level.players()) {
			if (player == mc.player && (!self.get() || mc.options.getCameraType().isFirstPerson())) continue;
			Vec3 pos = player.getPosition(tickDelta).add(0, player.getBbHeight() + 0.35, 0);
			Vec3 screen = ProjectionUtil.toScreen(pos);
			if (screen == null) continue;
			tags.add(new Tag(player, screen, mc.player.distanceTo(player)));
		}
		// Far tags first so near ones draw on top
		tags.sort(Comparator.comparingDouble(Tag::distance).reversed());
		for (Tag tag : tags) drawTag(context, tag);
	}

	private void drawTag(GuiGraphics context, Tag tag) {
		Font font = mc.font;
		Player player = tag.player;

		float size = scale.getFloat();
		if (distanceScaling.get()) size *= (float) Mth.clamp(8.0 / Math.max(tag.distance, 1.0), 0.45, 1.25);

		String line = buildLine(player, tag.distance);
		int width = font.width(line);

		context.pose().pushMatrix();
		context.pose().translate((float) tag.screen.x, (float) tag.screen.y);
		context.pose().scale(size, size);

		int textX = -width / 2;
		int textY = -font.lineHeight;
		if (background.get()) context.fill(textX - 2, textY - 2, textX + width + 2, textY + font.lineHeight, 0x90000000);
		drawLine(context, font, player, textX, textY, tag.distance);

		List<ItemStack> items = collectItems(player);
		int itemsY = textY - 20;
		if (!items.isEmpty()) {
			int itemsX = -(items.size() * 16) / 2;
			for (int i = 0; i < items.size(); i++) {
				ItemStack stack = items.get(i);
				int x = itemsX + i * 16;
				context.renderItem(stack, x, itemsY);
				context.renderItemDecorations(font, stack, x, itemsY);
				if (durability.get() && armor.get() && stack.isDamageableItem() && isArmor(player, stack)) {
					int percent = Math.round(100f * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage());
					String text = percent + "%";
					context.pose().pushMatrix();
					context.pose().translate(x + 8, itemsY - 5);
					context.pose().scale(0.5f, 0.5f);
					context.drawString(font, text, -font.width(text) / 2, 0, durabilityColor(percent / 100f));
					context.pose().popMatrix();
				}
			}
		}

		ItemStack main = player.getMainHandItem();
		if (heldItems.get() && itemName.get() && !main.isEmpty()) {
			String name = main.getHoverName().getString();
			int nameY = (items.isEmpty() ? textY : itemsY) - (durability.get() && armor.get() ? 15 : 11);
			context.pose().pushMatrix();
			context.pose().translate(0, nameY);
			context.pose().scale(0.75f, 0.75f);
			context.drawString(font, name, -font.width(name) / 2, 0, 0xFFE0D8E8);
			context.pose().popMatrix();
		}

		context.pose().popMatrix();
	}

	private String buildLine(Player player, double dist) {
		StringBuilder builder = new StringBuilder(player.getName().getString());
		if (health.get()) builder.append(' ').append(formatHealth(player));
		if (ping.get()) builder.append(' ').append(getPing(player)).append("ms");
		if (distance.get()) builder.append(' ').append(String.format("%.0fm", dist));
		return builder.toString();
	}

	/** Draws the same text as {@link #buildLine} but with each part coloured. */
	private void drawLine(GuiGraphics context, Font font, Player player, int x, int y, double dist) {
		String name = player.getName().getString();
		context.drawString(font, name, x, y, 0xFFFFFFFF);
		x += font.width(name);
		if (health.get()) {
			String text = " " + formatHealth(player);
			float fraction = (player.getHealth() + player.getAbsorptionAmount()) / player.getMaxHealth();
			context.drawString(font, text, x, y, healthColor(fraction));
			x += font.width(text);
		}
		if (ping.get()) {
			int latency = getPing(player);
			String text = " " + latency + "ms";
			context.drawString(font, text, x, y, latency < 80 ? 0xFF77FF99 : latency < 180 ? 0xFFFFD966 : 0xFFFF6666);
			x += font.width(text);
		}
		if (distance.get()) {
			context.drawString(font, String.format(" %.0fm", dist), x, y, 0xFFAAAAAA);
		}
	}

	private List<ItemStack> collectItems(Player player) {
		List<ItemStack> items = new ArrayList<>();
		if (heldItems.get() && !player.getMainHandItem().isEmpty()) items.add(player.getMainHandItem());
		if (armor.get()) {
			for (EquipmentSlot slot : ARMOR_ORDER) {
				ItemStack stack = player.getItemBySlot(slot);
				if (!stack.isEmpty()) items.add(stack);
			}
		}
		if (heldItems.get() && !player.getOffhandItem().isEmpty()) items.add(player.getOffhandItem());
		return items;
	}

	private static boolean isArmor(Player player, ItemStack stack) {
		for (EquipmentSlot slot : ARMOR_ORDER) {
			if (player.getItemBySlot(slot) == stack) return true;
		}
		return false;
	}

	private int getPing(Player player) {
		PlayerInfo entry = mc.getConnection().getPlayerInfo(player.getUUID());
		return entry == null ? 0 : entry.getLatency();
	}

	private static String formatHealth(Player player) {
		float total = player.getHealth() + player.getAbsorptionAmount();
		return String.format("%.1f", total);
	}

	private static int healthColor(float fraction) {
		if (fraction > 1f) return 0xFFFFD966;
		if (fraction > 0.66f) return 0xFF77FF99;
		if (fraction > 0.33f) return 0xFFFFB347;
		return 0xFFFF6666;
	}

	private static int durabilityColor(float fraction) {
		int red = (int) (255 * Mth.clamp(2 * (1 - fraction), 0, 1));
		int green = (int) (255 * Mth.clamp(2 * fraction, 0, 1));
		return 0xFF000000 | red << 16 | green << 8 | 0x40;
	}
}
