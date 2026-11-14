package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.larp.LarpLight;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;

/**
 * Coloured fog: nested shells of thin mist round the camera, so the further something is the more
 * shells it's behind and the more it fades into the colour, like real fog; thickest low down, with
 * a band of mist lying on the ground if you like.
 */
public class FogModule extends FxModule {
	private static final int SHELLS = 10, SIDES = 48;

	private final ColorSetting color = add(new ColorSetting("Color", "The fog's colour", 0xFFF2B8DA));
	private final NumberSetting start = add(new NumberSetting("Start", "Where the fog begins, in blocks", 8, 0, 64, 1));
	private final NumberSetting end = add(new NumberSetting("End", "Where it's at its thickest", 72, 8, 256, 1));
	private final NumberSetting density = add(new NumberSetting("Density", "How thick it gets", 0.75, 0.05, 1, 0.05));
	private final NumberSetting height = add(new NumberSetting("Height", "How far up from you it reaches before thinning out", 40, 4, 160, 1));
	private final BoolSetting night = add(new BoolSetting("Darker At Night", "Dim the fog at night", true));

	public FogModule() {
		super("Fog", "Coloured fog that thickens with distance", Category.SKY);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		VertexConsumer quads = FxRender.quads(event, false, false);
		PoseStack.Pose pose = event.matrices().last();
		int base = color.get();
		if (night.get()) base = LarpLight.shade(base, 1 - 0.75f * LarpLight.night());
		float near = start.getFloat(), far = Math.max(near + 1, end.getFloat());
		// Each shell's share so that all of them together reach the density at the far end
		float each = 1 - (float) Math.pow(1 - Math.min(0.99, density.get()), 1.0 / SHELLS);
		float top = height.getFloat(), bottom = -top * 0.5f;
		// Farthest first, so nearer shells blend over them
		for (int s = SHELLS - 1; s >= 0; s--) {
			float r = near + (far - near) * (s + 1) / SHELLS;
			for (int i = 0; i < SIDES; i++) {
				double a1 = Math.PI * 2 * i / SIDES, a2 = Math.PI * 2 * (i + 1) / SIDES;
				float x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r, x2 = (float) Math.cos(a2) * r, z2 = (float) Math.sin(a2) * r;
				int solid = FxRender.fade(base, each), clear = FxRender.fade(base, 0);
				// Thick from below up to your level, thinning to nothing at the top
				FxRender.quad(quads, pose, x1, bottom, z1, solid, x2, bottom, z2, solid, x2, 0, z2, solid, x1, 0, z1, solid);
				FxRender.quad(quads, pose, x1, 0, z1, solid, x2, 0, z2, solid, x2, top, z2, clear, x1, top, z1, clear);
			}
			// A lid and a floor, so looking down or up still goes through the fog
			int lid = FxRender.fade(base, each * 0.5f);
			for (int i = 0; i < SIDES; i++) {
				double a1 = Math.PI * 2 * i / SIDES, a2 = Math.PI * 2 * (i + 1) / SIDES;
				float x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r, x2 = (float) Math.cos(a2) * r, z2 = (float) Math.sin(a2) * r;
				FxRender.quad(quads, pose, 0, bottom, 0, FxRender.fade(base, each), x1, bottom, z1, FxRender.fade(base, each), x2, bottom, z2, FxRender.fade(base, each), 0, bottom, 0, FxRender.fade(base, each));
				FxRender.quad(quads, pose, 0, top, 0, lid, x2, top, z2, FxRender.fade(base, 0), x1, top, z1, FxRender.fade(base, 0), 0, top, 0, lid);
			}
		}
	}
}
