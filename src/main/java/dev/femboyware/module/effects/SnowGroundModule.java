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
 * Snow lying on the ground around you, on your screen only. It lies in soft drifts rather than a
 * flat sheet: its depth rises and falls smoothly across blocks, it's lit by the sun on the slopes
 * that face it and a cold blue in the hollows, it slumps over the edges of steps, and ice crystals
 * glint here and there.
 */
public class SnowGroundModule extends FxModule {
	/** Each block's snow is split this many ways each side, so the drifts can roll. */
	private static final int GRID = 3;

	private final NumberSetting radius = add(new NumberSetting("Radius", "How far round you it snows", 24, 6, 48, 1));
	private final NumberSetting depth = add(new NumberSetting("Depth", "How deep the snow lies", 0.1, 0.02, 0.4, 0.01));
	private final NumberSetting drifts = add(new NumberSetting("Drifts", "How much the depth rolls", 1.3, 0, 2, 0.05));
	private final NumberSetting cover = add(new NumberSetting("Cover", "How completely it covers the ground", 1, 0.3, 1, 0.05));
	private final BoolSetting glints = add(new BoolSetting("Glints", "Ice crystals catching the light", true));
	private final BoolSetting falling = add(new BoolSetting("Snowfall", "Flakes falling round you", true));

	private final GroundTops ground = new GroundTops();

	public SnowGroundModule() {
		super("Snow Ground", "Snow lies in drifts on the ground around you", Category.SKY);
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
		Vec3 sun = sunDirection();
		float night = LarpLight.night();
		float step = 1f / GRID;
		float[][] h = new float[GRID + 1][GRID + 1];
		int[][] c = new int[GRID + 1][GRID + 1];
		for (GroundTops.Top top : tops) {
			if (cover.getFloat() < 1 && Noise.fbm(top.x() + 0.5, top.z() + 0.5, 4) > cover.getFloat()) continue;
			double dist = Math.sqrt((top.x() + 0.5 - px) * (top.x() + 0.5 - px) + (top.z() + 0.5 - pz) * (top.z() + 0.5 - pz));
			float edge = (float) Math.max(0, Math.min(1, (r - dist) / 4));
			if (edge <= 0) continue;
			float[] n = top.neighbours();
			// Where the ground steps down beside it, the snow slumps toward that edge
			boolean dropN = !(n[0] >= top.y() - 0.01f), dropS = !(n[1] >= top.y() - 0.01f), dropW = !(n[2] >= top.y() - 0.01f), dropE = !(n[3] >= top.y() - 0.01f);
			for (int i = 0; i <= GRID; i++) {
				for (int j = 0; j <= GRID; j++) {
					double wx = top.x() + i * step, wz = top.z() + j * step;
					float d = height(wx, wz);
					float slump = 1;
					if (dropW && i == 0 || dropE && i == GRID || dropN && j == 0 || dropS && j == GRID) slump = 0.3f;
					h[i][j] = d * slump;
					// Lit by how the drift's slope faces the sun, with cold blue in the shade
					float sx = height(wx + 0.15, wz) - height(wx - 0.15, wz), sz = height(wx, wz + 0.15) - height(wx, wz - 0.15);
					Vec3 normal = new Vec3(-sx * 22, 1, -sz * 22).normalize();
					float lambert = (float) Math.max(0, normal.dot(sun));
					float lit = 0.3f + 0.7f * lambert * (1 - night);
					int tone = FxRender.mix(0xFFB4C6DE, 0xFFFBFDFF, lit);
					// Hollows hold a little blue shadow
					tone = FxRender.mix(tone, 0xFF9DB4D4, Math.max(0, 0.5f - d / depth.getFloat()) * 0.5f);
					c[i][j] = FxRender.fade(LarpLight.shade(tone, top.light()), edge);
				}
			}
			float bx = (float) (top.x() - cam.x), bz = (float) (top.z() - cam.z), base = (float) (top.y() - cam.y);
			for (int i = 0; i < GRID; i++) {
				for (int j = 0; j < GRID; j++) {
					float x1 = bx + i * step, z1 = bz + j * step, x2 = x1 + step, z2 = z1 + step;
					FxRender.quad(quads, pose, x1, base + h[i][j], z1, c[i][j], x1, base + h[i][j + 1], z2, c[i][j + 1],
						x2, base + h[i + 1][j + 1], z2, c[i + 1][j + 1], x2, base + h[i + 1][j], z1, c[i + 1][j]);
				}
			}
			// The snow's side where the ground steps down, so it has thickness
			for (int k = 0; k < GRID; k++) {
				float a = bx + k * step, b = a + step, za = bz + k * step, zb = za + step;
				if (dropN) side(quads, pose, a, base + h[k][0], bz, b, base + h[k + 1][0], bz, base, c[k][0], c[k + 1][0]);
				if (dropS) side(quads, pose, b, base + h[k + 1][GRID], bz + 1, a, base + h[k][GRID], bz + 1, base, c[k + 1][GRID], c[k][GRID]);
				if (dropW) side(quads, pose, bx, base + h[0][k + 1], zb, bx, base + h[0][k], za, base, c[0][k + 1], c[0][k], true);
				if (dropE) side(quads, pose, bx + 1, base + h[GRID][k], za, bx + 1, base + h[GRID][k + 1], zb, base, c[GRID][k], c[GRID][k + 1], true);
			}
		}
		if (glints.get()) glints(event, tops, cam);
		if (falling.get()) snowfall(event, cam);
	}

