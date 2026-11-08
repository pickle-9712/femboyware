package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.Random;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;

/** Client-side effects when you hit something: particles, sounds and lightning. */
public class HitFxModule extends Module {
	public enum Particles { NONE, CRIT, MAGIC, HEARTS, FLAME, TOTEM }

	public enum Sound { NONE, BELL, ORB, ANVIL, BASS }

	private final EnumSetting<Particles> particles = add(new EnumSetting<>("Particles", "Particles on hit", Particles.MAGIC));
	private final NumberSetting amount = add(new NumberSetting("Amount", "Particles per hit", 15, 1, 60, 1)).visibleWhen(() -> !particles.is(Particles.NONE));
	private final EnumSetting<Sound> sound = add(new EnumSetting<>("Sound", "Sound on hit", Sound.NONE));
	private final BoolSetting lightning = add(new BoolSetting("Lightning", "Strike the target with fake lightning", false));

	private final Random random = new Random();
	private int nextLightningId = -200000;

	public HitFxModule() {
		super("Hit Fx", "Effects when you hit entities", Category.COSMETIC);
	}

	@Override
	public void onPreAttack(Entity target) {
		if (!(target instanceof LivingEntity)) return;

		ParticleOptions effect = switch (particles.get()) {
			case NONE -> null;
			case CRIT -> ParticleTypes.CRIT;
			case MAGIC -> ParticleTypes.ENCHANTED_HIT;
			case HEARTS -> ParticleTypes.HEART;
			case FLAME -> ParticleTypes.FLAME;
			case TOTEM -> ParticleTypes.TOTEM_OF_UNDYING;
		};
		if (effect != null) {
			for (int i = 0; i < amount.getInt(); i++) {
				double x = target.getX() + (random.nextDouble() - 0.5) * target.getBbWidth();
				double y = target.getY() + random.nextDouble() * target.getBbHeight();
				double z = target.getZ() + (random.nextDouble() - 0.5) * target.getBbWidth();
				mc.level.addParticle(effect, x, y, z, (random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 0.3, (random.nextDouble() - 0.5) * 0.4);
			}
		}

		SoundEvent soundEvent = switch (sound.get()) {
			case NONE -> null;
			case BELL -> SoundEvents.NOTE_BLOCK_BELL.value();
			case ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
			case ANVIL -> SoundEvents.ANVIL_LAND;
			case BASS -> SoundEvents.NOTE_BLOCK_BASS.value();
		};
		if (soundEvent != null) {
			mc.level.playLocalSound(target.getX(), target.getY(), target.getZ(), soundEvent, SoundSource.PLAYERS, 0.8f, 0.9f + random.nextFloat() * 0.2f, false);
		}

		if (lightning.get()) {
			LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, mc.level);
			bolt.setId(nextLightningId--);
			bolt.snapTo(target.position());
			// Cosmetic only: no fire, and the bolt discards itself client side after a few ticks
			bolt.setVisualOnly(true);
			mc.level.addEntity(bolt);
		}
	}
}
