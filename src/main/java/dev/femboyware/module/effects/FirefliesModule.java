package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Fireflies drifting low over the ground round you, blinking on and off. Like Petal Rain they live in
 * an area that wraps round you, so they stay put in the world as you walk.
 */
public class FirefliesModule extends SkyFxModule {
	private final NumberSetting count = add(new NumberSetting("Count", "How many fireflies", 120, 10, 600, 10));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far round you they fly, in blocks", 20, 6, 48, 1));
	private final NumberSetting size = add(new NumberSetting("Size", "How big their glow is", 1.6, 0.3, 4, 0.05));
	private final NumberSetting blink = add(new NumberSetting("Blink Speed", "How fast they blink", 1, 0.1, 4, 0.05));
	private final BoolSetting nightOnly = add(new BoolSetting("Night Only", "Only come out at night", true));
	private final Palette palette = palette(0xFFE2FF6A, 0xFFFF9AD5);

	private float[] seeds = new float[0];

	public FirefliesModule() {
		super("Fireflies", "Blinking fireflies drift over the ground round you");
	}

	private void ensureSeeds(int n) {
		if (seeds.length == n * 5) return;
		Random random = new Random(512);
		seeds = new float[n * 5];
		for (int i = 0; i < seeds.length; i++) seeds[i] = random.nextFloat();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		float visible = (nightOnly.get() ? night() : 1) * clearWeather();
		if (visible <= 0.01f) return;
		int n = count.getInt();
		ensureSeeds(n);
		Vec3 cam = event.camera().pos;
		float box = radius.getFloat(), side = box * 2;
		double time = time();
		float s = size.getFloat();
		VertexConsumer light = FxRender.textured(event, FxRender.GLOW, true, false);

		for (int i = 0; i < n; i++) {
			int o = i * 5;
			float phase = seeds[o + 2] * 100;
			// Lazy loops, each on its own rhythm
			double wx = seeds[o] * side + Math.sin(time * 0.23 * (1 + seeds[o + 3]) + phase) * 2.5 + Math.cos(time * 0.11 + phase * 2) * 1.5;
			double wz = seeds[o + 1] * side + Math.cos(time * 0.19 * (1 + seeds[o + 4]) + phase) * 2.5 + Math.sin(time * 0.13 + phase * 3) * 1.5;
			float rx = (float) (Mth.positiveModulo(wx - cam.x, side) - box);
			float rz = (float) (Mth.positiveModulo(wz - cam.z, side) - box);
			double x = cam.x + rx, z = cam.z + rz;
			int ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
			double y = ground + 0.4 + (Math.sin(time * 0.4 + phase) + 1) * 1.2;
			if (Math.abs(y - cam.y) > box) continue;
			// Fade out at the edge of the area so the wrapping never shows
			float edge = Math.max(Math.abs(rx), Math.abs(rz));
			float alpha = Math.min(1, (box - edge) / (box * 0.25f)) * visible;
			// Blink: long dim stretches, then a soft flash
			float pulse = (float) Math.pow(Math.max(0, Math.sin(time * blink.get() * (1.2 + seeds[o + 3]) + phase)), 3);
			alpha *= 0.15f + 0.85f * pulse;
			if (alpha <= 0.01f) continue;
			int color = palette.at(seeds[o + 4]);
			float ry = (float) (y - cam.y);
			FxRender.billboard(event, light, rx, ry, rz, 0.35f * s, 0, FxRender.fade(color, alpha * 0.45f));
			FxRender.billboard(event, light, rx, ry, rz, 0.07f * s, 0, FxRender.fade(FxRender.lighten(color, 0.6f), alpha));
		}
	}
}
