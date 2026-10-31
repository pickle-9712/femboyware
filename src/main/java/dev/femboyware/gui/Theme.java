package dev.femboyware.gui;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Module;
import dev.femboyware.module.theme.BackgroundModule;
import dev.femboyware.module.theme.ColorsModule;
import dev.femboyware.module.theme.GlassModule;
import dev.femboyware.module.theme.ShineModule;

/** The menu's look, from the Customize panel's modules; each part falls back to its default while its module is off. */
public final class Theme {
	private Theme() {
	}

	public static int accent() {
		ColorsModule colors = active(ColorsModule.class);
		return colors != null ? colors.accent() : ColorsModule.DEFAULT_ACCENT;
	}

	public static int text() {
		ColorsModule colors = active(ColorsModule.class);
		return colors != null ? colors.text() : ColorsModule.DEFAULT_TEXT;
	}

	public static int muted() {
		ColorsModule colors = active(ColorsModule.class);
		return colors != null ? colors.muted() : ColorsModule.DEFAULT_MUTED;
	}

	public static int panel() {
		GlassModule glass = active(GlassModule.class);
		return glass != null ? glass.panel() : GlassModule.DEFAULT_PANEL;
	}

	public static int list() {
		GlassModule glass = active(GlassModule.class);
		return glass != null ? glass.list() : GlassModule.DEFAULT_LIST;
	}

	public static int tooltip() {
		GlassModule glass = active(GlassModule.class);
		return glass != null ? glass.tooltip() : GlassModule.DEFAULT_TOOLTIP;
	}

	public static int roundness() {
		GlassModule glass = active(GlassModule.class);
		return glass != null ? glass.roundness() : GlassModule.DEFAULT_ROUNDNESS;
	}

	public static boolean shadow() {
		GlassModule glass = active(GlassModule.class);
		return glass == null || glass.shadow();
	}

	public static int highlight() {
		ShineModule shine = active(ShineModule.class);
		return shine != null ? shine.highlight() : ShineModule.DEFAULT_HIGHLIGHT & 0x00FFFFFF;
	}

	public static float rim() {
		ShineModule shine = active(ShineModule.class);
		return shine != null ? shine.rim() : 1;
	}

	public static float sheen() {
		ShineModule shine = active(ShineModule.class);
		return shine != null ? shine.sheen() : 1;
	}

	public static boolean blur() {
		BackgroundModule background = active(BackgroundModule.class);
		return background == null || background.blur();
	}

	public static int dim() {
		BackgroundModule background = active(BackgroundModule.class);
		return background != null ? background.dim() : BackgroundModule.DEFAULT_DIM;
	}

	private static <T extends Module> T active(Class<T> type) {
		if (FemboyWare.getModules() == null) return null;
		T module = FemboyWare.getModules().get(type);
		return module != null && module.isEnabled() ? module : null;
	}
}
