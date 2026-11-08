package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** A crackling bolt of lightning from you to whoever you're fighting, re-forking many times a second. */
public class LightningLinkModule extends TargetFxModule {
	private static final int SEGMENTS = 14;

	private final NumberSetting jaggedness = add(new NumberSetting("Jaggedness", "How far the bolt zigzags", 0.35, 0, 1.5, 0.05));
	private final NumberSetting flicker = add(new NumberSetting("Flicker", "How many times a second it re-forks", 14, 2, 40, 1));
	private final NumberSetting thickness = add(new NumberSetting("Thickness", "How thick the bolt is", 0.06, 0.01, 0.25, 0.005));
	private final NumberSetting branches = add(new NumberSetting("Branches", "Little forks splitting off the bolt", 2, 0, 6, 1));
	private final BoolSetting flare = add(new BoolSetting("Flare", "A flash of light where it strikes", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFFB98AFF);
	private final BoolSetting throughWalls = targetSettings();

	private record Bolt(List<Vector3f> points, float width, float alpha) {}

	public LightningLinkModule() {
		super("Lightning Link", "A crackling bolt from you to whoever you're fighting");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Map<LivingEntity, Float> targets = fadedTargets();
		if (targets.isEmpty()) return;
		float tickDelta = tickDelta();
		Vec3 cam = event.camera().pos;
		Vector3f from = origin(event, tickDelta);
		long bucket = (long) (time() * flicker.get());

		List<Bolt> bolts = new ArrayList<>();
		List<Vector3f> strikes = new ArrayList<>();
		List<Float> strikeAlphas = new ArrayList<>();
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3 aim = target.getPosition(tickDelta).add(0, target.getBbHeight() * 0.55, 0);
			Vector3f to = new Vector3f((float) (aim.x - cam.x), (float) (aim.y - cam.y), (float) (aim.z - cam.z));
			Random random = new Random(bucket * 31 + target.getId());
			List<Vector3f> main = jag(from, to, SEGMENTS, jaggedness.getFloat(), random);
			bolts.add(new Bolt(main, thickness.getFloat(), fade));
			for (int b = 0; b < branches.getInt(); b++) {
				// A short fork off a random point, heading roughly onwards
				Vector3f root = main.get(2 + random.nextInt(SEGMENTS - 4));
				Vector3f onward = new Vector3f(to).sub(from).normalize().mul(0.8f + random.nextFloat())
					.add((float) random.nextGaussian() * 0.8f, (float) random.nextGaussian() * 0.8f, (float) random.nextGaussian() * 0.8f);
				bolts.add(new Bolt(jag(root, new Vector3f(root).add(onward), 5, jaggedness.getFloat() * 0.5f, random), thickness.getFloat() * 0.55f, fade * 0.7f));
			}
			strikes.add(to);
			strikeAlphas.add(fade);
		}

		PoseStack.Pose pose = event.matrices().last();
		VertexConsumer light = FxRender.quads(event, true, throughWalls.get());
		for (Bolt bolt : bolts) {
			// A wide coloured glow, then a thin white-hot core along the same path
			strip(light, pose, bolt.points, bolt.width * 3, FxRender.fade(palette.at(0), bolt.alpha * 0.45f), FxRender.fade(palette.at(0.5f), bolt.alpha * 0.45f));
			strip(light, pose, bolt.points, bolt.width, FxRender.fade(0xFFFFFFFF, bolt.alpha), FxRender.fade(FxRender.lighten(palette.at(0.5f), 0.6f), bolt.alpha));
		}
		if (!flare.get()) return;
		VertexConsumer glows = FxRender.textured(event, FxRender.GLOW, true, throughWalls.get());
		float pulse = 0.7f + 0.3f * (float) Math.sin(time() * 30);
		for (int i = 0; i < strikes.size(); i++) {
			Vector3f strike = strikes.get(i);
			FxRender.billboard(event, glows, strike.x, strike.y, strike.z, 0.6f * pulse, 0, FxRender.fade(palette.at(0.5f), strikeAlphas.get(i) * 0.7f));
		}
	}

	/** Where the bolt starts, relative to the camera: from your hand in first person, else your chest. */
	private static Vector3f origin(Render3DEvent event, float tickDelta) {
		if (mc.options.getCameraType().isFirstPerson()) return new Vector3f(0.35f, -0.4f, -1.1f).rotate(event.camera().orientation);
		Vec3 cam = event.camera().pos;
		Vec3 chest = mc.player.getPosition(tickDelta).add(0, mc.player.getBbHeight() * 0.6, 0);
		return new Vector3f((float) (chest.x - cam.x), (float) (chest.y - cam.y), (float) (chest.z - cam.z));
	}

	/** A zigzag from {@code a} to {@code b}, pushed sideways most in the middle. */
	private static List<Vector3f> jag(Vector3f a, Vector3f b, int segments, float amount, Random random) {
		Vector3f direction = new Vector3f(b).sub(a);
		float length = direction.length();
		direction.normalize();
		Vector3f u = new Vector3f(direction).cross(0, 1, 0);
		if (u.lengthSquared() < 1e-4f) u.set(1, 0, 0);
		u.normalize();
		Vector3f v = new Vector3f(direction).cross(u).normalize();
		List<Vector3f> points = new ArrayList<>();
		for (int i = 0; i <= segments; i++) {
			float s = (float) i / segments;
			Vector3f point = new Vector3f(direction).mul(length * s).add(a);
			if (i > 0 && i < segments) {
				float push = amount * (float) Math.sin(Math.PI * s) * Math.min(1, length / 4);
				point.add(new Vector3f(u).mul((float) random.nextGaussian() * push)).add(new Vector3f(v).mul((float) random.nextGaussian() * push));
			}
			points.add(point);
		}
		return points;
	}

	/** A band along the points that always faces the camera, which sits at the origin. */
	private static void strip(VertexConsumer consumer, PoseStack.Pose pose, List<Vector3f> points, float width, int startColor, int endColor) {
		for (int i = 0; i < points.size() - 1; i++) {
			Vector3f a = points.get(i), b = points.get(i + 1);
			Vector3f side = new Vector3f(b).sub(a).cross(a);
			if (side.lengthSquared() < 1e-8f) continue;
			// Thinner close to the camera, so it doesn't fill the screen in first person
			side.normalize().mul(width / 2 * Math.max(0.2f, Math.min(1, a.length() / 3)));
			int c1 = FxRender.mix(startColor, endColor, (float) i / (points.size() - 1));
			int c2 = FxRender.mix(startColor, endColor, (float) (i + 1) / (points.size() - 1));
			FxRender.quad(consumer, pose, a.x - side.x, a.y - side.y, a.z - side.z, c1, b.x - side.x, b.y - side.y, b.z - side.z, c2,
				b.x + side.x, b.y + side.y, b.z + side.z, c2, a.x + side.x, a.y + side.y, a.z + side.z, c1);
		}
	}
}
