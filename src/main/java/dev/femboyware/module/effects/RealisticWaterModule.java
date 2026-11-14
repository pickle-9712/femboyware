package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.larp.LarpLight;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Lakes and seas round you look like real water, on your screen only: rolling waves, the sky
 * mirrored across the surface at low angles and deep blue-green looking straight down (Fresnel),
 * the sun glittering off the wave tops, and white foam lapping at the shore.
 */
public class RealisticWaterModule extends FxModule {
	private static final int GRID = 4;

	private final NumberSetting radius = add(new NumberSetting("Radius", "How far round you the water's done", 24, 8, 48, 1));
	private final NumberSetting waves = add(new NumberSetting("Waves", "How rough the water is", 1, 0, 3, 0.05));
	private final NumberSetting clarity = add(new NumberSetting("Clarity", "How much you see into it looking down (less is a deeper colour)", 0.4, 0, 1, 0.05));
	private final BoolSetting glints = add(new BoolSetting("Sun Glints", "The sun glittering off the waves", true));
	private final BoolSetting foam = add(new BoolSetting("Shore Foam", "White foam where the water meets land", true));

	/** A water column: its top, which sides are shore, its light, how deep it is and the colour of its bed. */
	private record Surface(int x, int z, float y, boolean[] shore, float light, int depth, int bed) {
	}

	private final List<Surface> surfaces = new ArrayList<>();
	private int cx = Integer.MIN_VALUE, cz, scannedRadius;
	private long scannedAt;

	public RealisticWaterModule() {
		super("Realistic Water", "Waves, reflections, sun glints and foam on water", Category.SKY);
	}

	@Override
	protected void onDisable() {
		surfaces.clear();
		cx = Integer.MIN_VALUE;
	}

