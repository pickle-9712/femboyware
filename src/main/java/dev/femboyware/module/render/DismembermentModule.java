package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Random;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

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
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || !living.isDeadOrDying()) continue;
			if (playersOnly.get() && !(entity instanceof Player)) continue;
			dying.add(entity.getId());
			if (handled.contains(entity.getId())) continue;
			burst(living);
		}
		handled.clear();
		handled.addAll(dying);
	}

	private void burst(LivingEntity entity) {
		ParticleOptions blood = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
		//? if >=26.1 {
		/*ParticleOptions bone = new ItemParticleOption(ParticleTypes.ITEM, Items.BONE);
		*///?} else {
		ParticleOptions bone = new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.BONE));
		//?}
		double height = entity.getBbHeight();
		// Head, torso and legs fly apart separately
		double[] parts = {height * 0.85, height * 0.55, height * 0.2};
		for (double partY : parts) {
			double kickX = (random.nextDouble() - 0.5) * 0.4;
			double kickZ = (random.nextDouble() - 0.5) * 0.4;
			for (int i = 0; i < amount.getInt(); i++) {
				double x = entity.getX() + (random.nextDouble() - 0.5) * entity.getBbWidth();
				double y = entity.getY() + partY + (random.nextDouble() - 0.5) * 0.3;
				double z = entity.getZ() + (random.nextDouble() - 0.5) * entity.getBbWidth();
				double vx = kickX + (random.nextDouble() - 0.5) * 0.3;
				double vy = random.nextDouble() * 0.4;
				double vz = kickZ + (random.nextDouble() - 0.5) * 0.3;
				mc.level.addParticle(blood, x, y, z, vx, vy, vz);
				if (bones.get() && i % 6 == 0) mc.level.addParticle(bone, x, y, z, vx, vy + 0.1, vz);
			}
		}
	}
}
