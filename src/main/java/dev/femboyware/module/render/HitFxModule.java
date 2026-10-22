package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

import java.util.Random;

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

		ParticleEffect effect = switch (particles.get()) {
			case NONE -> null;
			case CRIT -> ParticleTypes.CRIT;
			case MAGIC -> ParticleTypes.ENCHANTED_HIT;
			case HEARTS -> ParticleTypes.HEART;
			case FLAME -> ParticleTypes.FLAME;
			case TOTEM -> ParticleTypes.TOTEM_OF_UNDYING;
		};
		if (effect != null) {
			for (int i = 0; i < amount.getInt(); i++) {
				double x = target.getX() + (random.nextDouble() - 0.5) * target.getWidth();
				double y = target.getY() + random.nextDouble() * target.getHeight();
				double z = target.getZ() + (random.nextDouble() - 0.5) * target.getWidth();
				mc.world.addParticleClient(effect, x, y, z, (random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 0.3, (random.nextDouble() - 0.5) * 0.4);
			}
		}

		SoundEvent soundEvent = switch (sound.get()) {
			case NONE -> null;
			case BELL -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
			case ORB -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
			case ANVIL -> SoundEvents.BLOCK_ANVIL_LAND;
			case BASS -> SoundEvents.BLOCK_NOTE_BLOCK_BASS.value();
		};
		if (soundEvent != null) {
			mc.world.playSoundClient(target.getX(), target.getY(), target.getZ(), soundEvent, SoundCategory.PLAYERS, 0.8f, 0.9f + random.nextFloat() * 0.2f, false);
		}

		if (lightning.get()) {
			LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, mc.world);
			bolt.setId(nextLightningId--);
			bolt.refreshPositionAfterTeleport(target.getEntityPos());
			// Cosmetic only: no fire, and the bolt discards itself client side after a few ticks
			bolt.setCosmetic(true);
			mc.world.addEntity(bolt);
		}
	}
}
