package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.Identifier;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Huge swirls of coloured cloud in the sky: a spiral galaxy hanging over the horizon, a vortex
 * slowly drawing everything in overhead, or nebula clouds drifting round the sky. Each is built
 * from soft cloud puffs with bright specks of light among them.
 */
public class SkySwirlsModule extends SkyFxModule {
	public enum Mode { GALAXY, VORTEX, NEBULA }

	private static final Identifier CLOUD = texture("cloud");
	private static final Identifier SPARKLE = texture("sparkle");
	/** Where each nebula clump sits in the sky before it drifts. */
	private static final int CLUMPS = 7;
	private static final double[] CLUMP_AZIMUTH = new double[CLUMPS];
	private static final double[] CLUMP_ELEVATION = new double[CLUMPS];

	static {
		Random random = new Random(7919);
		for (int i = 0; i < CLUMPS; i++) {
			CLUMP_AZIMUTH[i] = i * 360.0 / CLUMPS + random.nextDouble() * 30;
			CLUMP_ELEVATION[i] = 15 + random.nextDouble() * 60;
		}
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Galaxy: a spiral hanging in the sky. Vortex: a swirl overhead pulling inwards. Nebula: clouds drifting round the sky", Mode.VORTEX));
	private final NumberSetting size = add(new NumberSetting("Size", "How much of the sky it covers", 1, 0.3, 2, 0.05));
	private final NumberSetting arms = add(new NumberSetting("Arms", "Spiral arms", 3, 1, 7, 1)).visibleWhen(() -> !mode.is(Mode.NEBULA));
	private final NumberSetting twist = add(new NumberSetting("Twist", "How tightly the arms wind", 1, 0.2, 3, 0.05)).visibleWhen(() -> !mode.is(Mode.NEBULA));
	private final NumberSetting azimuth = add(new NumberSetting("Direction", "Compass bearing of the galaxy: 0 north, 90 east", 200, 0, 360, 5)).visibleWhen(() -> mode.is(Mode.GALAXY));
	private final NumberSetting elevation = add(new NumberSetting("Elevation", "How high in the sky the galaxy hangs, in degrees", 40, 5, 90, 1)).visibleWhen(() -> mode.is(Mode.GALAXY));
	private final NumberSetting tilt = add(new NumberSetting("Tilt", "How edge-on the galaxy is, in degrees", 40, 0, 80, 1)).visibleWhen(() -> mode.is(Mode.GALAXY));
	private final NumberSetting density = add(new NumberSetting("Density", "How many puffs it's made of", 700, 100, 2000, 50));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast it turns", 1, 0, 5, 0.05));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong the clouds are", 0.5, 0.05, 1, 0.05));
	private final BoolSetting sparkles = add(new BoolSetting("Sparkles", "Bright specks of light in the clouds", true));
	private final BoolSetting nightOnly = add(new BoolSetting("Night Only", "Fade out during the day", false));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8A5CFF);

	/** Fixed random numbers for each puff, so the shape holds still between frames. */
	private float[] seedA = new float[0], seedB = new float[0], seedC = new float[0], seedD = new float[0];

	public SkySwirlsModule() {
		super("Sky Swirls", "Huge swirls of coloured cloud in the sky");
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	private void ensureSeeds(int count) {
		if (seedA.length == count) return;
		Random random = new Random(1337);
		seedA = new float[count];
		seedB = new float[count];
		seedC = new float[count];
		seedD = new float[count];
		for (int i = 0; i < count; i++) {
			seedA[i] = random.nextFloat();
			seedB[i] = (float) random.nextGaussian();
			seedC[i] = random.nextFloat();
			seedD[i] = random.nextFloat();
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!hasSky()) return;
		float visible = clearWeather() * (nightOnly.get() ? night() : 1);
		if (visible <= 0.01f) return;
		int count = density.getInt();
		ensureSeeds(count);
		float distance = skyDistance();
		double time = time() * speed.get();
		Vector3f[] positions = new Vector3f[count];
		float[] alphas = new float[count];
		float[] scales = new float[count];
		int[] colors = new int[count];
		for (int i = 0; i < count; i++) place(i, count, time, positions, alphas, scales, colors);

		// Clouds blend normally so they show against a blue day sky; specks are added as light
		float strength = opacity.getFloat() * visible;
		VertexConsumer clouds = FxRender.textured(event, CLOUD, false, false);
		for (int i = 0; i < count; i++) {
			if (positions[i] == null) continue;
			sprite(event, clouds, positions[i], distance, scales[i], seedC[i] * 6.28f, FxRender.fade(colors[i], alphas[i] * strength * 0.55f));
		}
		VertexConsumer light = FxRender.textured(event, FxRender.GLOW, true, false);
		if (!mode.is(Mode.NEBULA)) {
			Vector3f core = center();
			sprite(event, light, core, distance, 0.09f * size.getFloat(), 0, FxRender.fade(FxRender.lighten(palette.at(0), 0.6f), strength));
			sprite(event, light, core, distance, 0.25f * size.getFloat(), 0, FxRender.fade(palette.at(0), strength * 0.5f));
		}
		if (!sparkles.get()) return;
		VertexConsumer specks = FxRender.textured(event, SPARKLE, true, false);
		for (int i = 0; i < count; i += 4) {
			if (positions[i] == null) continue;
			float twinkle = 0.5f + 0.5f * (float) Math.sin(time() * 3 + seedD[i] * 40);
			sprite(event, specks, positions[i], distance, 0.012f * (0.5f + seedD[i]), 0, FxRender.fade(FxRender.lighten(colors[i], 0.6f), alphas[i] * twinkle * visible));
		}
	}

	/** The middle of the galaxy, or straight up for the vortex. */
	private Vector3f center() {
		return mode.is(Mode.GALAXY) ? direction(azimuth.get(), elevation.get()) : new Vector3f(0, 1, 0);
	}

	/** Works out where puff {@code i} is, how strong, how big and what colour. */
	private void place(int i, int count, double time, Vector3f[] positions, float[] alphas, float[] scales, int[] colors) {
		int armCount = arms.getInt();
		float s = size.getFloat();
		switch (mode.get()) {
			case GALAXY -> {
				Vector3f c = center();
				Vector3f u = new Vector3f(c).cross(0, 1, 0);
				if (u.lengthSquared() < 1e-4f) u.set(1, 0, 0);
				u.normalize();
				Vector3f w = new Vector3f(u).cross(c).normalize();
				float r = (float) Math.pow(seedA[i], 0.65);
				int arm = i % armCount;
				double angle = Math.PI * 2 * arm / armCount + r * 3.5 * twist.get() + seedB[i] * 0.35 * (1 - r * 0.5) + time * 0.08;
				float radius = 0.55f * s;
				float x = (float) Math.cos(angle) * r * radius, y = (float) Math.sin(angle) * r * radius * (float) Math.cos(Math.toRadians(tilt.get()));
				positions[i] = new Vector3f(c).add(new Vector3f(u).mul(x)).add(new Vector3f(w).mul(y)).normalize();
				alphas[i] = (1 - r * 0.7f);
				scales[i] = (0.03f + 0.05f * seedD[i]) * s * (1.2f - r * 0.5f);
				colors[i] = palette.at(r * 0.8f + (float) arm / armCount * 0.3f);
			}
			case VORTEX -> {
				// Puffs spiral in towards the centre along fixed arms, so the swirl flows without winding up
				float r = (float) frac(seedA[i] - time * 0.025);
				int arm = i % armCount;
				// Arms stay narrow so the spiral reads, widening a little towards the rim
				double angle = Math.PI * 2 * arm / armCount + Math.log(r + 0.05) * 1.6 * twist.get() + seedB[i] * 0.09 * (0.5 + r) + time * 0.06;
				float spread = 1.3f * s;
				positions[i] = new Vector3f((float) Math.cos(angle) * r * spread, 0.6f, (float) Math.sin(angle) * r * spread).normalize();
				// Fade in at the rim and out as they reach the middle
				alphas[i] = (float) Math.min(1, (1 - r) * 5) * (float) Math.min(1, r * 6);
				scales[i] = (0.05f + 0.06f * seedD[i]) * s * (0.35f + r);
				colors[i] = palette.at(r * 0.9f + (float) arm / armCount * 0.25f);
			}
			case NEBULA -> {
				// A few clumps, each a cluster of puffs round a drifting centre
				int clump = i % CLUMPS;
				Vector3f c = direction(CLUMP_AZIMUTH[clump] + time * 0.6, CLUMP_ELEVATION[clump]);
				Vector3f u = new Vector3f(c).cross(0, 1, 0).normalize();
				Vector3f w = new Vector3f(u).cross(c).normalize();
				float spread = 0.22f * s;
				float x = seedB[i] * spread, y = (seedA[i] - 0.5f) * spread * 1.2f;
				positions[i] = new Vector3f(c).add(new Vector3f(u).mul(x)).add(new Vector3f(w).mul(y)).normalize();
				float breathe = 0.75f + 0.25f * (float) Math.sin(time * 0.5 + i);
				alphas[i] = breathe * (1 - Math.min(1, Math.abs(seedB[i]) * 0.4f));
				scales[i] = (0.04f + 0.07f * seedD[i]) * s;
				colors[i] = palette.at((float) clump / CLUMPS + seedC[i] * 0.3f);
			}
		}
	}

	private static double frac(double value) {
		return value - Math.floor(value);
	}
}