	private void scan() {
		int r = radius.getInt();
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		long now = System.currentTimeMillis();
		if (Math.abs(px - cx) <= 2 && Math.abs(pz - cz) <= 2 && r == scannedRadius && now - scannedAt < 1500) return;
		cx = px;
		cz = pz;
		scannedRadius = r;
		scannedAt = now;
		surfaces.clear();
		int size = r * 2 + 3;
		float[][] tops = new float[size][size];
		for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) tops[dx + r + 1][dz + r + 1] = water(px + dx, pz + dz);
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				int i = dx + r + 1, j = dz + r + 1;
				float y = tops[i][j];
				if (Float.isNaN(y)) continue;
				// North, south, west, east: land (or a different level) there means shore
				float[] n = {tops[i][j - 1], tops[i][j + 1], tops[i - 1][j], tops[i + 1][j]};
				boolean[] shore = new boolean[4];
				for (int k = 0; k < 4; k++) shore[k] = Float.isNaN(n[k]) || Math.abs(n[k] - y) > 0.05f;
				// Down through the water to whatever it lies on
				BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(px + dx, (int) Math.floor(y - 0.01), pz + dz);
				int depth = 0;
				while (depth < 12 && !mc.level.getFluidState(at).isEmpty()) {
					at.move(0, -1, 0);
					depth++;
				}
				int bed = mc.level.getBlockState(at).getMapColor(mc.level, at).col;
				if (bed == 0) bed = 0x6A5E48;
				surfaces.add(new Surface(px + dx, pz + dz, y, shore, LarpLight.at(px + dx + 0.5, y + 0.5, pz + dz + 0.5), depth, 0xFF000000 | bed));
			}
		}
	}

	/** The top of the water in a column open to the sky, or NaN if it's not water on top. */
	private float water(int x, int z) {
		int height = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		BlockPos pos = new BlockPos(x, height - 1, z);
		FluidState fluid = mc.level.getFluidState(pos);
		if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) return Float.NaN;
		return pos.getY() + fluid.getHeight(mc.level, pos);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.player == null || mc.level == null) return;
		scan();
		if (surfaces.isEmpty()) return;
		Vec3 cam = event.camera().pos;
		PoseStack.Pose pose = event.matrices().last();
		// Solid, so it stands in for the game's water (drawn after it in some versions, which would cover it)
		VertexConsumer quads = FxRender.solidQuads(event);
		VertexConsumer light = FxRender.quads(event, true, false);
		VertexConsumer soft = FxRender.quads(event, false, false);
		double t = time();
		float night = LarpLight.night();
		int sky = FxRender.mix(0xFFC2DCF5, 0xFF16203A, night);
		int deep = FxRender.mix(FxRender.mix(0xFF0B3A4A, 0xFF2A8A9A, clarity.getFloat()), 0xFF04080E, night);
		Vec3 sun = sunDirection();
		int r = radius.getInt();
		double px = mc.player.getX(), pz = mc.player.getZ();
		float[][] wave = new float[GRID + 1][GRID + 1];
		float step = 1f / GRID;
		for (Surface s : surfaces) {
			double dist = Math.hypot(s.x + 0.5 - px, s.z + 0.5 - pz);
			if (dist > r) continue;
			float edge = 1;
			// What you see looking in: the bed through the water, lost in the deep colour the deeper it goes
			float murk = (float) (1 - Math.exp(-s.depth * (1.3 - clarity.getFloat())));
			int below = FxRender.mix(LarpLight.shade(FxRender.mix(s.bed, 0xFF2A8A9A, 0.35f), 0.7f), deep, murk);
			// Just over the game's own water, so it shows through where we're see-through
			float y = (float) (s.y + 0.004 - cam.y);
			// From underneath it's the game's own water
			if (cam.y < s.y) continue;
			for (int i = 0; i <= GRID; i++) for (int j = 0; j <= GRID; j++) wave[i][j] = waveHeight(s.x + i * step, s.z + j * step, t) * waves.getFloat();
			for (int i = 0; i < GRID; i++) {
				for (int j = 0; j < GRID; j++) {
					float ax = (float) (s.x - cam.x) + i * step, az = (float) (s.z - cam.z) + j * step;
					int[][] corners = {{i, j}, {i, j + 1}, {i + 1, j + 1}, {i + 1, j}};
					int[] c = new int[4], g = new int[4];
					for (int k = 0; k < 4; k++) {
						int ci = corners[k][0], cj = corners[k][1];
						float vx = ax + (ci - i) * step, vz = az + (cj - j) * step;
						float sx = wave[Math.min(GRID, ci + 1)][cj] - wave[Math.max(0, ci - 1)][cj];
						float sz = wave[ci][Math.min(GRID, cj + 1)] - wave[ci][Math.max(0, cj - 1)];
						Vec3 normal = new Vec3(-sx * 1.8, 1, -sz * 1.8).normalize();
						Vec3 toEye = new Vec3(-vx, -y, -vz).normalize();
						double cos = Math.max(0, normal.dot(toEye));
						float fresnel = (float) (0.02 + 0.98 * Math.pow(1 - cos, 5));
						int color = FxRender.mix(below, sky, Math.min(1, fresnel * 1.1f + 0.03f));
						// Wave crests catch a little more light
						color = FxRender.lighten(color, Math.max(0, wave[ci][cj]) * 2.5f);
						color = LarpLight.shade(color, 0.55f + 0.45f * s.light);
						c[k] = FxRender.opaque(color);
						float spec = 0;
						if (glints.get() && sun.y > 0) {
							Vec3 in = toEye.scale(-1);
							Vec3 reflected = in.subtract(normal.scale(2 * in.dot(normal)));
							spec = (float) Math.pow(Math.max(0, reflected.dot(sun)), 40) * (1 - night) * edge;
							// Glitter: the odd wave facet flashing
							spec *= 0.6f + 0.8f * Noise.hash((int) Math.floor((s.x + ci * step) * 8 + t * 3), (int) Math.floor((s.z + cj * step) * 8));
						}
						g[k] = FxRender.fade(0xFFFFF4DA, Math.min(1, spec * 1.6f));
					}
					float x1 = ax, z1 = az, x2 = ax + step, z2 = az + step;
					FxRender.quad(quads, pose, x1, y, z1, c[0], x1, y, z2, c[1], x2, y, z2, c[2], x2, y, z1, c[3]);
					if (glints.get() && ((g[0] | g[1] | g[2] | g[3]) >>> 24) != 0)
						FxRender.quad(light, pose, x1, y + 0.001f, z1, g[0], x1, y + 0.001f, z2, g[1], x2, y + 0.001f, z2, g[2], x2, y + 0.001f, z1, g[3]);
				}
			}
			if (foam.get()) foam(soft, pose, s, cam, y, t, edge);
		}
	}

	/** Foam along the block's shore sides: a band that laps in and out. */
	private static void foam(VertexConsumer quads, PoseStack.Pose pose, Surface s, Vec3 cam, float y, double t, float edge) {
		float x1 = (float) (s.x - cam.x), z1 = (float) (s.z - cam.z), x2 = x1 + 1, z2 = z1 + 1, fy = y + 0.002f;
		float lap = 0.14f + 0.08f * (float) Math.sin(t * 1.4 + s.x * 0.7 + s.z * 0.9);
		int white = FxRender.fade(LarpLight.shade(0xFFF4FAFF, 0.5f + 0.5f * s.light), 0.75f * edge), clear = FxRender.fade(white, 0);
		// North (z1), south (z2), west (x1), east (x2)
		if (s.shore[0]) FxRender.quad(quads, pose, x1, fy, z1, white, x1, fy, z1 + lap, clear, x2, fy, z1 + lap, clear, x2, fy, z1, white);
		if (s.shore[1]) FxRender.quad(quads, pose, x1, fy, z2 - lap, clear, x1, fy, z2, white, x2, fy, z2, white, x2, fy, z2 - lap, clear);
		if (s.shore[2]) FxRender.quad(quads, pose, x1, fy, z1, white, x1, fy, z2, white, x1 + lap, fy, z2, clear, x1 + lap, fy, z1, clear);
		if (s.shore[3]) FxRender.quad(quads, pose, x2 - lap, fy, z1, clear, x2 - lap, fy, z2, clear, x2, fy, z2, white, x2, fy, z1, white);
	}

	private static float waveHeight(double x, double z, double t) {
		return (float) (Math.sin(x * 1.3 + z * 0.5 + t * 1.2) * 0.5
			+ Math.sin(x * -0.7 + z * 1.9 + t * 1.0) * 0.35
			+ Math.sin(x * 3.7 - z * 2.9 + t * 2.3) * 0.15) * 0.07f;
	}

	private Vec3 sunDirection() {
		double day = (dev.femboyware.util.Mc.dayTime() % 24000) / 24000.0;
		double angle = day * Math.PI * 2;
		return new Vec3(Math.cos(angle), Math.sin(angle), 0.15).normalize();
	}
}
