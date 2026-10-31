package dev.femboyware.module.hud;

import dev.femboyware.setting.ColorSetting;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.combat.AuraModule;
import dev.femboyware.module.combat.AutoCrystalModule;
import dev.femboyware.module.combat.LegitAuraModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
	private PlayerEntity lastHit;
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
		if (target instanceof PlayerEntity player) {
			lastHit = player;
			lastHitTime = System.currentTimeMillis();
		}
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof EntityStatusS2CPacket packet) || mc.world == null) return;
		Entity entity = packet.getEntity(mc.world);
		if (!(entity instanceof PlayerEntity)) return;
		if (packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING) pops.merge(entity.getId(), 1, Integer::sum);
		else if (packet.getStatus() == EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) pops.remove(entity.getId());
	}

	/** Who the card is about right now, or null to hide it. */
	private PlayerEntity target() {
		LivingEntity aura = FemboyWare.getModules().get(AuraModule.class).getTarget();
		if (aura instanceof PlayerEntity player && player.isAlive()) return player;
		PlayerEntity legit = FemboyWare.getModules().get(LegitAuraModule.class).getTarget();
		if (legit != null && legit.isAlive()) return legit;
		AutoCrystalModule crystal = FemboyWare.getModules().get(AutoCrystalModule.class);
		if (crystal.isEnabled() && crystal.getTarget() != null && crystal.getTarget().isAlive()) return crystal.getTarget();
		if (lastHit != null && lastHit.isAlive() && !lastHit.isRemoved() && System.currentTimeMillis() - lastHitTime < HIT_MEMORY
			&& mc.player.distanceTo(lastHit) < 16) return lastHit;
		if (mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof PlayerEntity player) return player;
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
	protected void draw(DrawContext context, int left, int top, boolean editing) {
		PlayerEntity target = target();
		// In the HUD editor with nobody to fight, show yourself as an example
		if (target == null && editing) target = mc.player;
		if (target == null) {
			shownId = -1;
			return;
		}
		Glass.draw(context, left, top, left + WIDTH, top + HEIGHT, Math.min(6, Theme.roundness()), backgroundColor(Theme.panel()), true);

		// Face, with the hat layer
		if (target instanceof AbstractClientPlayerEntity client) PlayerSkinDrawer.draw(context, client.getSkin(), left + 6, top + 6, 26);

		int textLeft = left + 38;
		context.drawTextWithShadow(mc.textRenderer, target.getName().getString(), textLeft, top + 6, textColor());
		String info = String.format(Locale.ROOT, "%.1fm", mc.player.distanceTo(target));
		int popped = pops.getOrDefault(target.getId(), 0);
		if (popped > 0) info += "  ·  " + popped + (popped == 1 ? " pop" : " pops");
		context.drawTextWithShadow(mc.textRenderer, info, textLeft, top + 17, mutedColor());

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
		float fraction = MathHelper.clamp(shownHealth / (max + absorption), 0, 1);
		int filled = barLeft + Math.round((barRight - barLeft) * fraction);
		if (filled > barLeft) Glass.rounded(context, barLeft, barTop, filled, barTop + 4, 2, healthColor(health / max));
		if (absorption > 0) {
			int gold = Math.min(barRight, filled + Math.round((barRight - barLeft) * absorption / (max + absorption)));
			Glass.rounded(context, filled, barTop, gold, barTop + 4, 2, absorptionColor.get());
		}
		String hp = String.format(Locale.ROOT, "%.1f", health + absorption);
		context.drawTextWithShadow(mc.textRenderer, hp, barRight - mc.textRenderer.getWidth(hp), top + 6, healthColor(health / max));

		// Armour, head to feet, with durability bars
		int itemLeft = textLeft;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
			ItemStack stack = target.getEquippedStack(slot);
			if (!stack.isEmpty()) {
				context.getMatrices().pushMatrix();
				context.getMatrices().translate(itemLeft, top + 26);
				context.getMatrices().scale(0.7f, 0.7f);
				context.drawItem(stack, 0, 0);
				context.drawStackOverlay(mc.textRenderer, stack, 0, 0);
				context.getMatrices().popMatrix();
			}
			itemLeft += 13;
		}
	}

	/** Green when healthy, through yellow, to red when nearly dead. */
	private int healthColor(float fraction) {
		fraction = MathHelper.clamp(fraction, 0, 1);
		if (usesCustomColors()) return LyricFont.mix(healthLow.get(), healthFull.get(), fraction);
		int r = fraction > 0.5f ? (int) (255 * (1 - fraction) * 2) : 255;
		int g = fraction > 0.5f ? 255 : (int) (255 * fraction * 2);
		return 0xFF000000 | r << 16 | g << 8 | 0x50;
	}
}
