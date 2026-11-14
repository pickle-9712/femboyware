package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.larp.LarpLight;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.world.phys.Vec3;

/**
 * The ground around you after rain, on your screen only, with water that behaves like water: the
 * ground darkens where it's soaked, and puddles lie in smooth patches across blocks. Their surface
 * mirrors the sky at a low angle and shows deep, dark water looking straight down (Fresnel), waves
 * roll across it, the sun glints off the waves, and drops ring out as they land.
 */
public class WetGroundModule extends FxModule {
	/** Each puddle block's surface is split this many ways each side, so the waves and glints have detail. */
	private static final int GRID = 4;

	private final NumberSetting radius = add(new NumberSetting("Radius", "How far round you it's wet", 22, 6, 48, 1));
	private final NumberSetting wetness = add(new NumberSetting("Wetness", "How soaked the ground looks", 0.7, 0.1, 1, 0.05));
	private final NumberSetting puddles = add(new NumberSetting("Puddles", "How much of the ground is standing water (1 floods it all)", 0.45, 0, 1, 0.05));
	private final NumberSetting waves = add(new NumberSetting("Waves", "How choppy the water is", 1, 0, 3, 0.05));
	private final BoolSetting glints = add(new BoolSetting("Sun Glints", "The sun sparkling off the waves", true));
	private final BoolSetting ripples = add(new BoolSetting("Ripples", "Drops landing in the puddles", true));
	private final BoolSetting rain = add(new BoolSetting("Rain", "Streaks of rain falling round you", false));

	private final GroundTops ground = new GroundTops();

	public WetGroundModule() {
		super("Wet Ground", "The ground around you is soaked, with realistic puddles", Category.SKY);
	}

	@Override
	protected void onDisable() {
		ground.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		int r = radius.getInt();
		var tops = ground.around(r);
		if (tops.isEmpty()) return;
		Vec3 cam = event.camera().pos;
		PoseStack.Pose pose = event.matrices().last();
		VertexConsumer quads = FxRender.quads(event, false, false);
		double px = mc.player.getX(), pz = mc.player.getZ();
		double t = time();
		float night = LarpLight.night();
		// What the water mirrors: the sky's colour toward the horizon, and what it shows looking down
		int sky = FxRender.mix(0xFFB9D6F4, 0xFF1A2238, night);
		int deep = FxRender.mix(0xFF16303A, 0xFF05080E, night);
		Vec3 sun = sunDirection();
		float wet = wetness.getFloat(), cover = puddles.getFloat();

		// Soaked ground first, under the water
		for (GroundTops.Top top : tops) {
			float edge = edge(top, px, pz, r);
			if (edge <= 0) continue;
			float x1 = (float) (top.x() - cam.x), z1 = (float) (top.z() - cam.z), x2 = x1 + 1, z2 = z1 + 1;
			float y = (float) (top.y() + 0.012 - cam.y);
			int soak = FxRender.fade(0xFF06101C, 0.55f * wet * edge);
			FxRender.quad(quads, pose, x1, y, z1, soak, x1, y, z2, soak, x2, y, z2, soak, x2, y, z1, soak);
		}

		VertexConsumer light = FxRender.quads(event, true, false);
		float[][] col = new float[GRID + 1][GRID + 1];
		float[][] wave = new float[GRID + 1][GRID + 1];
		for (GroundTops.Top top : tops) {
			float edge = edge(top, px, pz, r);
			if (edge <= 0) continue;
			// Skip blocks with no water anywhere on them
			if (cover < 1 && Noise.fbm(top.x() + 0.5, top.z() + 0.5, 5) < 1 - cover - 0.2f) continue;
			float y = (float) (top.y() + 0.02 - cam.y);
			boolean any = false;
			for (int i = 0; i <= GRID; i++) {
				for (int j = 0; j <= GRID; j++) {
					double wx = top.x() + i / (double) GRID, wz = top.z() + j / (double) GRID;
					// How much water there is here: smooth patches, soft at their shore
					float depth = cover >= 1 ? 1 : Math.max(0, Math.min(1, (Noise.fbm(wx, wz, 5) - (1 - cover)) * 6));
					col[i][j] = depth * edge;
					any |= depth > 0;
					wave[i][j] = waveHeight(wx, wz, t) * waves.getFloat();
				}
			}
			if (!any) continue;
			float step = 1f / GRID;
			for (int i = 0; i < GRID; i++) {
				for (int j = 0; j < GRID; j++) {
					if (col[i][j] + col[i + 1][j] + col[i][j + 1] + col[i + 1][j + 1] <= 0.001f) continue;
					float ax = (float) (top.x() - cam.x) + i * step, az = (float) (top.z() - cam.z) + j * step;
					int[] c = new int[4];
					int[] g = new int[4];
					int[][] corners = {{i, j}, {i, j + 1}, {i + 1, j + 1}, {i + 1, j}};
					for (int k = 0; k < 4; k++) {
						int ci = corners[k][0], cj = corners[k][1];
						float vx = ax + (ci - i) * step, vz = az + (cj - j) * step;
						// The waves tilt the surface: their slope here, from the neighbouring heights
						float sx = wave[Math.min(GRID, ci + 1)][cj] - wave[Math.max(0, ci - 1)][cj];
						float sz = wave[ci][Math.min(GRID, cj + 1)] - wave[ci][Math.max(0, cj - 1)];
						Vec3 normal = new Vec3(-sx * 1.6, 1, -sz * 1.6).normalize();
						Vec3 toEye = new Vec3(-vx, -y, -vz).normalize();
						double cos = Math.max(0, normal.dot(toEye));
						// Schlick's Fresnel: water mirrors more the lower you look across it
						float fresnel = (float) (0.02 + 0.98 * Math.pow(1 - cos, 5));
						int color = FxRender.mix(deep, sky, Math.min(1, fresnel * 1.6f + 0.08f));
						color = LarpLight.shade(color, 0.6f + 0.4f * top.light());
						float alpha = (0.62f + 0.33f * fresnel) * col[ci][cj];
						c[k] = FxRender.fade(color, alpha);
						// The sun's glint: where it reflects off the surface toward you
						float spec = 0;
						if (glints.get() && sun.y > 0) {
							Vec3 reflected = toEye.scale(-1).subtract(normal.scale(2 * toEye.scale(-1).dot(normal)));
							spec = (float) Math.pow(Math.max(0, reflected.dot(sun)), 28) * (1 - night) * col[ci][cj];
						}
						g[k] = FxRender.fade(0xFFFFF6E0, Math.min(1, spec * 1.5f));
					}
					float x1 = ax, z1 = az, x2 = ax + step, z2 = az + step;
					FxRender.quad(quads, pose, x1, y, z1, c[0], x1, y, z2, c[1], x2, y, z2, c[2], x2, y, z1, c[3]);
					if (glints.get() && (g[0] | g[1] | g[2] | g[3]) >>> 24 != 0) {
						FxRender.quad(light, pose, x1, y + 0.001f, z1, g[0], x1, y + 0.001f, z2, g[1], x2, y + 0.001f, z2, g[2], x2, y + 0.001f, z1, g[3]);
					}
				}
			}
			if (ripples.get()) ripple(event, light, top, cam, y, t, edge);
		}
		if (rain.get()) rain(event, cam, t);
	}

