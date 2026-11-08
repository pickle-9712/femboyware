package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import org.joml.Vector3f;

/** Curtains of light rippling across the sky, brightest along their lower edge, shimmering as they fold. */
public class AuroraModule extends SkyFxModule {
	private static final int SEGMENTS = 120;

	private final NumberSetting bands = add(new NumberSetting("Bands", "How many curtains", 3, 1, 6, 1));
	private final NumberSetting azimuth = add(new NumberSetting("Direction", "Compass bearing the aurora is centred on: 0 north, 90 east", 0, 0, 360, 5));
	private final NumberSetting spread = add(new NumberSetting("Spread", "How far round the sky it reaches, in degrees", 220, 40, 360, 5));
	private final NumberSetting elevation = add(new NumberSetting("Elevation", "How high its lower edge sits, in degrees", 16, 2, 60, 1));
	private final NumberSetting height = add(new NumberSetting("Height", "How tall the curtains are, in degrees", 26, 5, 70, 1));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast it ripples", 1, 0, 5, 0.05));
	private final NumberSetting brightness = add(new NumberSetting("Brightness", "How bright it shines", 0.7, 0.05, 1.5, 0.05));
	private final BoolSetting nightOnly = add(new BoolSetting("Night Only", "Fade out during the day", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFF5CFFD8);

	public AuroraModule() {
		super("Aurora", "Rippling curtains of light across the sky");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!hasSky()) return;
		float visible = clearWeather() * (nightOnly.get() ? night() : 1) * brightness.getFloat();
		if (visible <= 0.01f) return;
		float distance = skyDistance();
		double time = time() * speed.get();
		PoseStack.Pose pose = event.matrices().last();
		VertexConsumer light = FxRender.quads(event, true, false);

		int count = bands.getInt();
		for (int band = 0; band < count; band++) {
			// Each curtain sits a little higher and further round than the last
			double bandAzimuth = azimuth.get() + (band - (count - 1) / 2.0) * 18;
			double bandElevation = elevation.get() + band * 4;
			Vector3f lastBottom = null, lastMid = null, lastTop = null, lastSkirt = null;
			int lastColor = 0;
			float lastAlpha = 0;
			for (int i = 0; i <= SEGMENTS; i++) {
				float along = (float) i / SEGMENTS;
				// Folds: the curtain's line wanders back and forth as it runs across the sky
				double fold = Math.sin(along * 9 + time * 0.35 + band * 1.7) * 7 + Math.sin(along * 23 - time * 0.6 + band) * 2.5;
				double az = bandAzimuth + (along - 0.5) * spread.get() + fold;
				double el = bandElevation + Math.sin(along * 5 + time * 0.2 + band * 2.3) * 3;
				double tall = height.get() * (0.65 + 0.35 * Math.sin(along * 7 - time * 0.3 + band * 0.9));
				float shimmer = (float) (0.55 + 0.45 * Math.sin(along * 37 + time * 2.1 + band * 3) * Math.sin(along * 13 - time * 1.1));
				float alpha = visible * shimmer * (float) Math.sin(Math.PI * along) * (1 - band * 0.12f);
				int color = palette.at(along * 0.6f + band * 0.25f);

				Vector3f skirt = direction(az, el - 1.5).mul(distance);
				Vector3f bottom = direction(az, el).mul(distance);
				Vector3f mid = direction(az + 1.5, el + tall * 0.3).mul(distance);
				Vector3f top = direction(az + 3, el + tall).mul(distance);
				if (lastBottom != null) {
					int c1 = lastColor, c2 = color;
					// A quick fade in under the lower edge, the bright base, then a long fade upwards
					quad(light, pose, lastSkirt, skirt, bottom, lastBottom, FxRender.fade(c1, 0), FxRender.fade(c2, 0), FxRender.fade(FxRender.lighten(c2, 0.4f), alpha), FxRender.fade(FxRender.lighten(c1, 0.4f), lastAlpha));
					quad(light, pose, lastBottom, bottom, mid, lastMid, FxRender.fade(FxRender.lighten(c1, 0.4f), lastAlpha), FxRender.fade(FxRender.lighten(c2, 0.4f), alpha), FxRender.fade(c2, alpha * 0.6f), FxRender.fade(c1, lastAlpha * 0.6f));
					quad(light, pose, lastMid, mid, top, lastTop, FxRender.fade(c1, lastAlpha * 0.6f), FxRender.fade(c2, alpha * 0.6f), FxRender.fade(palette.at(along + 0.5f), 0), FxRender.fade(palette.at(along + 0.5f), 0));
				}
				lastSkirt = skirt;
				lastBottom = bottom;
				lastMid = mid;
				lastTop = top;
				lastColor = color;
				lastAlpha = alpha;
			}
		}
	}

	private static void quad(VertexConsumer consumer, PoseStack.Pose pose, Vector3f a, Vector3f b, Vector3f c, Vector3f d, int ca, int cb, int cc, int cd) {
		FxRender.quad(consumer, pose, a.x, a.y, a.z, ca, b.x, b.y, b.z, cb, c.x, c.y, c.z, cc, d.x, d.y, d.z, cd);
	}
}
