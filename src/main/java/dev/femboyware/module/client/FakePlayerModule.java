package dev.femboyware.module.client;

import com.mojang.authlib.GameProfile;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.DamageCalculator;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Spawns a client-side player that stands still and takes damage, so combat modules can be
 * tested without a second account. Melee hits never reach the server (see the interaction
 * manager mixin) and explosions are applied from the server's explosion packets.
 */
public class FakePlayerModule extends Module {
	private static final int ENTITY_ID = -1337;
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private final StringSetting name = add(new StringSetting("Name", "Name shown above the fake player", "FakePlayer"));
	private final NumberSetting health = add(new NumberSetting("Health", "Health the fake player spawns with", 20, 1, 20, 1));
	private final BoolSetting copyArmor = add(new BoolSetting("Copy Armor", "Give it your armor and held items", true));
	private final BoolSetting respawn = add(new BoolSetting("Respawn", "Respawn in the same spot after dying", true));
	private final NumberSetting respawnDelay = add(new NumberSetting("Respawn Delay", "Ticks to wait before respawning", 40, 0, 200, 1)).visibleWhen(respawn::get);
	private final BoolSetting invincible = add(new BoolSetting("Invincible", "Takes hits and flinches but never loses health", false));

	private OtherClientPlayerEntity fake;
	private Vec3d spawnPos;
	private float spawnYaw;
	private float lastDamage;
	private int invulnerableTicks;
	private int deadTicks;

	public FakePlayerModule() {
		super("Fake Player", "Spawns a stationary fake player to test modules on", Category.WORLD);
	}

	@Override
	public String getInfo() {
		return fake == null ? null : String.format("%.1f", fake.getHealth());
	}

	@Override
	protected void onEnable() {
		// May be enabled from the config before a world exists; onTick spawns it then
		spawnPos = null;
		if (!nullCheck()) spawn(mc.player.getEntityPos(), mc.player.getYaw());
	}

	@Override
	protected void onDisable() {
		despawn();
		spawnPos = null;
	}

	@Override
	public void onTick() {
		// After a world change or dimension switch, spawn a new one next to us
		if (fake != null && fake.getEntityWorld() != mc.world) spawnPos = null;
		if (spawnPos == null) {
			spawn(mc.player.getEntityPos(), mc.player.getYaw());
			return;
		}
		if (invulnerableTicks > 0) invulnerableTicks--;
		if (fake != null && !fake.isDead()) return;

		deadTicks++;
		// Leave the body for the vanilla death animation before removing it
		if (deadTicks == 20) despawn();
		if (deadTicks < 20) return;
		if (!respawn.get()) setEnabled(false);
		else if (deadTicks >= 20 + respawnDelay.getInt()) spawn(spawnPos, spawnYaw);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (event.getPacket() instanceof ExplosionS2CPacket packet) {
			mc.execute(() -> onExplosion(packet.center(), packet.radius()));
		}
	}

	/** Called from the interaction manager mixin. Returns true when the attack was handled locally. */
	public boolean onAttack(PlayerEntity attacker, Entity target) {
		if (!isEnabled() || fake == null || target != fake) return false;
		attacker.resetTicksSince();
		if (fake.isDead()) return true;

		// Mirrors PlayerEntity#attack; enchantment damage bonuses are server-side and not included
		float progress = attacker.getAttackCooldownProgress(0.5f);
		DamageSource source = mc.world.getDamageSources().playerAttack(attacker);
		float damage = (float) attacker.getAttributeValue(EntityAttributes.ATTACK_DAMAGE) * (0.2f + progress * progress * 0.8f);
		damage += attacker.getWeaponStack().getItem().getBonusAttackDamage(fake, damage, source);
		boolean crit = progress > 0.9f && attacker.fallDistance > 0 && !attacker.isOnGround() && !attacker.isClimbing()
			&& !attacker.isTouchingWater() && !attacker.hasStatusEffect(StatusEffects.BLINDNESS) && !attacker.hasVehicle() && !attacker.isSprinting();
		if (crit) {
			damage *= 1.5f;
			mc.particleManager.addEmitter(fake, ParticleTypes.CRIT);
		}

		damage = DamageUtil.getDamageLeft(fake, damage, source, fake.getArmor(), (float) fake.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS));
		damage = DamageUtil.getInflictedDamage(damage, getProtection());
		playSound(crit ? SoundEvents.ENTITY_PLAYER_ATTACK_CRIT : progress > 0.9f ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_PLAYER_ATTACK_WEAK);
		damage(damage, source);
		return true;
	}

	private void onExplosion(Vec3d center, float power) {
		if (!isEnabled() || fake == null || fake.isDead() || fake.getEntityWorld() != mc.world) return;
		// Blast protection and armor are already applied by the calculator
		float damage = DamageCalculator.explosionDamage(fake, fake.getBoundingBox(), center, power, false, false);
		if (damage > 0) damage(damage, mc.world.getDamageSources().explosion(null, null));
	}

	private void damage(float amount, DamageSource source) {
		// Vanilla hurt cooldown: within 10 ticks of a hit only damage above the last hit counts
		if (invulnerableTicks > 10) {
			if (amount <= lastDamage) return;
			float extra = amount - lastDamage;
			lastDamage = amount;
			amount = extra;
		} else {
			lastDamage = amount;
			invulnerableTicks = 20;
			fake.onDamaged(source);
		}

		if (invincible.get()) {
			playSound(SoundEvents.ENTITY_PLAYER_HURT);
			return;
		}
		float remaining = fake.getHealth() - amount;
		if (remaining > 0) {
			fake.setHealth(remaining);
			playSound(SoundEvents.ENTITY_PLAYER_HURT);
			return;
		}
		fake.setHealth(0);
		deadTicks = 0;
		playSound(SoundEvents.ENTITY_PLAYER_DEATH);
	}

	/** Protection enchantment levels on the armor, which reduce melee damage. */
	private float getProtection() {
		int total = 0;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : fake.getEquippedStack(slot).getEnchantments().getEnchantmentEntries()) {
				if (entry.getKey().matchesKey(Enchantments.PROTECTION)) total += entry.getIntValue();
			}
		}
		return MathHelper.clamp(total, 0, 20);
	}

	private void spawn(Vec3d pos, float yaw) {
		despawn();
		ClientWorld world = mc.world;
		fake = new OtherClientPlayerEntity(world, new GameProfile(UUID.randomUUID(), name.get()));
		fake.setId(ENTITY_ID);
		fake.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0);
		fake.setHeadYaw(yaw);
		fake.setBodyYaw(yaw);
		fake.setHealth(health.getFloat());
		if (copyArmor.get()) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = mc.player.getEquippedStack(slot);
				if (!stack.isEmpty()) fake.equipStack(slot, stack.copy());
			}
		}
		world.addEntity(fake);
		spawnPos = pos;
		spawnYaw = yaw;
		lastDamage = 0;
		invulnerableTicks = 0;
		deadTicks = 0;
	}

	private void despawn() {
		if (fake != null && mc.world != null && fake.getEntityWorld() == mc.world) {
			mc.world.removeEntity(fake.getId(), Entity.RemovalReason.DISCARDED);
		}
		fake = null;
	}

	private void playSound(SoundEvent sound) {
		mc.world.playSoundClient(fake.getX(), fake.getY(), fake.getZ(), sound, SoundCategory.PLAYERS, 1f, 1f, false);
	}
}
