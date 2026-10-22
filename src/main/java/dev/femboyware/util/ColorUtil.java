package dev.femboyware.util;

import java.awt.Color;

public final class ColorUtil {
	private ColorUtil() {
	}

	/** Soft pink-to-blue wave, offset by {@code index} so lists ripple. */
	public static int pastel(int index, float speed) {
		float hue = ((System.currentTimeMillis() * speed / 10000f) + index * 0.035f) % 1f;
		// Keep the hue between pink (0.83) and light blue (0.55) by mirroring the sweep
		float wave = Math.abs(hue * 2f - 1f);
		return 0xFF000000 | Color.HSBtoRGB(0.55f + wave * 0.38f, 0.45f, 1f);
	}

	public static int rainbow(int index, float speed) {
		float hue = ((System.currentTimeMillis() * speed / 10000f) + index * 0.035f) % 1f;
		return 0xFF000000 | Color.HSBtoRGB(hue, 0.6f, 1f);
	}

	public static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
	}
}
