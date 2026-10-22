package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;

import java.util.Random;

/** Blows dying entities apart into a spray of blood and bones. */
public class DismembermentModule extends Module {
	private final BoolSetting playersOnly = add(new BoolSetting("Players Only", "Only for players, not mobs", false));
	private final NumberSetting amount = add(new NumberSetting("Amount", "Particles per body part", 25, 5, 100, 1));
	private final BoolSetting bones = add(new BoolSetting("Bones", "Also throw bones", true));

	private final IntSet handled = new IntOpenHashSet();
	private final Random random = new Random();

	public DismembermentModule() {
		super("Dismemberment", "Gory death effects", Category.COSMETIC);
	}

	@Override
	protected void onDisable() {
		handled.clear();
	}

	@Override
	public void onTick() {
		IntSet dying = new IntOpenHashSet();
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || !living.isDead()) continue;
			if (playersOnly.get() && !(entity instanceof PlayerEntity)) continue;
			dying.add(entity.getId());
			if (handled.contains(entity.getId())) continue;
			burst(living);
		}
		handled.clear();
		handled.addAll(dying);
	}

	private void burst(LivingEntity entity) {
		ParticleEffect blood = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.getDefaultState());
		ParticleEffect bone = new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.BONE));
		double height = entity.getHeight();
		// Head, torso and legs fly apart separately
		double[] parts = {height * 0.85, height * 0.55, height * 0.2};
		for (double partY : parts) {
			double kickX = (random.nextDouble() - 0.5) * 0.4;
			double kickZ = (random.nextDouble() - 0.5) * 0.4;
			for (int i = 0; i < amount.getInt(); i++) {
				double x = entity.getX() + (random.nextDouble() - 0.5) * entity.getWidth();
				double y = entity.getY() + partY + (random.nextDouble() - 0.5) * 0.3;
				double z = entity.getZ() + (random.nextDouble() - 0.5) * entity.getWidth();
				double vx = kickX + (random.nextDouble() - 0.5) * 0.3;
				double vy = random.nextDouble() * 0.4;
				double vz = kickZ + (random.nextDouble() - 0.5) * 0.3;
				mc.world.addParticleClient(blood, x, y, z, vx, vy, vz);
				if (bones.get() && i % 6 == 0) mc.world.addParticleClient(bone, x, y, z, vx, vy + 0.1, vz);
			}
		}
	}
}
