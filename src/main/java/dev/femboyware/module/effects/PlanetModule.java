package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

/**
 * A huge ringed planet hanging in the sky, with moons circling it. The planet and moons write depth
 * and the rings are real 3D bands round it, so the far half of the rings and moons hide behind the
 * planet and the near half pass in front, whatever order the batches end up drawn in (ImmediatelyFast
 * and 26.2's render queue both regroup draws by layer).
 */
public class PlanetModule extends SkyFxModule {
	public enum Style {
		CANDY("planet_candy", 0xFFFF9AD5),
		ICE("planet_ice", 0xFF9AD0FF),
		SUNSET("planet_sunset", 0xFFFFA070);

		private final Identifier texture;
		private final int atmosphere;

		Style(String texture, int atmosphere) {
			this.texture = texture(texture);
			this.atmosphere = atmosphere;
		}
	}

	private static final int RING_SEGMENTS = 96;

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Candy pink, icy blue or sunset orange", Style.CANDY));
	private final NumberSetting size = add(new NumberSetting("Size", "How big it looks, in degrees across", 14, 2, 50, 0.5));
	private final NumberSetting azimuth = add(new NumberSetting("Direction", "Compass bearing: 0 north, 90 east", 135, 0, 360, 5));
	private final NumberSetting elevation = add(new NumberSetting("Elevation", "How high in the sky, in degrees", 32, 0, 90, 1));
	private final BoolSetting rings = add(new BoolSetting("Rings", "Rings round the planet", true));
	private final NumberSetting ringOpen = add(new NumberSetting("Ring Tilt", "How open the rings look: 0 edge-on, 90 face-on", 22, 2, 90, 1)).visibleWhen(rings::get);
	private final ColorSetting ringColor = add(new ColorSetting("Ring Color", "Colour of the rings", 0xD0FFD8F0)).visibleWhen(rings::get);
	private final NumberSetting moons = add(new NumberSetting("Moons", "Small moons circling it", 2, 0, 6, 1));
	private final NumberSetting moonSpeed = add(new NumberSetting("Moon Speed", "How fast the moons go round", 1, 0, 5, 0.05)).visibleWhen(() -> moons.getInt() > 0);
	private final BoolSetting glow = add(new BoolSetting("Atmosphere", "A soft glow round the planet", true));

	public PlanetModule() {
		super("Planet", "A huge ringed planet in the sky");
	}

	@Override
	public String getInfo() {
		return style.getDisplayName();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!hasSky()) return;
		float visible = clearWeather();
		float distance = skyDistance();
		Vector3f c = direction(azimuth.get(), elevation.get());
		float radius = (float) Math.tan(Math.toRadians(size.get() / 2));
		// Ring plane: across the sky one way, and between the sky and the line of sight the other
		Vector3f across = new Vector3f(c).cross(0, 1, 0);
		if (across.lengthSquared() < 1e-4f) across.set(1, 0, 0);
		across.normalize();
		Vector3f up = new Vector3f(across).cross(c).normalize();
		// A little roll so the rings aren't level with the horizon
		float roll = (float) Math.toRadians(-18);
		Vector3f u = new Vector3f(across).mul((float) Math.cos(roll)).add(new Vector3f(up).mul((float) Math.sin(roll)));
		Vector3f screenUp = new Vector3f(up).mul((float) Math.cos(roll)).sub(new Vector3f(across).mul((float) Math.sin(roll)));
		double open = Math.toRadians(ringOpen.get());
		Vector3f v = new Vector3f(c).mul((float) Math.cos(open)).add(new Vector3f(screenUp).mul((float) Math.sin(open)));

		Style planet = style.get();
		if (glow.get()) {
			sprite(event, FxRender.textured(event, FxRender.GLOW, true, false), c, distance, radius * 2.4f, 0, FxRender.fade(planet.atmosphere, 0.55f * visible));
		}
		if (rings.get()) ring(event, c, u, v, radius, distance, true, visible);
		moons(event, c, u, v, radius, distance, true, visible);
		sprite(event, FxRender.solid(event, planet.texture), c, distance, radius, roll, FxRender.fade(0xFFFFFFFF, visible));
		if (rings.get()) ring(event, c, u, v, radius, distance, false, visible);
		moons(event, c, u, v, radius, distance, false, visible);
	}

	/** One half of the rings: the far half ({@code back}) or the near half. */
	private void ring(Render3DEvent event, Vector3f c, Vector3f u, Vector3f v, float radius, float distance, boolean back, float visible) {
		PoseStack.Pose pose = event.matrices().last();
		VertexConsumer consumer = FxRender.quads(event, false, false);
		// Three bands with gaps between, like real planetary rings
		float[][] bands = {{1.35f, 1.7f, 0.75f}, {1.76f, 2.15f, 1f}, {2.22f, 2.4f, 0.5f}};
		int base = ringColor.get();
		for (int i = 0; i < RING_SEGMENTS; i++) {
			double a1 = Math.PI * 2 * i / RING_SEGMENTS, a2 = Math.PI * 2 * (i + 1) / RING_SEGMENTS;
			// Sine positive means the line-of-sight side, away from the camera
			if ((Math.sin((a1 + a2) / 2) > 0) != back) continue;
			for (float[] band : bands) {
				int color = FxRender.fade(i % 6 < 3 ? base : FxRender.lighten(base, 0.15f), band[2] * visible);
				Vector3f p1 = ringPoint(c, u, v, a1, radius * band[0], distance), p2 = ringPoint(c, u, v, a2, radius * band[0], distance);
				Vector3f p3 = ringPoint(c, u, v, a2, radius * band[1], distance), p4 = ringPoint(c, u, v, a1, radius * band[1], distance);
				FxRender.quad(consumer, pose, p1.x, p1.y, p1.z, color, p2.x, p2.y, p2.z, color, p3.x, p3.y, p3.z, color, p4.x, p4.y, p4.z, color);
			}
		}
	}

	private static Vector3f ringPoint(Vector3f c, Vector3f u, Vector3f v, double angle, float r, float distance) {
		return new Vector3f(u).mul((float) Math.cos(angle) * r).add(new Vector3f(v).mul((float) Math.sin(angle) * r)).add(c).mul(distance);
	}

	private void moons(Render3DEvent event, Vector3f c, Vector3f u, Vector3f v, float radius, float distance, boolean back, float visible) {
		int count = moons.getInt();
		if (count == 0) return;
		VertexConsumer consumer = FxRender.solid(event, texture("planet_ice"));
		for (int i = 0; i < count; i++) {
			double angle = time() * moonSpeed.get() * (0.25 - i * 0.03) + i * 2.4;
			if ((Math.sin(angle) > 0) != back) continue;
			// Each moon a little further out, on a slightly different tilt
			float orbit = radius * (2.9f + i * 0.55f);
			Vector3f tilted = new Vector3f(v).add(new Vector3f(u).cross(c).mul(0.15f * (i - 1)));
			Vector3f pos = new Vector3f(u).mul((float) Math.cos(angle) * orbit).add(tilted.mul((float) Math.sin(angle) * orbit)).add(c).normalize();
			sprite(event, consumer, pos, distance, radius * (0.16f + 0.05f * (i % 3)), 0, FxRender.fade(0xFFD8D0E0, visible));
		}
	}
}
