package dev.femboyware.module.hud;

import dev.femboyware.util.ColorUtil;

/** How HUD text is coloured: soft shifting pastels, a rainbow, or one colour. */
public enum HudColors {
	PASTEL, RAINBOW, STATIC;

	/** The colour of item {@code index} in a list, moving at {@code speed}, or {@code fixed} for Static. */
	public int at(int index, float speed, int fixed) {
		return switch (this) {
			case PASTEL -> ColorUtil.pastel(index, speed);
			case RAINBOW -> ColorUtil.rainbow(index, speed);
			case STATIC -> fixed | 0xFF000000;
		};
	}
}
