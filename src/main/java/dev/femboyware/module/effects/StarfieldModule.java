package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.resources.Identifier;

/**
 * A field of coloured stars, twinkling and wheeling slowly round the sky, with shooting stars
 * streaking across now and then. Stars can be dots, four-point sparkles or little hearts.
 */
public class StarfieldModule extends SkyFxModule {
	public enum Shape { DOTS, SPARKLES, HEARTS }

	private static final Identifier SPARKLE = texture("sparkle");
	private static final Identifier HEART = texture("heart");
	/** The stars turn round this axis, tipped like the real celestial pole. */
	private static final Vector3f POLE = new Vector3f(0, (float) Math.sin(Math.toRadians(50)), (float) -Math.cos(Math.toRadians(50)));

	private final EnumSetting<Shape> shape = add(new EnumSetting<>("Shape", "Round dots, four-point sparkles or hearts", Shape.SPARKLES));
	private final NumberSetting count = add(new NumberSetting("Count", "How many stars", 900, 100, 4000, 50));
	private final NumberSetting size = add(new NumberSetting("Size", "How big the stars are", 1, 0.2, 4, 0.05));
	private final NumberSetting rotation = add(new NumberSetting("Rotation", "How fast the sky wheels round", 0.3, 0, 5, 0.05));
	private final BoolSetting twinkle = add(new BoolSetting("Twinkle", "Stars flicker gently", true));
	private final BoolSetting daytime = add(new BoolSetting("Daytime", "Show them in daylight too", false));
	private final BoolSetting shooting = add(new BoolSetting("Shooting Stars", "Streaks of light across the sky now and then", true));
	private final NumberSetting rate = add(new NumberSetting("Shooting Rate", "Shooting stars a minute", 10, 1, 120, 1)).visibleWhen(shooting::get);
	private final Palette palette = palette(0xFFFFFFFF, 0xFFFFA8DD);

	private record Meteor(Vector3f start, Vector3f heading, float speed, float life, long born) {}

	private final List<Meteor> meteors = new ArrayList<>();
	private final Random random = new Random();
	private Vector3f[] stars = new Vector3f[0];
	private float[] magnitude = new float[0];
	private float[] phase = new float[0];
	private long lastFrame;

	public StarfieldModule() {
		super("Starfield", "Twinkling coloured stars and shooting stars");
	}

	@Override
	protected void onDisable() {
		meteors.clear();
		lastFrame = 0;
	}

	private void ensureStars(int n) {
		if (stars.length == n) return;
		Random seeded = new Random(4242);
		stars = new Vector3f[n];
		magnitude = new float[n];
		phase = new float[n];
		for (int i = 0; i < n; i++) {
			// Even over the whole sphere; the ones below the horizon end up hidden by the ground
			Vector3f star = new Vector3f((float) seeded.nextGaussian(), (float) seeded.nextGaussian(), (float) seeded.nextGaussian());
			stars[i] = star.normalize();
			// Mostly faint, a few bright
			magnitude[i] = (float) Math.pow(seeded.nextFloat(), 3);
			phase[i] = seeded.nextFloat() * 100;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (!hasSky()) return;
		float visible = clearWeather() * (daytime.get() ? 1 : night());
		if (visible <= 0.01f) return;

		ensureStars(count.getInt());
		float distance = skyDistance();
		double time = time();
		Quaternionf turn = new Quaternionf().rotateAxis((float) (time * rotation.get() * 0.01), POLE);
		Identifier texture = switch (shape.get()) {
			case DOTS -> FxRender.GLOW;
			case SPARKLES -> SPARKLE;
			case HEARTS -> HEART;
		};
		float shapeScale = shape.is(Shape.DOTS) ? 1 : 2.2f;
		VertexConsumer consumer = FxRender.textured(event, texture, true, false);
		Vector3f star = new Vector3f();
		for (int i = 0; i < stars.length; i++) {
			star.set(stars[i]).rotate(turn);
			if (star.y < -0.1f) continue;
			float m = magnitude[i];
			float flicker = twinkle.get() ? 0.55f + 0.45f * (float) Math.sin(time * (2 + m * 3) + phase[i]) : 1;
			float radius = 0.0028f * size.getFloat() * shapeScale * (0.5f + 1.6f * m);
			int color = palette.at(phase[i] % 1);
			sprite(event, consumer, star, distance, radius, 0, FxRender.fade(color, visible * flicker * (0.35f + 0.65f * m)));
		}

		if (!shooting.get()) {
			meteors.clear();
			return;
		}
		if (random.nextFloat() < rate.get() / 60 * delta) {
			Vector3f start = direction(random.nextDouble() * 360, 25 + random.nextDouble() * 50);
			Vector3f heading = new Vector3f(start).cross((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalize();
			// Mostly heading downwards, like a real meteor
			if (heading.y > 0) heading.negate();
			meteors.add(new Meteor(start, heading, 0.5f + random.nextFloat() * 0.5f, 0.6f + random.nextFloat() * 0.7f, now));
		}
		meteors.removeIf(meteor -> (now - meteor.born) / 1e9f > meteor.life);
		VertexConsumer streaks = FxRender.textured(event, FxRender.GLOW, true, false);
		for (Meteor meteor : meteors) {
			float age = (now - meteor.born) / 1e9f;
			float fade = (float) Math.sin(Math.PI * age / meteor.life) * clearWeather();
			for (int k = 0; k < 18; k++) {
				float t = Math.max(0, age - k * 0.012f);
				Vector3f pos = new Vector3f(meteor.heading).mul(t * meteor.speed).add(meteor.start).normalize();
				float tail = 1 - k / 18f;
				sprite(event, streaks, pos, distance, 0.004f * size.getFloat() * (0.4f + tail), 0,
					FxRender.fade(k == 0 ? 0xFFFFFFFF : palette.at(k / 18f), fade * tail));
			}
		}
	}
}
