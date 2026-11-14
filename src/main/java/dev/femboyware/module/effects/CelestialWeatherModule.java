package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The weather, but celestial: blazing meteors arcing down the night sky, glittering stardust
 * drifting in the air round you, and rain or snow that glows as it falls. Only on your screen.
 */
public class CelestialWeatherModule extends SkyFxModule {
	public enum Fall { RAIN, SNOW }

	private final BoolSetting meteors = add(new BoolSetting("Meteors", "Blazing meteors with fiery tails across the sky", true));
	private final NumberSetting meteorRate = add(new NumberSetting("Meteor Rate", "Meteors a minute", 8, 1, 60, 1)).visibleWhen(meteors::get);
	private final BoolSetting daytime = add(new BoolSetting("Daytime", "Meteors in daylight too", false)).visibleWhen(meteors::get);
	private final BoolSetting stardust = add(new BoolSetting("Stardust", "Glittering dust drifting in the air round you", true));
	private final NumberSetting dust = add(new NumberSetting("Dust Amount", "How much stardust", 220, 20, 800, 10)).visibleWhen(stardust::get);
	private final BoolSetting glowFall = add(new BoolSetting("Glowing Weather", "Rain or snow that glows as it falls", true));
	private final EnumSetting<Fall> fall = add(new EnumSetting<>("Falls As", "Glowing rain streaks or soft glowing snow", Fall.SNOW)).visibleWhen(glowFall::get);
	private final BoolSetting always = add(new BoolSetting("Always", "Fall even when it isn't raining", false)).visibleWhen(glowFall::get);
	private final NumberSetting amount = add(new NumberSetting("Amount", "How much falls", 500, 50, 2000, 50)).visibleWhen(glowFall::get);
	private final Palette palette = palette(0xFF9FD8FF, 0xFFFFA8F0);

	/** The air round you is a repeating cube this big, so the dust and drops stay put as you walk. */
	private static final float CELL = 24;

	private record Meteor(Vector3f start, Vector3f heading, float speed, float life, float size, long born) {
	}

	private final List<Meteor> sky = new ArrayList<>();
	private final Random random = new Random();
	private float[][] dustSeeds = new float[0][], fallSeeds = new float[0][];
	private long lastFrame;

	public CelestialWeatherModule() {
		super("Celestial Weather", "Meteors, stardust and glowing rain or snow");
	}

	@Override
	protected void onDisable() {
		sky.clear();
		lastFrame = 0;
	}

	private static float[][] seeds(int n, long seed) {
		Random r = new Random(seed);
		float[][] out = new float[n][5];
		for (float[] s : out) for (int k = 0; k < 5; k++) s[k] = r.nextFloat();
		return out;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (mc.level == null) return;
		Vec3 cam = event.camera().pos;
		double time = time();
		if (meteors.get() && hasSky()) drawMeteors(event, now, delta);
		else sky.clear();
		if (stardust.get()) drawDust(event, cam, time);
		float rain = mc.level.getRainLevel(tickDelta());
		float wet = always.get() ? 1 : rain;
		if (glowFall.get() && wet > 0.02f && hasSky()) drawFall(event, cam, time, wet);
	}

	// ---------------------------------------------------------------- meteors

