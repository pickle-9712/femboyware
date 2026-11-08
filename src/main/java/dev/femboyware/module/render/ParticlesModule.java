package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.Random;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

/** A trail of particles around you. */
public class ParticlesModule extends Module {
	public enum Type { HEART, FLAME, SOUL, END_ROD, TOTEM, NOTE, ENCHANT, CLOUD }

	private final EnumSetting<Type> type = add(new EnumSetting<>("Type", "Which particle to spawn", Type.HEART));
	private final NumberSetting amount = add(new NumberSetting("Amount", "Particles per tick", 2, 1, 20, 1));
	private final BoolSetting onlyMoving = add(new BoolSetting("Only Moving", "Only spawn while you move", true));

	private final Random random = new Random();

	public ParticlesModule() {
		super("Particles", "Particle trail around you", Category.COSMETIC);
	}

	@Override
	public void onTick() {
		if (onlyMoving.get() && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4) return;
		ParticleOptions effect = switch (type.get()) {
			case HEART -> ParticleTypes.HEART;
			case FLAME -> ParticleTypes.FLAME;
			case SOUL -> ParticleTypes.SOUL_FIRE_FLAME;
			case END_ROD -> ParticleTypes.END_ROD;
			case TOTEM -> ParticleTypes.TOTEM_OF_UNDYING;
			case NOTE -> ParticleTypes.NOTE;
			case ENCHANT -> ParticleTypes.ENCHANT;
			case CLOUD -> ParticleTypes.CLOUD;
		};
		for (int i = 0; i < amount.getInt(); i++) {
			double x = mc.player.getX() + (random.nextDouble() - 0.5) * 0.8;
			double y = mc.player.getY() + random.nextDouble() * mc.player.getBbHeight();
			double z = mc.player.getZ() + (random.nextDouble() - 0.5) * 0.8;
			mc.level.addParticle(effect, x, y, z, 0, 0.02, 0);
		}
	}
}
