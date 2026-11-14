package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import org.joml.Vector3f;

/**
 * Repaints the sky: a dome of colour over the real sky, from a glowing horizon up to the zenith,
 * with soft bands of light drifting slowly across it. Hills and trees still stand in front of it.
 */
public class SkyShaderModule extends SkyFxModule {
	public enum Look { SUNSET, NEBULA, DREAMY, OCEAN, ROYAL, TOXIC, CUSTOM }

	private static final int RINGS = 12, SEGMENTS = 48;

	private final EnumSetting<Look> look = add(new EnumSetting<>("Look", "The colours of the sky", Look.DREAMY));
	private final ColorSetting horizon = add(new ColorSetting("Horizon", "The colour at the horizon", 0xFFFF9AC8)).visibleWhen(() -> look.is(Look.CUSTOM));
	private final ColorSetting middle = add(new ColorSetting("Middle", "The colour half way up", 0xFFB27CFF)).visibleWhen(() -> look.is(Look.CUSTOM));
	private final ColorSetting zenith = add(new ColorSetting("Zenith", "The colour straight up", 0xFF2A3A8C)).visibleWhen(() -> look.is(Look.CUSTOM));
	private final NumberSetting strength = add(new NumberSetting("Strength", "How strongly it covers the real sky", 0.65, 0.05, 1, 0.05));
	private final BoolSetting bands = add(new BoolSetting("Light Bands", "Soft bands of light drifting across", true));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast the bands drift", 1, 0, 5, 0.1)).visibleWhen(bands::get);
	private final BoolSetting nightOnly = add(new BoolSetting("Night Only", "Only after sunset", false));

	public SkyShaderModule() {
		super("Sky Shader", "Repaint the sky with glowing gradients");
	}

	@Override
	public String getInfo() {
		return look.getDisplayName();
	}

	private int[] colors() {
		return switch (look.get()) {
			case SUNSET -> new int[]{0xFFFFB25A, 0xFFFF5E7A, 0xFF3A2A7A};
			case NEBULA -> new int[]{0xFFFF6FD8, 0xFF7A3DFF, 0xFF0E0A2E};
			case DREAMY -> new int[]{0xFFFFC4E6, 0xFFC8A8FF, 0xFF7FB8FF};
			case OCEAN -> new int[]{0xFF8FFFE8, 0xFF3DA8FF, 0xFF0A2A6A};
			case ROYAL -> new int[]{0xFFFFD27A, 0xFF8A3DCC, 0xFF1A0A3A};
			case TOXIC -> new int[]{0xFFD8FF5A, 0xFF3DCC7A, 0xFF0A2A1A};
			case CUSTOM -> new int[]{horizon.get(), middle.get(), zenith.get()};
		};
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.level == null || !hasSky()) return;
		float fade = strength.getFloat() * (nightOnly.get() ? night() : 1) * (0.4f + 0.6f * clearWeather());
		if (fade <= 0.01f) return;
		int[] c = colors();
		float distance = skyDistance();
		double t = time() * speed.get();
		VertexConsumer quads = FxRender.quads(event, false, false);
		PoseStack.Pose pose = event.matrices().last();
		// Rings from a little under the horizon (hidden by the ground) up to the top
		float[] elev = new float[RINGS + 1];
		for (int i = 0; i <= RINGS; i++) elev[i] = -8 + (98f * i / RINGS);
		Vector3f[][] p = new Vector3f[RINGS + 1][SEGMENTS + 1];
		int[][] col = new int[RINGS + 1][SEGMENTS + 1];
		for (int i = 0; i <= RINGS; i++) {
			for (int j = 0; j <= SEGMENTS; j++) {
				double az = 360.0 * j / SEGMENTS;
				p[i][j] = direction(az, Math.min(89.9, elev[i])).mul(distance);
				col[i][j] = color(c, elev[i], az, t, fade);
			}
		}
		for (int i = 0; i < RINGS; i++) {
			for (int j = 0; j < SEGMENTS; j++) {
				Vector3f a = p[i][j], b = p[i][j + 1], d = p[i + 1][j + 1], e = p[i + 1][j];
				// Both windings, so it shows from inside whichever way the culling goes
				FxRender.quad(quads, pose, a.x, a.y, a.z, col[i][j], b.x, b.y, b.z, col[i][j + 1], d.x, d.y, d.z, col[i + 1][j + 1], e.x, e.y, e.z, col[i + 1][j]);
				FxRender.quad(quads, pose, e.x, e.y, e.z, col[i + 1][j], d.x, d.y, d.z, col[i + 1][j + 1], b.x, b.y, b.z, col[i][j + 1], a.x, a.y, a.z, col[i][j]);
			}
		}
	}

	/** The sky's colour at an elevation and bearing: horizon to middle to zenith, the bands brightening it. */
	private int color(int[] c, float elevation, double azimuth, double t, float fade) {
		float h = Math.max(0, Math.min(1, elevation / 90f));
		int base = h < 0.35f ? FxRender.mix(c[0], c[1], h / 0.35f) : FxRender.mix(c[1], c[2], (h - 0.35f) / 0.65f);
		if (bands.get()) {
			double az = Math.toRadians(azimuth);
			float band = (float) (0.5 + 0.5 * Math.sin(az * 3 + h * 7 + t * 0.15) * Math.sin(az * 2 - h * 4 - t * 0.11));
			base = FxRender.lighten(base, band * 0.25f);
		}
		// Thicker toward the horizon, thinner overhead, like real haze
		float alpha = fade * (0.75f + 0.25f * (1 - h));
		return FxRender.fade(base, alpha);
	}
}
