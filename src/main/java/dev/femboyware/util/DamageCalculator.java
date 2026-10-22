package dev.femboyware.util;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

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
		return crystalDamage(target, Vec3d.ofBottomCenter(base.up()), destructible, assumeBestArmor);
	}

	public static float crystalDamage(LivingEntity target, Vec3d crystal, boolean destructible, boolean assumeBestArmor) {
		return explosionDamage(target, target.getBoundingBox(), crystal, CRYSTAL_POWER, destructible, assumeBestArmor);
	}

	public static float explosionDamage(LivingEntity target, Box box, Vec3d center, float power, boolean destructible, boolean assumeBestArmor) {
		return explosionDamage(target, box, center, power, destructible, assumeBestArmor, null);
	}

	/** As above, treating {@code assumedSolid} as a full block, for a block about to be placed there. */
	public static float explosionDamage(LivingEntity target, Box box, Vec3d center, float power, boolean destructible, boolean assumeBestArmor, BlockPos assumedSolid) {
		double radius = power * 2.0;
		Vec3d feet = new Vec3d((box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0);
		double distance = Math.sqrt(feet.squaredDistanceTo(center)) / radius;
		if (distance > 1.0) return 0f;

		double impact = (1.0 - distance) * getExposure(target.getEntityWorld(), center, box, destructible, assumedSolid);
		float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1.0);

		if (target instanceof PlayerEntity) {
			damage = switch (target.getEntityWorld().getDifficulty()) {
				case PEACEFUL -> 0f;
				case EASY -> Math.min(damage / 2f + 1f, damage);
				case NORMAL -> damage;
				case HARD -> damage * 1.5f;
			};
		}
		return applyReductions(target, damage, assumeBestArmor);
	}

	private static float applyReductions(LivingEntity target, float damage, boolean assumeBestArmor) {
		damage = DamageUtil.getDamageLeft(target, damage, MinecraftClient.getInstance().world.getDamageSources().generic(),
			target.getArmor(), (float) target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS));

		StatusEffectInstance resistance = target.getStatusEffect(StatusEffects.RESISTANCE);
		if (resistance != null) {
			int reduction = (resistance.getAmplifier() + 1) * 5;
			damage = Math.max(damage * (25 - reduction) / 25f, 0f);
		}

		return DamageUtil.getInflictedDamage(damage, getBlastProtection(target, assumeBestArmor));
	}

	/** Enchantment protection factor against explosions: 1 per Protection level, 2 per Blast Protection level. */
	private static float getBlastProtection(LivingEntity target, boolean assumeBestArmor) {
		int total = 0;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack stack = target.getEquippedStack(slot);
			if (stack.isEmpty()) continue;
			int pieceProtection = 0;
			for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : stack.getEnchantments().getEnchantmentEntries()) {
				if (entry.getKey().matchesKey(Enchantments.PROTECTION)) pieceProtection += entry.getIntValue();
				else if (entry.getKey().matchesKey(Enchantments.BLAST_PROTECTION)) pieceProtection += entry.getIntValue() * 2;
			}
			// Blast Protection IV is worth 8
			if (assumeBestArmor) pieceProtection = Math.max(pieceProtection, 8);
			total += pieceProtection;
		}
		return MathHelper.clamp(total, 0, 20);
	}

	/** Fraction of {@code box} visible from {@code center}, sampled exactly like vanilla. */
	public static float getExposure(World world, Vec3d center, Box box, boolean destructible) {
		return getExposure(world, center, box, destructible, null);
	}

	private static float getExposure(World world, Vec3d center, Box box, boolean destructible, BlockPos assumedSolid) {
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
					Vec3d sample = new Vec3d(
						MathHelper.lerp(x, box.minX, box.maxX) + offsetX,
						MathHelper.lerp(y, box.minY, box.maxY),
						MathHelper.lerp(z, box.minZ, box.maxZ) + offsetZ);
					if (!isObstructed(world, sample, center, destructible, assumedSolid)) visible++;
					total++;
				}
			}
		}
		return total == 0 ? 0f : (float) visible / total;
	}

	private static boolean isObstructed(World world, Vec3d from, Vec3d to, boolean destructible, BlockPos assumedSolid) {
		return BlockView.raycast(from, to, world, (BlockView view, BlockPos pos) -> {
			if (pos.equals(assumedSolid)) return VoxelShapes.fullCube().raycast(from, to, pos) != null ? Boolean.TRUE : null;
			BlockState state = view.getBlockState(pos);
			if (state.isAir()) return null;
			if (destructible && state.getBlock().getBlastResistance() < DESTRUCTIBLE_RESISTANCE) return null;
			BlockHitResult hit = state.getCollisionShape(view, pos).raycast(from, to, pos);
			return hit != null ? Boolean.TRUE : null;
		}, view -> Boolean.FALSE);
	}
}
