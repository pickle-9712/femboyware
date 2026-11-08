package dev.femboyware.module.hud;

import dev.femboyware.setting.ColorSetting;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.combat.AuraModule;
import dev.femboyware.module.combat.AutoCrystalModule;
import dev.femboyware.module.combat.LegitAuraModule;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1 {
/*import net.minecraft.client.gui.components.PlayerFaceExtractor;
*///?} else {
import net.minecraft.client.gui.components.PlayerFaceRenderer;
//?}
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/**
 * A liquid glass card for whoever you're fighting: their face, name, health and absorption, the
 * armour they wear with its durability, how far away they are, and how many totems they've popped.
 * The target is whoever an aura or Auto Crystal is on, else the player you last hit, else the
 * player under your crosshair.
 */
public class TargetHudModule extends HudElement {
	private static final int WIDTH = 150;
	private static final int HEIGHT = 52;
	/** The player you hit stays the target this long. */
	private static final long HIT_MEMORY = 6000;

	/** Totems popped this life, by entity id; filled from the network thread. */
	private final Map<Integer, Integer> pops = new ConcurrentHashMap<>();
	private Player lastHit;
	private long lastHitTime;
	/** Health shown, eased towards the real value so the bar slides instead of jumping. */
	private float shownHealth = -1;
	private int shownId = -1;

	private final ColorSetting healthFull = add(new ColorSetting("Health Full Color", "Health bar and number at full health", 0xFF50FF50)).visibleWhen(this::usesCustomColors);
	private final ColorSetting healthLow = add(new ColorSetting("Health Low Color", "Health bar and number when nearly dead", 0xFFFF0050)).visibleWhen(this::usesCustomColors);
	private final ColorSetting absorptionColor = add(new ColorSetting("Absorption Color", "Extra hearts on the health bar", 0xFFFFC94A)).visibleWhen(this::usesCustomColors);

	public TargetHudModule() {
		super("Target HUD", "Shows your target's health, armour and totem pops", 55, 58);
	}

	@Override
	public void onPostAttack(Entity target) {
		if (target instanceof Player player) {
			lastHit = player;
			lastHitTime = System.currentTimeMillis();
		}
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof ClientboundEntityEventPacket packet) || mc.level == null) return;
		Entity entity = packet.getEntity(mc.level);
		if (!(entity instanceof Player)) return;
		if (packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH) pops.merge(entity.getId(), 1, Integer::sum);
		else if (packet.getEventId() == EntityEvent.DEATH) pops.remove(entity.getId());
	}

	/** Who the card is about right now, or null to hide it. */
	private Player target() {
		LivingEntity aura = FemboyWare.getModules().get(AuraModule.class).getTarget();
		if (aura instanceof Player player && player.isAlive()) return player;
		Player legit = FemboyWare.getModules().get(LegitAuraModule.class).getTarget();
		if (legit != null && legit.isAlive()) return legit;
		AutoCrystalModule crystal = FemboyWare.getModules().get(AutoCrystalModule.class);
		if (crystal.isEnabled() && crystal.getTarget() != null && crystal.getTarget().isAlive()) return crystal.getTarget();
		if (lastHit != null && lastHit.isAlive() && !lastHit.isRemoved() && System.currentTimeMillis() - lastHitTime < HIT_MEMORY
			&& mc.player.distanceTo(lastHit) < 16) return lastHit;
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player player) return player;
		return null;
	}

	@Override
	public int width(boolean editing) {
		return editing || target() != null ? WIDTH : 0;
	}

	@Override
	public int height(boolean editing) {
		return HEIGHT;
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		Player target = target();
		// In the HUD editor with nobody to fight, show yourself as an example
		if (target == null && editing) target = mc.player;
		if (target == null) {
			shownId = -1;
			return;
		}
		Glass.draw(context, left, top, left + WIDTH, top + HEIGHT, Math.min(6, Theme.roundness()), backgroundColor(Theme.panel()), true);

		// Face, with the hat layer
		//? if >=26.1 {
		/*if (target instanceof AbstractClientPlayer client) PlayerFaceExtractor.extractRenderState(context, client.getSkin(), left + 6, top + 6, 26);
		*///?} else {
		if (target instanceof AbstractClientPlayer client) PlayerFaceRenderer.draw(context, client.getSkin(), left + 6, top + 6, 26);
		//?}

		int textLeft = left + 38;
		context.drawString(mc.font, target.getName().getString(), textLeft, top + 6, textColor());
		String info = String.format(Locale.ROOT, "%.1fm", mc.player.distanceTo(target));
		int popped = pops.getOrDefault(target.getId(), 0);
		if (popped > 0) info += "  ·  " + popped + (popped == 1 ? " pop" : " pops");
		context.drawString(mc.font, info, textLeft, top + 17, mutedColor());

		// Health bar: green when full through yellow to red, with absorption in gold beyond it
		float health = target.getHealth();
		if (shownId != target.getId() || shownHealth < 0) shownHealth = health;
		shownId = target.getId();
		shownHealth += (health - shownHealth) * 0.25f;
		float max = target.getMaxHealth();
		float absorption = target.getAbsorptionAmount();
		int barLeft = left + 6;
		int barRight = left + WIDTH - 6;
		int barTop = top + HEIGHT - 10;
		Glass.rounded(context, barLeft, barTop, barRight, barTop + 4, 2, 0x40000000);
		float fraction = Mth.clamp(shownHealth / (max + absorption), 0, 1);
		int filled = barLeft + Math.round((barRight - barLeft) * fraction);
		if (filled > barLeft) Glass.rounded(context, barLeft, barTop, filled, barTop + 4, 2, healthColor(health / max));
		if (absorption > 0) {
			int gold = Math.min(barRight, filled + Math.round((barRight - barLeft) * absorption / (max + absorption)));
			Glass.rounded(context, filled, barTop, gold, barTop + 4, 2, absorptionColor.get());
		}
		String hp = String.format(Locale.ROOT, "%.1f", health + absorption);
		context.drawString(mc.font, hp, barRight - mc.font.width(hp), top + 6, healthColor(health / max));

		// Armour, head to feet, with durability bars
		int itemLeft = textLeft;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
			ItemStack stack = target.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				context.pose().pushMatrix();
				context.pose().translate(itemLeft, top + 26);
				context.pose().scale(0.7f, 0.7f);
				context.renderItem(stack, 0, 0);
				context.renderItemDecorations(mc.font, stack, 0, 0);
				context.pose().popMatrix();
			}
			itemLeft += 13;
		}
	}

	/** Green when healthy, through yellow, to red when nearly dead. */
	private int healthColor(float fraction) {
		fraction = Mth.clamp(fraction, 0, 1);
		if (usesCustomColors()) return LyricFont.mix(healthLow.get(), healthFull.get(), fraction);
		int r = fraction > 0.5f ? (int) (255 * (1 - fraction) * 2) : 255;
		int g = fraction > 0.5f ? 255 : (int) (255 * fraction * 2);
		return 0xFF000000 | r << 16 | g << 8 | 0x50;
	}
}