	private static void side(VertexConsumer quads, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, float base, int c1, int c2) {
		side(quads, pose, x1, y1, z1, x2, y2, z2, base, c1, c2, false);
	}

	/** A strip of the snow's side, a little darker than its top. */
	private static void side(VertexConsumer quads, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, float base, int c1, int c2, boolean unused) {
		int d1 = FxRender.mix(c1, 0xFF8EA4C2, 0.25f) & 0x00FFFFFF | c1 & 0xFF000000, d2 = FxRender.mix(c2, 0xFF8EA4C2, 0.25f) & 0x00FFFFFF | c2 & 0xFF000000;
		FxRender.quad(quads, pose, x1, base, z1, d1, x1, y1, z1, d1, x2, y2, z2, d2, x2, base, z2, d2);
	}

	/** How deep the snow is at a point: its depth rolling in drifts across the ground. */
	private float height(double x, double z) {
		float roll = Noise.fbm(x, z, 2.6);
		return depth.getFloat() * Math.max(0.25f, 1 + (roll - 0.5f) * 1.6f * drifts.getFloat());
	}

	private void glints(Render3DEvent event, java.util.List<GroundTops.Top> tops, Vec3 cam) {
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		double t = time();
		float day = 1 - LarpLight.night() * 0.7f;
		for (GroundTops.Top top : tops) {
			for (int g = 0; g < 2; g++) {
				float hsh = Noise.hash(top.x() * 3 + g * 101, top.z() * 7);
				if (hsh > 0.22f) continue;
				// Each crystal flashes briefly as the angle catches it
				float twinkle = (float) Math.max(0, Math.sin(t * (1.2 + hsh * 5) + hsh * 40 + cam.x * 0.7 + cam.z * 0.5));
				if (twinkle < 0.85f) continue;
				float gx = top.x() + 0.1f + 0.8f * Noise.hash(top.z() + g, top.x()), gz = top.z() + 0.1f + 0.8f * Noise.hash(top.x() + 9, top.z() + g);
				float gy = top.y() + height(gx, gz) + 0.01f;
				FxRender.billboard(event, glow, (float) (gx - cam.x), (float) (gy - cam.y), (float) (gz - cam.z), 0.035f, 0,
					FxRender.fade(0xFFFFFFFF, (twinkle - 0.85f) / 0.15f * top.light() * day));
			}
		}
	}

	/** Flakes drifting down round you, tumbling a little as they fall, wrapped round so there are always some. */
	private void snowfall(Render3DEvent event, Vec3 cam) {
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, false, false);
		double t = time();
		float light = LarpLight.at(cam.x, cam.y + 2, cam.z);
		for (int i = 0; i < 320; i++) {
			float hx = Noise.hash(i, 1), hz = Noise.hash(i, 2), hy = Noise.hash(i, 3);
			double speed = 0.6 + hy * 0.5;
			double fx = wrap(hx * 26 + Math.sin(t * 0.9 + i) * 0.35 + t * 0.25 - cam.x, 26);
			double fz = wrap(hz * 26 + Math.cos(t * 0.7 + i * 1.3) * 0.35 - cam.z, 26);
			double fy = wrap(hy * 18 - t * speed - cam.y, 18) - 5;
			float size = 0.025f + hx * 0.025f;
			FxRender.billboard(event, glow, (float) fx, (float) fy, (float) fz, size, 0, FxRender.fade(LarpLight.shade(0xFFFFFFFF, light), 0.9f));
		}
	}

	/** Which way the sun is from you, from the time of day. */
	private Vec3 sunDirection() {
		if (mc.level == null) return new Vec3(0, 1, 0);
		double angle = (dev.femboyware.util.Mc.dayTime() % 24000) / 24000.0 * Math.PI * 2;
		Vec3 sun = new Vec3(Math.cos(angle), Math.sin(angle), 0.25);
		// At night, the moon's on the other side
		return (sun.y < 0 ? sun.scale(-1) : sun).normalize();
	}

	private static double wrap(double v, double size) {
		return v - Math.floor(v / size + 0.5) * size;
	}

	/** A steady pseudo-random 0..1 for a spot. */
	static float hash(int x, int z) {
		return Noise.hash(x, z);
	}
}
