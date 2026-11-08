package dev.femboyware.util;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/** Client-side copy of the vanilla explosion damage pipeline, used to score crystal positions. */
public final class DamageCalculator {
	public static final float CRYSTAL_POWER = 6f;
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/** Blocks below this blast resistance get destroyed by a crystal, so they don't block damage. */
	private static final float DESTRUCTIBLE_RESISTANCE = 25f;

	private DamageCalculator() {
	}

	/** Damage a crystal sitting on top of {@code base} would deal to {@code target}. */
	public static float crystalDamage(LivingEntity target, BlockPos base, boolean destructible, boolean assumeBestArmor) {
		return crystalDamage(target, Vec3.atBottomCenterOf(base.above()), destructible, assumeBestArmor);
	}

	public static float crystalDamage(LivingEntity target, Vec3 crystal, boolean destructible, boolean assumeBestArmor) {
		return explosionDamage(target, target.getBoundingBox(), crystal, CRYSTAL_POWER, destructible, assumeBestArmor);
	}

	public static float explosionDamage(LivingEntity target, AABB box, Vec3 center, float power, boolean destructible, boolean assumeBestArmor) {
		return explosionDamage(target, box, center, power, destructible, assumeBestArmor, null);
	}

	/** As above, treating {@code assumedSolid} as a full block, for a block about to be placed there. */
	public static float explosionDamage(LivingEntity target, AABB box, Vec3 center, float power, boolean destructible, boolean assumeBestArmor, BlockPos assumedSolid) {
		return explosionDamage(target, box, center, power, destructible, assumeBestArmor, assumedSolid, null);
	}

	/**
	 * As above, also treating {@code assumedAir} as empty: a respawn anchor is removed before it
	 * explodes, so it doesn't shield anyone from its own blast.
	 */
	public static float explosionDamage(LivingEntity target, AABB box, Vec3 center, float power, boolean destructible, boolean assumeBestArmor,
										BlockPos assumedSolid, BlockPos assumedAir) {
		double radius = power * 2.0;
		Vec3 feet = new Vec3((box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0);
		double distance = Math.sqrt(feet.distanceToSqr(center)) / radius;
		if (distance > 1.0) return 0f;

		double impact = (1.0 - distance) * getExposure(target.level(), center, box, destructible, assumedSolid, assumedAir);
		float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1.0);

		if (target instanceof Player) {
			damage = switch (target.level().getDifficulty()) {
				case PEACEFUL -> 0f;
				case EASY -> Math.min(damage / 2f + 1f, damage);
				case NORMAL -> damage;
				case HARD -> damage * 1.5f;
			};
		}
		return applyReductions(target, damage, assumeBestArmor);
	}

	private static float applyReductions(LivingEntity target, float damage, boolean assumeBestArmor) {
		damage = CombatRules.getDamageAfterAbsorb(target, damage, Minecraft.getInstance().level.damageSources().generic(),
			target.getArmorValue(), (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));

		MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
		if (resistance != null) {
			int reduction = (resistance.getAmplifier() + 1) * 5;
			damage = Math.max(damage * (25 - reduction) / 25f, 0f);
		}

		return CombatRules.getDamageAfterMagicAbsorb(damage, getBlastProtection(target, assumeBestArmor));
	}

	/** Enchantment protection factor against explosions: 1 per Protection level, 2 per Blast Protection level. */
	private static float getBlastProtection(LivingEntity target, boolean assumeBestArmor) {
		int total = 0;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack stack = target.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			int pieceProtection = 0;
			for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
				if (entry.getKey().is(Enchantments.PROTECTION)) pieceProtection += entry.getIntValue();
				else if (entry.getKey().is(Enchantments.BLAST_PROTECTION)) pieceProtection += entry.getIntValue() * 2;
			}
			// Blast Protection IV is worth 8
			if (assumeBestArmor) pieceProtection = Math.max(pieceProtection, 8);
			total += pieceProtection;
		}
		return Mth.clamp(total, 0, 20);
	}

	/** Fraction of {@code box} visible from {@code center}, sampled exactly like vanilla. */
	public static float getExposure(Level world, Vec3 center, AABB box, boolean destructible) {
		return getExposure(world, center, box, destructible, null, null);
	}

	private static float getExposure(Level world, Vec3 center, AABB box, boolean destructible, BlockPos assumedSolid, BlockPos assumedAir) {
		double stepX = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
		double stepY = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
		double stepZ = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
		double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
		double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;
		if (stepX < 0 || stepY < 0 || stepZ < 0) return 0f;

		int visible = 0;
		int total = 0;
		for (double x = 0; x <= 1; x += stepX) {
			for (double y = 0; y <= 1; y += stepY) {
				for (double z = 0; z <= 1; z += stepZ) {
					Vec3 sample = new Vec3(
						Mth.lerp(x, box.minX, box.maxX) + offsetX,
						Mth.lerp(y, box.minY, box.maxY),
						Mth.lerp(z, box.minZ, box.maxZ) + offsetZ);
					if (!isObstructed(world, sample, center, destructible, assumedSolid, assumedAir)) visible++;
					total++;
				}
			}
		}
		return total == 0 ? 0f : (float) visible / total;
	}

	private static boolean isObstructed(Level world, Vec3 from, Vec3 to, boolean destructible, BlockPos assumedSolid, BlockPos assumedAir) {
		return BlockGetter.traverseBlocks(from, to, world, (BlockGetter view, BlockPos pos) -> {
			if (pos.equals(assumedSolid)) return Shapes.block().clip(from, to, pos) != null ? Boolean.TRUE : null;
			if (pos.equals(assumedAir)) return null;
			BlockState state = view.getBlockState(pos);
			if (state.isAir()) return null;
			if (destructible && state.getBlock().getExplosionResistance() < DESTRUCTIBLE_RESISTANCE) return null;
			BlockHitResult hit = state.getCollisionShape(view, pos).clip(from, to, pos);
			return hit != null ? Boolean.TRUE : null;
		}, view -> Boolean.FALSE);
	}
}
