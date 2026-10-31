package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Sakura petals, hearts, snow or sparkles drifting down all round you. They live in a box that
 * wraps round the camera, so they hang still in the world as you walk yet never run out.
 */
public class PetalRainModule extends SkyFxModule {
	public enum Mode { SAKURA, HEARTS, SNOW, SPARKLES }

	private static final Identifier PETAL = texture("petal");
	private static final Identifier HEART = texture("heart");
	private static final Identifier SPARKLE = texture("sparkle");

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Cherry blossom petals, hearts, snow or sparkles", Mode.SAKURA));
	private final NumberSetting amount = add(new NumberSetting("Amount", "How many are falling round you", 400, 50, 2000, 50));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far round you they fall, in blocks", 18, 6, 48, 1));
	private final NumberSetting fallSpeed = add(new NumberSetting("Fall Speed", "How fast they fall", 1, 0.1, 4, 0.05));
	private final NumberSetting size = add(new NumberSetting("Size", "How big each one is", 1, 0.3, 3, 0.05));
	private final BoolSetting outdoorsOnly = add(new BoolSetting("Outdoors Only", "Don't fall under roofs", true));
	private final Palette palette = palette(0xFFFFC2DD, 0xFFFF77C8);

	private float[] seeds = new float[0];

	public PetalRainModule() {
		super("Petal Rain", "Petals, hearts, snow or sparkles drift down round you");
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	/** Seven random numbers per flake: x, y, z in the box, sway phase, spin axis twice, colour. */
	private void ensureSeeds(int n) {
		if (seeds.length == n * 7) return;
		Random random = new Random(99);
		seeds = new float[n * 7];
		for (int i = 0; i < seeds.length; i++) seeds[i] = random.nextFloat();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		int n = amount.getInt();
		ensureSeeds(n);
		Vec3d cam = event.camera().pos;
		float box = radius.getFloat();
		float side = box * 2;
		double time = time();
		float s = size.getFloat();
		Identifier texture = switch (mode.get()) {
			case SAKURA -> PETAL;
			case HEARTS -> HEART;
			case SNOW -> FxRender.GLOW;
			case SPARKLES -> SPARKLE;
		};
		boolean tumbling = mode.is(Mode.SAKURA) || mode.is(Mode.HEARTS);
		VertexConsumer consumer = FxRender.textured(event, texture, mode.is(Mode.SPARKLES), false);
		MatrixStack.Entry pose = event.matrices().peek();
		Quaternionf spin = new Quaternionf();
		Vector3f corner = new Vector3f();

		for (int i = 0; i < n; i++) {
			int o = i * 7;
			float phase = seeds[o + 3] * 100;
			float speed = fallSpeed.getFloat() * (0.7f + 0.6f * seeds[o + 6]) * (mode.is(Mode.SNOW) ? 0.8f : 1.4f);
			// Fixed in the world, drifting down and sideways on the breeze
			double wx = seeds[o] * side + time * 0.4 + Math.sin(time * 0.7 + phase) * 1.2;
			double wy = seeds[o + 1] * side - time * speed;
			double wz = seeds[o + 2] * side + Math.cos(time * 0.5 + phase) * 1.2;
			// Wrapped into the box round the camera
			float rx = (float) (MathHelper.floorMod(wx - cam.x, side) - box);
			float ry = (float) (MathHelper.floorMod(wy - cam.y, side) - box);
			float rz = (float) (MathHelper.floorMod(wz - cam.z, side) - box);
			// Fade out towards the edges of the box so the wrapping never shows
			float edge = Math.max(Math.abs(rx), Math.max(Math.abs(ry), Math.abs(rz)));
			float alpha = Math.min(1, (box - edge) / (box * 0.25f));
			if (alpha <= 0.01f) continue;
			if (outdoorsOnly.get()) {
				int x = MathHelper.floor(cam.x + rx), z = MathHelper.floor(cam.z + rz);
				if (mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) > cam.y + ry) continue;
			}
			int color = FxRender.fade(palette.at(seeds[o + 6]), alpha);
			float half = (mode.is(Mode.SNOW) ? 0.06f : 0.12f) * s;

			if (!tumbling) {
				FxRender.billboard(event, consumer, rx, ry, rz, half, 0, color);
				continue;
			}
			// Tumbling round its own axis as it falls
			Vector3f axis = new Vector3f(seeds[o + 4] - 0.5f, 0.5f, seeds[o + 5] - 0.5f).normalize();
			spin.identity().rotateAxis((float) (time * (1.5 + seeds[o + 6] * 2) + phase), axis);
			for (int k = 0; k < 4; k++) {
				float cx = k == 1 || k == 2 ? half : -half, cy = k >= 2 ? half : -half;
				corner.set(cx, cy, 0).rotate(spin);
				FxRender.textured(consumer, pose, rx + corner.x, ry + corner.y, rz + corner.z, k == 1 || k == 2 ? 1 : 0, k >= 2 ? 0 : 1, color);
			}
		}
	}
}
