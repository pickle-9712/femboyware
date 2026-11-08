package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.Vec3;

/** Celebrates when someone you were fighting dies: lightning, a rising soul, fireworks and more. Only you see it. */
public class KillEffectsModule extends TargetFxModule {
	public enum Effect { SOUL, LIGHTNING, FIREWORK, EXPLOSION, TOTEM, HEARTS }

	public enum Sound { NONE, THUNDER, LEVEL_UP, BELL, CHALLENGE, ORB }

	/** A kill still counts this long after they stopped being your target. */
	private static final long ENGAGE_MEMORY = 3000;

	private final EnumSetting<Effect> effect = add(new EnumSetting<>("Effect", "What plays where they died", Effect.SOUL));
	private final NumberSetting soulTime = add(new NumberSetting("Soul Time", "Seconds the soul takes to rise", 2, 0.5, 5, 0.1)).visibleWhen(() -> effect.is(Effect.SOUL));
	private final EnumSetting<Sound> sound = add(new EnumSetting<>("Sound", "Sound played on a kill", Sound.LEVEL_UP));
	private final BoolSetting particles = add(new BoolSetting("Soul Particles", "Vanilla soul particles burst out too", true)).visibleWhen(() -> effect.is(Effect.SOUL));
	private final Palette palette = palette(0xFFFF77C8, 0xFFFFFFFF);
	private final BoolSetting throughWalls = targetSettings();

	private record Soul(Vec3 pos, float height, long start) {}

	/** When each recent target was last your target. */
	private final Map<LivingEntity, Long> engaged = new HashMap<>();
	private final List<Soul> souls = new ArrayList<>();
	private final Random random = new Random();
	private int nextLightningId = -300000;

	public KillEffectsModule() {
		super("Kill Effects", "Effects when someone you were fighting dies");
	}

	@Override
	protected void onDisable() {
		super.onDisable();
		engaged.clear();
		souls.clear();
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		for (LivingEntity target : targets()) engaged.put(target, now);
		Iterator<Map.Entry<LivingEntity, Long>> iterator = engaged.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<LivingEntity, Long> entry = iterator.next();
			LivingEntity entity = entry.getKey();
			if (entity.isDeadOrDying() || entity.getHealth() <= 0) {
				play(entity);
				iterator.remove();
			} else if (entity.isRemoved() || now - entry.getValue() > ENGAGE_MEMORY) {
				iterator.remove();
			}
		}
	}

	private void play(LivingEntity entity) {
		Vec3 pos = entity.position();
		double x = pos.x, y = pos.y, z = pos.z;
		float height = entity.getBbHeight();
		switch (effect.get()) {
			case SOUL -> {
				souls.add(new Soul(pos, height, System.nanoTime()));
				if (particles.get()) {
					for (int i = 0; i < 25; i++) {
						mc.level.addParticle(ParticleTypes.SOUL, x + random.nextGaussian() * 0.3, y + random.nextDouble() * height, z + random.nextGaussian() * 0.3,
							random.nextGaussian() * 0.02, 0.05 + random.nextDouble() * 0.05, random.nextGaussian() * 0.02);
					}
				}
			}
			case LIGHTNING -> {
				LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, mc.level);
				bolt.setId(nextLightningId--);
				bolt.snapTo(pos);
				// Cosmetic only: no fire, and the bolt discards itself client side after a few ticks
				bolt.setVisualOnly(true);
				mc.level.addEntity(bolt);
			}
			case FIREWORK -> {
				IntList colors = IntList.of(palette.at(0) & 0xFFFFFF, palette.at(0.33f) & 0xFFFFFF, palette.at(0.66f) & 0xFFFFFF);
				FireworkExplosion burst = new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, colors, IntList.of(0xFFFFFF), true, true);
				mc.level.createFireworks(x, y + height + 0.5, z, 0, 0, 0, List.of(burst));
			}
			case EXPLOSION -> mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y + height / 2, z, 0, 0, 0);
			case TOTEM -> {
				for (int i = 0; i < 60; i++) {
					mc.level.addParticle(ParticleTypes.TOTEM_OF_UNDYING, x, y + height / 2, z,
						random.nextGaussian() * 0.5, random.nextDouble() * 0.6, random.nextGaussian() * 0.5);
				}
			}
			case HEARTS -> {
				for (int i = 0; i < 20; i++) {
					mc.level.addParticle(ParticleTypes.HEART, x + random.nextGaussian() * 0.4, y + random.nextDouble() * height + 0.3, z + random.nextGaussian() * 0.4,
						0, 0.1, 0);
				}
			}
		}

		SoundEvent soundEvent = switch (sound.get()) {
			case NONE -> null;
			case THUNDER -> SoundEvents.LIGHTNING_BOLT_THUNDER;
			case LEVEL_UP -> SoundEvents.PLAYER_LEVELUP;
			case BELL -> SoundEvents.BELL_BLOCK;
			case CHALLENGE -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
			case ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
		};
		if (soundEvent != null) mc.level.playLocalSound(x, y, z, soundEvent, SoundSource.PLAYERS, 0.7f, 1f, false);
	}

	/** Souls: a column of light rising out of the body, with orbs spiralling up round it. */
	@Override
	public void onRender3D(Render3DEvent event) {
		if (souls.isEmpty()) return;
		long now = System.nanoTime();
		double duration = soulTime.get();
		souls.removeIf(soul -> (now - soul.start) / 1e9 >= duration);

		VertexConsumer light = FxRender.textured(event, FxRender.GLOW, true, throughWalls.get());
		for (Soul soul : souls) {
			double progress = (now - soul.start) / 1e9 / duration;
			FxRender.glow(event, light, soul.pos.add(0, soul.height * 0.5 + progress * 3.5, 0), 0.9f, FxRender.fade(palette.at(0), fadeAt(progress) * 0.5f));
		}

		VertexConsumer core = FxRender.textured(event, FxRender.GLOW, false, throughWalls.get());
		for (Soul soul : souls) {
			double progress = (now - soul.start) / 1e9 / duration;
			float fade = fadeAt(progress);
			Vec3 center = soul.pos.add(0, soul.height * 0.5 + progress * 3.5, 0);
			FxRender.glow(event, core, center, 0.32f, FxRender.fade(FxRender.lighten(palette.at(0), 0.5f), fade));
			for (int i = 0; i < 6; i++) {
				for (int k = 0; k < 8; k++) {
					double t = progress * 6 - k * 0.04;
					double angle = t * 3 + i * Math.PI / 3;
					double spread = 0.55 * (1 - progress * 0.6);
					double y = soul.height * 0.2 + (t / 6) * 3.5 + i * 0.12;
					float tail = 1 - k / 8f;
					Vec3 pos = soul.pos.add(Math.cos(angle) * spread, y, Math.sin(angle) * spread);
					FxRender.glow(event, core, pos, 0.09f * tail, FxRender.fade(palette.at(i / 6f), fade * tail));
				}
			}
		}
	}

	/** Quick fade in, long fade out. */
	private static float fadeAt(double progress) {
		return (float) (progress < 0.15 ? progress / 0.15 : 1 - (progress - 0.15) / 0.85);
	}
}
