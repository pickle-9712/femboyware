package dev.femboyware.module.hud;

import dev.femboyware.util.ColorUtil;

/** How HUD text is coloured: soft shifting pastels, a rainbow, a gradient between two colours, or one colour. */
public enum HudColors {
	PASTEL, RAINBOW, GRADIENT, STATIC;

	/** The colour of item {@code index} in a list, moving at {@code speed}, or {@code fixed} for Static. */
	public int at(int index, float speed, int fixed) {
		return switch (this) {
			case PASTEL -> ColorUtil.pastel(index, speed);
			case RAINBOW -> ColorUtil.rainbow(index, speed);
			case GRADIENT -> {
				// Back and forth between Colors' two gradient colours, moving down the list
				double t = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 1000.0 * speed * 0.8 - index * 0.35);
				yield 0xFF000000 | dev.femboyware.module.theme.WallpaperModule.mixColor(dev.femboyware.gui.Theme.gradientStart(), dev.femboyware.gui.Theme.gradientEnd(), (float) t);
			}
			case STATIC -> fixed | 0xFF000000;
		};
	}
}