	/** A drop landing on this block every so often: rings spreading out and fading, if there's water where it lands. */
	private void ripple(Render3DEvent event, VertexConsumer light, GroundTops.Top top, Vec3 cam, float y, double t, float edge) {
		float seed = Noise.hash(top.x() * 5, top.z() * 11);
		double cycle = 1.2 + seed * 1.4;
		double phase = (t + seed * 9) % cycle / cycle;
		if (phase > 0.6) return;
		float rx = top.x() + 0.2f + 0.6f * Noise.hash(top.z(), top.x() + 3), rz = top.z() + 0.2f + 0.6f * Noise.hash(top.x() + 7, top.z());
		float cover = puddles.getFloat();
		if (cover < 1 && Noise.fbm(rx, rz, 5) - (1 - cover) < 0.08f) return;
		float pr = (float) (phase / 0.6);
		event.matrices().pushPose();
		event.matrices().translate((float) (rx - cam.x), y + 0.002f, (float) (rz - cam.z));
		for (int ring = 0; ring < 2; ring++) {
			float p = pr - ring * 0.25f;
			if (p <= 0) continue;
			float outer = 0.03f + p * 0.3f;
			float alpha = (1 - p) * 0.3f * top.light() * edge;
			FxRender.ring(light, event.matrices().last(), outer * 0.85f, outer, 0, 24, (u, in) -> FxRender.fade(0xFFE6F0FF, alpha));
		}
		event.matrices().popPose();
	}

	/** The water's surface height here, from a few wave trains crossing at different angles and speeds. */
	private static float waveHeight(double x, double z, double t) {
		return (float) (Math.sin(x * 2.1 + z * 0.7 + t * 1.6) * 0.5
			+ Math.sin(x * -0.9 + z * 2.6 + t * 1.3) * 0.35
			+ Math.sin(x * 4.3 - z * 3.1 + t * 2.7) * 0.15) * 0.08f;
	}

	/** Which way the sun is from you, from the time of day: rising in the east, setting in the west. */
	private Vec3 sunDirection() {
		if (mc.level == null) return new Vec3(0, 1, 0);
		double day = (dev.femboyware.util.Mc.dayTime() % 24000) / 24000.0;
		// 0 is sunrise in the day count: the sun's angle round the sky from the east
		double angle = day * Math.PI * 2;
		return new Vec3(Math.cos(angle), Math.sin(angle), 0.15).normalize();
	}

	private static float edge(GroundTops.Top top, double px, double pz, int r) {
		double dist = Math.sqrt((top.x() + 0.5 - px) * (top.x() + 0.5 - px) + (top.z() + 0.5 - pz) * (top.z() + 0.5 - pz));
		return (float) Math.max(0, Math.min(1, (r - dist) / 4));
	}

	private void rain(Render3DEvent event, Vec3 cam, double t) {
		VertexConsumer lines = FxRender.quads(event, false, false);
		PoseStack.Pose pose = event.matrices().last();
		float light = LarpLight.at(cam.x, cam.y + 2, cam.z);
		int color = FxRender.fade(LarpLight.shade(0xFFB8CCE6, light), 0.35f);
		for (int i = 0; i < 300; i++) {
			float hx = Noise.hash(i, 21), hz = Noise.hash(i, 22), hy = Noise.hash(i, 23);
			double fx = wrap(hx * 22 - cam.x, 22), fz = wrap(hz * 22 - cam.z, 22);
			double fy = wrap(hy * 14 - t * 11 - cam.y, 14) - 3;
			float x = (float) fx, y = (float) fy, z = (float) fz, w = 0.008f;
			FxRender.quad(lines, pose, x - w, y, z, color, x + w, y, z, color, x + w, y + 0.5f, z, FxRender.fade(color, 0), x - w, y + 0.5f, z, FxRender.fade(color, 0));
			FxRender.quad(lines, pose, x, y, z - w, color, x, y, z + w, color, x, y + 0.5f, z + w, FxRender.fade(color, 0), x, y + 0.5f, z - w, FxRender.fade(color, 0));
		}
	}

	private static double wrap(double v, double size) {
		return v - Math.floor(v / size + 0.5) * size;
	}
}
