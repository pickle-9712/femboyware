package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Glowing paper lanterns drifting up into the sky all round you, swaying and flickering as they rise. */
public class SkyLanternsModule extends SkyFxModule {
	private static final Identifier LANTERN = texture("lantern");

	private final NumberSetting count = add(new NumberSetting("Count", "How many lanterns are in the air at once", 40, 5, 300, 5));
	private final NumberSetting spread = add(new NumberSetting("Spread", "How far round you they rise, in blocks", 40, 10, 150, 5));
	private final NumberSetting riseSpeed = add(new NumberSetting("Rise Speed", "How fast they float up, in blocks a second", 1.2, 0.2, 5, 0.1));
	private final NumberSetting size = add(new NumberSetting("Size", "How big each lantern is", 1.5, 0.3, 4, 0.05));
	private final BoolSetting glow = add(new BoolSetting("Glow", "A warm glow round each lantern", true));
	private final BoolSetting nightOnly = add(new BoolSetting("Night Only", "Only release lanterns at night", false));
	private final Palette palette = palette(0xFFFFB060, 0xFFFF77C8);

	private static final class Lantern {
		double x, y, z;
		final float speed, phase, tint;

		Lantern(double x, double y, double z, float speed, float phase, float tint) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.speed = speed;
			this.phase = phase;
			this.tint = tint;
		}
	}

	private final List<Lantern> lanterns = new ArrayList<>();
	private final Random random = new Random();
	private long lastFrame;

	public SkyLanternsModule() {
		super("Sky Lanterns", "Glowing lanterns float up into the sky round you");
	}

	@Override
	protected void onDisable() {
		lanterns.clear();
		lastFrame = 0;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		Vec3d cam = event.camera().pos;
		double range = spread.get();

		lanterns.removeIf(l -> l.y > cam.y + 120 || Math.abs(l.x - cam.x) > range * 1.5 || Math.abs(l.z - cam.z) > range * 1.5);
		boolean releasing = !nightOnly.get() || night() > 0.3f;
		// The first batch fills the whole sky; after that new ones start from below
		boolean filling = lanterns.isEmpty();
		while (releasing && lanterns.size() < count.getInt()) {
			double angle = random.nextDouble() * Math.PI * 2, distance = 8 + Math.sqrt(random.nextDouble()) * (range - 8);
			double y = cam.y + (filling ? -15 + random.nextDouble() * 110 : -20 - random.nextDouble() * 10);
			lanterns.add(new Lantern(cam.x + Math.cos(angle) * distance, y, cam.z + Math.sin(angle) * distance,
				0.7f + random.nextFloat() * 0.6f, random.nextFloat() * 100, random.nextFloat()));
		}
		if (lanterns.isEmpty()) return;

		double time = time();
		for (Lantern lantern : lanterns) {
			lantern.y += delta * riseSpeed.get() * lantern.speed;
			// A slow wander on the breeze
			lantern.x += Math.sin(time * 0.3 + lantern.phase) * delta * 0.4;
			lantern.z += Math.cos(time * 0.25 + lantern.phase) * delta * 0.4;
		}

		float s = size.getFloat();
		if (glow.get()) {
			VertexConsumer light = FxRender.textured(event, FxRender.GLOW, true, false);
			for (Lantern lantern : lanterns) {
				float flicker = 0.8f + 0.2f * (float) Math.sin(time * 9 + lantern.phase) * (float) Math.sin(time * 5.3 + lantern.phase * 2);
				FxRender.glow(event, light, new Vec3d(lantern.x, lantern.y, lantern.z), 1.8f * s, FxRender.fade(palette.at(lantern.tint), 0.6f * flicker * fadeOut(lantern, cam)));
			}
		}
		VertexConsumer bodies = FxRender.textured(event, LANTERN, false, false);
		for (Lantern lantern : lanterns) {
			Vec3d pos = new Vec3d(lantern.x, lantern.y, lantern.z);
			float sway = (float) Math.sin(time * 1.3 + lantern.phase) * 0.12f;
			FxRender.billboard(event, bodies, (float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z), 0.6f * s, sway,
				FxRender.fade(FxRender.lighten(palette.at(lantern.tint), 0.45f), fadeOut(lantern, cam)));
		}
	}

	/** Lanterns fade as they climb out of sight. */
	private static float fadeOut(Lantern lantern, Vec3d cam) {
		return (float) Math.max(0, Math.min(1, (cam.y + 120 - lantern.y) / 40));
	}
}
