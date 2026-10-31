package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector3f;

import java.awt.Color;

/** A rainbow arching across the sky, with a fainter second bow outside it in reversed colours. */
public class RainbowModule extends SkyFxModule {
	public enum Colors { CLASSIC, PALETTE }

	private static final int ARC_SEGMENTS = 80;
	private static final int BANDS = 20;

	private final EnumSetting<Colors> colors = add(new EnumSetting<>("Colors", "Classic rainbow colours, or the palette below", Colors.CLASSIC));
	private final NumberSetting azimuth = add(new NumberSetting("Direction", "Compass bearing of its middle: 0 north, 90 east", 90, 0, 360, 5));
	private final NumberSetting size = add(new NumberSetting("Size", "Angle from its centre to the bow, in degrees", 42, 15, 70, 1));
	private final NumberSetting width = add(new NumberSetting("Width", "How wide the bow is, in degrees", 5, 1, 15, 0.5));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong it is", 0.35, 0.05, 1, 0.05));
	private final BoolSetting second = add(new BoolSetting("Double", "A fainter second bow outside the first", true));
	private final BoolSetting rainOnly = add(new BoolSetting("After Rain", "Only show while it's raining or just stopped", false));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);

	public RainbowModule() {
		super("Rainbow", "A rainbow across the sky");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!hasSky()) return;
		float visible = opacity.getFloat() * (rainOnly.get() ? mc.world.getRainGradient(tickDelta()) : 1) * (1 - night() * 0.7f);
		if (visible <= 0.01f) return;
		// The bow is a circle round a point a little below the horizon
		Vector3f c = direction(azimuth.get(), -6);
		Vector3f u = new Vector3f(c).cross(0, 1, 0).normalize();
		Vector3f w = new Vector3f(u).cross(c).normalize();
		if (w.y < 0) w.negate();
		VertexConsumer consumer = FxRender.quads(event, false, false);
		bow(event, consumer, c, u, w, size.get(), width.get(), visible, false);
		if (second.get()) bow(event, consumer, c, u, w, size.get() * 1.24, width.get() * 1.6, visible * 0.45f, true);
	}

	private void bow(Render3DEvent event, VertexConsumer consumer, Vector3f c, Vector3f u, Vector3f w, double radius, double bowWidth, float alpha, boolean reversed) {
		MatrixStack.Entry pose = event.matrices().peek();
		float distance = skyDistance();
		for (int band = 0; band < BANDS; band++) {
			float t1 = (float) band / BANDS, t2 = (float) (band + 1) / BANDS;
			double r1 = Math.toRadians(radius + bowWidth * (t1 - 0.5)), r2 = Math.toRadians(radius + bowWidth * (t2 - 0.5));
			// Soft at both edges of the bow
			float a1 = alpha * (float) Math.sin(Math.PI * t1), a2 = alpha * (float) Math.sin(Math.PI * t2);
			int c1 = color(reversed ? t1 : 1 - t1), c2 = color(reversed ? t2 : 1 - t2);
			for (int i = 0; i < ARC_SEGMENTS; i++) {
				// A little past half a circle, so its feet sink below the horizon
				double s1 = -0.12 + (Math.PI + 0.24) * i / ARC_SEGMENTS, s2 = -0.12 + (Math.PI + 0.24) * (i + 1) / ARC_SEGMENTS;
				Vector3f p1 = point(c, u, w, s1, r1, distance), p2 = point(c, u, w, s2, r1, distance);
				Vector3f p3 = point(c, u, w, s2, r2, distance), p4 = point(c, u, w, s1, r2, distance);
				FxRender.quad(consumer, pose, p1.x, p1.y, p1.z, FxRender.fade(c1, a1), p2.x, p2.y, p2.z, FxRender.fade(c1, a1),
					p3.x, p3.y, p3.z, FxRender.fade(c2, a2), p4.x, p4.y, p4.z, FxRender.fade(c2, a2));
			}
		}
	}

	/** Colour {@code t} across the bow: 0 is the inside (violet), 1 the outside (red). */
	private int color(float t) {
		if (colors.is(Colors.PALETTE)) return palette.at(t);
		return 0xFF000000 | Color.HSBtoRGB((1 - t) * 0.78f, 0.75f, 1f);
	}

	private static Vector3f point(Vector3f c, Vector3f u, Vector3f w, double around, double radius, float distance) {
		float sin = (float) Math.sin(radius);
		return new Vector3f(u).mul((float) Math.cos(around) * sin).add(new Vector3f(w).mul((float) Math.sin(around) * sin))
			.add(new Vector3f(c).mul((float) Math.cos(radius))).mul(distance);
	}
}