	private void drawMeteors(Render3DEvent event, long now, float delta) {
		float visible = clearWeather() * (daytime.get() ? 1 : night());
		if (visible > 0.05f && random.nextFloat() < meteorRate.get() / 60 * delta) {
			Vector3f start = direction(random.nextDouble() * 360, 35 + random.nextDouble() * 40);
			Vector3f heading = new Vector3f(start).cross((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalize();
			if (heading.y > 0) heading.negate();
			// Now and then a big fireball
			float size = random.nextFloat() < 0.15f ? 2.4f : 0.8f + random.nextFloat() * 0.7f;
			sky.add(new Meteor(start, heading, 0.18f + random.nextFloat() * 0.18f, 1.6f + random.nextFloat() * 1.4f, size, now));
		}
		sky.removeIf(m -> (now - m.born) / 1e9f > m.life);
		if (sky.isEmpty()) return;
		float distance = skyDistance();
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (Meteor m : sky) {
			float age = (now - m.born) / 1e9f;
			float fade = (float) Math.sin(Math.PI * Math.min(1, age / m.life)) * Math.max(visible, 0.3f);
			int parts = 34;
			for (int k = parts - 1; k >= 0; k--) {
				float t = Math.max(0, age - k * 0.022f);
				Vector3f pos = new Vector3f(m.heading).mul(t * m.speed).add(m.start).normalize();
				float tail = 1 - k / (float) parts;
				// White-hot head, then gold, then the palette's colours cooling off down the tail
				int color = k < 2 ? 0xFFFFFFFF : k < 7 ? FxRender.mix(0xFFFFE6A0, 0xFFFF9A3C, k / 7f) : palette.at(k / (float) parts);
				float radius = 0.0045f * m.size * (0.35f + tail * 1.1f);
				sprite(event, glow, pos, distance, radius, 0, FxRender.fade(color, fade * tail * (k < 2 ? 1 : 0.75f)));
			}
			// A halo round the head
			Vector3f head = new Vector3f(m.heading).mul(age * m.speed).add(m.start).normalize();
			sprite(event, glow, head, distance, 0.02f * m.size, 0, FxRender.fade(0xFFFFD8A0, fade * 0.35f));
		}
	}

	// ---------------------------------------------------------------- stardust

	/** Where seed {@code s} is round the camera: fixed in the world, drifting, wrapped into the cube round you. */
	private static Vector3f wrapped(Vec3 cam, double x, double y, double z) {
		return new Vector3f(wrap(x - cam.x), wrap(y - cam.y), wrap(z - cam.z));
	}

	private static float wrap(double v) {
		double m = ((v % CELL) + CELL) % CELL;
		return (float) (m - CELL / 2);
	}

	private void drawDust(Render3DEvent event, Vec3 cam, double time) {
		int n = dust.getInt();
		if (dustSeeds.length != n) dustSeeds = seeds(n, 777);
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (float[] s : dustSeeds) {
			// A slow lazy drift, each mote its own way
			double x = s[0] * CELL + Math.sin(time * 0.21 + s[3] * 40) * 1.5 + time * 0.08;
			double y = s[1] * CELL + Math.sin(time * 0.17 + s[4] * 40) * 1.2 + time * 0.05;
			double z = s[2] * CELL + Math.cos(time * 0.19 + s[3] * 30) * 1.5;
			Vector3f p = wrapped(cam, x, y, z);
			float d = p.length();
			if (d < 0.6f) continue;
			// Fades out at the edge of the cube so you never see it wrap
			float edge = Math.max(0, Math.min(1, (CELL * 0.5f - d) / (CELL * 0.25f)));
			float twinkle = 0.35f + 0.65f * (float) Math.pow(Math.max(0, Math.sin(time * (1.5 + s[4] * 3) + s[3] * 60)), 4);
			int color = palette.at(s[4]);
			FxRender.billboard(event, glow, p.x, p.y, p.z, 0.07f + 0.08f * s[3], 0, FxRender.fade(color, Math.min(1, edge * twinkle * 1.4f)));
			// The bright core of the ones catching the light
			if (twinkle > 0.7f) FxRender.billboard(event, glow, p.x, p.y, p.z, 0.03f, 0, FxRender.fade(0xFFFFFFFF, edge * twinkle));
		}
	}

	// ---------------------------------------------------------------- rain and snow

	private void drawFall(Render3DEvent event, Vec3 cam, double time, float wet) {
		int n = amount.getInt();
		if (fallSeeds.length != n) fallSeeds = seeds(n, 1312);
		boolean snow = fall.is(Fall.SNOW);
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		PoseStack.Pose entry = event.matrices().last();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int shown = (int) (n * Math.min(1, wet));
		for (int i = 0; i < shown; i++) {
			float[] s = fallSeeds[i];
			double speed = snow ? 1.2 + s[3] * 0.8 : 14 + s[3] * 6;
			double x = s[0] * CELL + (snow ? Math.sin(time * 0.8 + s[4] * 30) * 0.6 : time * 0.6);
			double y = s[1] * CELL - time * speed;
			double z = s[2] * CELL + (snow ? Math.cos(time * 0.7 + s[3] * 30) * 0.6 : 0);
			Vector3f p = wrapped(cam, x, y, z);
			float d = p.length();
			if (d < 0.5f) continue;
			// Not under a roof
			pos.set(cam.x + p.x, cam.y + p.y, cam.z + p.z);
			if (!mc.level.canSeeSky(pos)) continue;
			float edge = Math.max(0, Math.min(1, (CELL * 0.5f - d) / (CELL * 0.25f)));
			int color = palette.at(s[4]);
			if (snow) {
				FxRender.billboard(event, glow, p.x, p.y, p.z, 0.12f + 0.08f * s[3], 0, FxRender.fade(color, Math.min(1, edge * 1.3f)));
				FxRender.billboard(event, glow, p.x, p.y, p.z, 0.045f, 0, FxRender.fade(0xFFFFFFFF, edge));
			} else {
				streak(glow, entry, p, 0.7f + s[3] * 0.5f, 0.045f, FxRender.fade(color, Math.min(1, edge * 1.2f)));
			}
		}
	}

	/** A falling streak at {@code p}, {@code length} tall, turned to face the camera. */
	private static void streak(VertexConsumer consumer, PoseStack.Pose entry, Vector3f p, float length, float width, int color) {
		Vector3f along = new Vector3f(0.04f, 1, 0).normalize().mul(length * 0.5f);
		Vector3f side = new Vector3f(along).cross(p);
		if (side.lengthSquared() < 1e-6f) return;
		side.normalize().mul(width);
		FxRender.textured(consumer, entry, p.x - along.x - side.x, p.y - along.y - side.y, p.z - along.z - side.z, 0, 1, color);
		FxRender.textured(consumer, entry, p.x - along.x + side.x, p.y - along.y + side.y, p.z - along.z + side.z, 1, 1, color);
		FxRender.textured(consumer, entry, p.x + along.x + side.x, p.y + along.y + side.y, p.z + along.z + side.z, 1, 0, color);
		FxRender.textured(consumer, entry, p.x + along.x - side.x, p.y + along.y - side.y, p.z + along.z - side.z, 0, 0, color);
	}
}
