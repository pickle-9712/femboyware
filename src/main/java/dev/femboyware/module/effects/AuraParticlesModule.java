package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A constant gentle stream of hearts, petals, sparkles or glowing motes rising round you. */
public class AuraParticlesModule extends PlayerFxModule {
	public enum Kind { HEARTS, PETALS, SPARKLES, MOTES }

	private static final Identifier HEART = Identifier.of("femboyware", "textures/sky/heart.png");
	private static final Identifier PETAL = Identifier.of("femboyware", "textures/sky/petal.png");
	private static final Identifier SPARKLE = Identifier.of("femboyware", "textures/sky/sparkle.png");
	private static final int MAX = 600;

	private final EnumSetting<Kind> kind = add(new EnumSetting<>("Kind", "Hearts, petals, sparkles or soft glowing motes", Kind.HEARTS));
	private final NumberSetting rate = add(new NumberSetting("Rate", "Particles a second", 8, 1, 60, 1));
	private final NumberSetting size = add(new NumberSetting("Size", "How big they are", 1, 0.3, 3, 0.05));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far round you they appear", 0.7, 0.2, 2, 0.05));
	private final NumberSetting life = add(new NumberSetting("Life", "Seconds each one lasts", 2, 0.5, 6, 0.1));
	private final BoolSetting onlyMoving = add(new BoolSetting("Only Moving", "Only while you're walking", false));
	private final Palette palette = palette(0xFFFF77C8, 0xFFFFB6E1);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	private record Particle(Vec3d start, double vx, double vz, float rise, long born, float phase, float tint) {}

	private final List<Particle> particles = new ArrayList<>();
	private final Random random = new Random();
	private long lastFrame;
	private float owed;

	public AuraParticlesModule() {
		super("Aura Particles", "Hearts, petals or sparkles rising round you");
	}

	@Override
	public String getInfo() {
		return kind.getDisplayName();
	}

	@Override
	protected void onDisable() {
		particles.clear();
		lastFrame = 0;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		float tickDelta = tickDelta();
		long lifetime = (long) (life.get() * 1e9);
		particles.removeIf(p -> now - p.born > lifetime);

		owed += delta * rate.getFloat();
		int spawn = (int) owed;
		owed -= spawn;
		for (PlayerEntity player : wearers(true)) {
			if (onlyMoving.get() && player.getVelocity().horizontalLengthSquared() < 0.002) continue;
			Vec3d feet = player.getLerpedPos(tickDelta);
			for (int i = 0; i < spawn && particles.size() < MAX; i++) {
				double angle = random.nextDouble() * Math.PI * 2;
				double r = radius.get() * (0.5 + random.nextDouble() * 0.5);
				Vec3d start = feet.add(Math.cos(angle) * r, random.nextDouble() * player.getHeight() * 0.8, Math.sin(angle) * r);
				particles.add(new Particle(start, Math.cos(angle) * 0.08, Math.sin(angle) * 0.08, 0.35f + random.nextFloat() * 0.35f,
					now, random.nextFloat() * 100, random.nextFloat()));
			}
		}
		if (particles.isEmpty()) return;

		Identifier texture = switch (kind.get()) {
			case HEARTS -> HEART;
			case PETALS -> PETAL;
			case SPARKLES -> SPARKLE;
			case MOTES -> FxRender.GLOW;
		};
		boolean light = kind.is(Kind.SPARKLES) || kind.is(Kind.MOTES);
		VertexConsumer consumer = FxRender.textured(event, texture, light, false);
		Vec3d cam = event.camera().pos;
		float s = size.getFloat();
		for (Particle p : particles) {
			float age = (now - p.born) / 1e9f;
			float progress = age / life.getFloat();
			// Pop in, drift up and out with a little sway, then fade
			float alpha = Math.min(1, progress * 8) * (1 - progress);
			double sway = Math.sin(age * 3 + p.phase) * 0.12;
			double x = p.start.x + p.vx * age + sway * p.vz * 8;
			double y = p.start.y + p.rise * age;
			double z = p.start.z + p.vz * age - sway * p.vx * 8;
			float radius = 0.09f * s * (0.6f + 0.4f * Math.min(1, progress * 5));
			float roll = kind.is(Kind.PETALS) ? age * 2 + p.phase : (float) Math.sin(age * 2 + p.phase) * 0.25f;
			FxRender.billboard(event, consumer, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), radius, roll, FxRender.fade(palette.at(p.tint), alpha));
		}
	}
}
