package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ProjectionUtil;
import dev.femboyware.util.RenderUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

	private record Tag(PlayerEntity player, Vec3d screen, double distance) {}

	public NametagsModule() {
		super("Nametags", "Shows player names, health, ping, armour and held items", Category.ESP);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!box.get()) return;
		float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (player == mc.player || player.isInvisible()) continue;
			Vec3d lerped = player.getLerpedPos(tickDelta);
			Box bounds = player.getBoundingBox().offset(lerped.subtract(player.getEntityPos()));
			RenderUtil.drawBox(event, bounds, boxColor.withAlpha(25), boxColor.get(), 1.5f);
		}
	}

	@Override
	public void onRender2D(DrawContext context, float tickDelta) {
		if (mc.options.hudHidden) return;
		List<Tag> tags = new ArrayList<>();
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (player == mc.player && (!self.get() || mc.options.getPerspective().isFirstPerson())) continue;
			Vec3d pos = player.getLerpedPos(tickDelta).add(0, player.getHeight() + 0.35, 0);
			Vec3d screen = ProjectionUtil.toScreen(pos);
			if (screen == null) continue;
			tags.add(new Tag(player, screen, mc.player.distanceTo(player)));
		}
		// Far tags first so near ones draw on top
		tags.sort(Comparator.comparingDouble(Tag::distance).reversed());
		for (Tag tag : tags) drawTag(context, tag);
	}

	private void drawTag(DrawContext context, Tag tag) {
		TextRenderer font = mc.textRenderer;
		PlayerEntity player = tag.player;

		float size = scale.getFloat();
		if (distanceScaling.get()) size *= (float) MathHelper.clamp(8.0 / Math.max(tag.distance, 1.0), 0.45, 1.25);

		String line = buildLine(player, tag.distance);
		int width = font.getWidth(line);

		context.getMatrices().pushMatrix();
		context.getMatrices().translate((float) tag.screen.x, (float) tag.screen.y);
		context.getMatrices().scale(size, size);

		int textX = -width / 2;
		int textY = -font.fontHeight;
		if (background.get()) context.fill(textX - 2, textY - 2, textX + width + 2, textY + font.fontHeight, 0x90000000);
		drawLine(context, font, player, textX, textY, tag.distance);

		List<ItemStack> items = collectItems(player);
		int itemsY = textY - 20;
		if (!items.isEmpty()) {
			int itemsX = -(items.size() * 16) / 2;
			for (int i = 0; i < items.size(); i++) {
				ItemStack stack = items.get(i);
				int x = itemsX + i * 16;
				context.drawItem(stack, x, itemsY);
				context.drawStackOverlay(font, stack, x, itemsY);
				if (durability.get() && armor.get() && stack.isDamageable() && isArmor(player, stack)) {
					int percent = Math.round(100f * (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage());
					String text = percent + "%";
					context.getMatrices().pushMatrix();
					context.getMatrices().translate(x + 8, itemsY - 5);
					context.getMatrices().scale(0.5f, 0.5f);
					context.drawTextWithShadow(font, text, -font.getWidth(text) / 2, 0, durabilityColor(percent / 100f));
					context.getMatrices().popMatrix();
				}
			}
		}

		ItemStack main = player.getMainHandStack();
		if (heldItems.get() && itemName.get() && !main.isEmpty()) {
			String name = main.getName().getString();
			int nameY = (items.isEmpty() ? textY : itemsY) - (durability.get() && armor.get() ? 15 : 11);
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(0, nameY);
			context.getMatrices().scale(0.75f, 0.75f);
			context.drawTextWithShadow(font, name, -font.getWidth(name) / 2, 0, 0xFFE0D8E8);
			context.getMatrices().popMatrix();
		}

		context.getMatrices().popMatrix();
	}

	private String buildLine(PlayerEntity player, double dist) {
		StringBuilder builder = new StringBuilder(player.getName().getString());
		if (health.get()) builder.append(' ').append(formatHealth(player));
		if (ping.get()) builder.append(' ').append(getPing(player)).append("ms");
		if (distance.get()) builder.append(' ').append(String.format("%.0fm", dist));
		return builder.toString();
	}

	/** Draws the same text as {@link #buildLine} but with each part coloured. */
	private void drawLine(DrawContext context, TextRenderer font, PlayerEntity player, int x, int y, double dist) {
		String name = player.getName().getString();
		context.drawTextWithShadow(font, name, x, y, 0xFFFFFFFF);
		x += font.getWidth(name);
		if (health.get()) {
			String text = " " + formatHealth(player);
			float fraction = (player.getHealth() + player.getAbsorptionAmount()) / player.getMaxHealth();
			context.drawTextWithShadow(font, text, x, y, healthColor(fraction));
			x += font.getWidth(text);
		}
		if (ping.get()) {
			int latency = getPing(player);
			String text = " " + latency + "ms";
			context.drawTextWithShadow(font, text, x, y, latency < 80 ? 0xFF77FF99 : latency < 180 ? 0xFFFFD966 : 0xFFFF6666);
			x += font.getWidth(text);
		}
		if (distance.get()) {
			context.drawTextWithShadow(font, String.format(" %.0fm", dist), x, y, 0xFFAAAAAA);
		}
	}

	private List<ItemStack> collectItems(PlayerEntity player) {
		List<ItemStack> items = new ArrayList<>();
		if (heldItems.get() && !player.getMainHandStack().isEmpty()) items.add(player.getMainHandStack());
		if (armor.get()) {
			for (EquipmentSlot slot : ARMOR_ORDER) {
				ItemStack stack = player.getEquippedStack(slot);
				if (!stack.isEmpty()) items.add(stack);
			}
		}
		if (heldItems.get() && !player.getOffHandStack().isEmpty()) items.add(player.getOffHandStack());
		return items;
	}

	private static boolean isArmor(PlayerEntity player, ItemStack stack) {
		for (EquipmentSlot slot : ARMOR_ORDER) {
			if (player.getEquippedStack(slot) == stack) return true;
		}
		return false;
	}

	private int getPing(PlayerEntity player) {
		PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
		return entry == null ? 0 : entry.getLatency();
	}

	private static String formatHealth(PlayerEntity player) {
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
		int red = (int) (255 * MathHelper.clamp(2 * (1 - fraction), 0, 1));
		int green = (int) (255 * MathHelper.clamp(2 * fraction, 0, 1));
		return 0xFF000000 | red << 16 | green << 8 | 0x40;
	}
}
