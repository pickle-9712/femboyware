package dev.femboyware.gui;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Module;
import dev.femboyware.module.hud.HudColors;
import dev.femboyware.module.theme.BackgroundModule;
import dev.femboyware.module.theme.ColorsModule;
import dev.femboyware.module.theme.GlassModule;
import dev.femboyware.module.theme.ShineModule;
import dev.femboyware.module.theme.WallpaperModule;
import java.util.function.ToIntFunction;

/**
 * The menu's and HUD's look, from the Customize panel's modules. Colours come from Colors (its
 * defaults while it's off); while a Wallpaper is on, its palette replaces the main ones, and
 * Colors' own come back when it's turned off.
 */
public final class Theme {
	private Theme() {
	}

	// ---------------------------------------------------------------- the ClickGUI

	public static int accent() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xFF000000 | p.accent() : color(ColorsModule::accent, ColorsModule.DEFAULT_ACCENT);
	}

	public static int text() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xFF000000 | p.text() : color(ColorsModule::text, ColorsModule.DEFAULT_TEXT);
	}

	public static int muted() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xFF000000 | p.muted() : color(ColorsModule::muted, ColorsModule.DEFAULT_MUTED);
	}

	/** The main window's tint. Over a wallpaper it shades the picture rather than showing the world through. */
	public static int panel() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.base(), WallpaperModule.shade()) : color(ColorsModule::panel, ColorsModule.DEFAULT_PANEL);
	}

	/** Small glass panels and pills, which have no picture behind them: solid over a wallpaper. */
	public static int solidPanel() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xF0000000 | p.base() : panel();
	}

	public static int content() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.base(), 0.35f) : color(ColorsModule::content, ColorsModule.DEFAULT_CONTENT);
	}

	public static int surface() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.text(), 0.09f) : color(ColorsModule::surface, ColorsModule.DEFAULT_SURFACE);
	}

	public static int surfaceHover() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.text(), 0.16f) : color(ColorsModule::surfaceHover, ColorsModule.DEFAULT_SURFACE_HOVER);
	}

	public static int switchOff() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.muted(), 0.35f) : color(ColorsModule::switchOff, ColorsModule.DEFAULT_SWITCH_OFF);
	}

	public static int knob() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xFF000000 | p.text() : color(ColorsModule::knob, ColorsModule.DEFAULT_KNOB);
	}

	public static int track() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.muted(), 0.35f) : color(ColorsModule::track, ColorsModule.DEFAULT_TRACK);
	}

	public static int divider() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.muted(), 0.25f) : color(ColorsModule::divider, ColorsModule.DEFAULT_DIVIDER);
	}

	public static int settingRow() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.base(), 0.3f) : color(ColorsModule::settingRow, ColorsModule.DEFAULT_SETTING_ROW);
	}

	public static int tooltip() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xF0000000 | p.base() : color(ColorsModule::tooltip, ColorsModule.DEFAULT_TOOLTIP);
	}

	public static int highlight() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return (p != null ? p.text() : color(ColorsModule::highlight, ColorsModule.DEFAULT_HIGHLIGHT)) & 0x00FFFFFF;
	}

	public static int dim() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.base(), 0.3f) : color(ColorsModule::dim, ColorsModule.DEFAULT_DIM);
	}

	public static int online() {
		return color(ColorsModule::online, ColorsModule.DEFAULT_ONLINE);
	}

	public static int roundness() {
		GlassModule glass = active(GlassModule.class);
		return glass != null ? glass.roundness() : GlassModule.DEFAULT_ROUNDNESS;
	}

	public static boolean shadow() {
		GlassModule glass = active(GlassModule.class);
		return glass == null || glass.shadow();
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

	// ---------------------------------------------------------------- the HUD

	private static boolean hudMatchesMenu() {
		ColorsModule colors = active(ColorsModule.class);
		return colors == null || colors.matchMenu() || WallpaperModule.active() != null;
	}

	public static int hudText() {
		return hudMatchesMenu() ? text() : color(ColorsModule::hudText, ColorsModule.DEFAULT_TEXT);
	}

	public static int hudAccent() {
		return hudMatchesMenu() ? accent() : color(ColorsModule::hudAccent, ColorsModule.DEFAULT_ACCENT);
	}

	public static int hudMuted() {
		return hudMatchesMenu() ? muted() : color(ColorsModule::hudMuted, ColorsModule.DEFAULT_HUD_MUTED);
	}

	/** Behind HUD elements and the module list. */
	public static int list() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? alpha(p.base(), 0.6f) : color(ColorsModule::hudBackground, ColorsModule.DEFAULT_HUD_BACKGROUND);
	}

	public static HudColors listColors() {
		ColorsModule colors = active(ColorsModule.class);
		return colors != null ? colors.listColors() : HudColors.PASTEL;
	}

	public static int listColor() {
		return color(ColorsModule::listColor, ColorsModule.DEFAULT_ACCENT);
	}

	public static HudColors watermarkColors() {
		ColorsModule colors = active(ColorsModule.class);
		return colors != null ? colors.watermarkColors() : HudColors.PASTEL;
	}

	public static int watermarkColor() {
		return color(ColorsModule::watermarkColor, ColorsModule.DEFAULT_ACCENT);
	}

	public static int healthFull() {
		return color(ColorsModule::healthFull, ColorsModule.DEFAULT_HEALTH_FULL);
	}

	public static int healthLow() {
		return color(ColorsModule::healthLow, ColorsModule.DEFAULT_HEALTH_LOW);
	}

	public static int absorption() {
		return color(ColorsModule::absorption, ColorsModule.DEFAULT_ABSORPTION);
	}

	public static int good() {
		return color(ColorsModule::good, ColorsModule.DEFAULT_GOOD);
	}

	public static int okay() {
		return color(ColorsModule::okay, ColorsModule.DEFAULT_OKAY);
	}

	public static int warning() {
		return color(ColorsModule::low, ColorsModule.DEFAULT_LOW);
	}

	public static int pressedText() {
		return color(ColorsModule::pressedText, ColorsModule.DEFAULT_PRESSED_TEXT);
	}

	public static int staff() {
		return color(ColorsModule::staff, ColorsModule.DEFAULT_STAFF);
	}

	public static int islandPill() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xC0000000 | p.base() : color(ColorsModule::islandPill, ColorsModule.DEFAULT_ISLAND_PILL);
	}

	public static int islandHighlight() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xFF000000 | p.accent() : color(ColorsModule::islandHighlight, ColorsModule.DEFAULT_ISLAND_HIGHLIGHT);
	}

	public static int islandText() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xFF000000 | p.text() : color(ColorsModule::islandText, ColorsModule.DEFAULT_ISLAND_TEXT);
	}

	public static int islandBars() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xFF000000 | p.accent() : color(ColorsModule::islandBars, ColorsModule.DEFAULT_ISLAND_BARS);
	}

	public static int islandTime() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0xFF000000 | p.text() : color(ColorsModule::islandTime, ColorsModule.DEFAULT_ISLAND_TIME);
	}

	public static int editorDim() { return color(ColorsModule::editorDim, 0x30000000); }
	public static int editorOutline() { return color(ColorsModule::editorOutline, 0x80FFFFFF); }
	public static int chatPrefix() { return color(ColorsModule::chatPrefix, 0xFFFF55FF); }
	public static int gradientStart() { return color(ColorsModule::gradientStart, ColorsModule.DEFAULT_ACCENT); }
	public static int gradientEnd() { return color(ColorsModule::gradientEnd, 0xFF8AD8FF); }
	public static int barTrack() { return color(ColorsModule::barTrack, 0x40000000); }
	public static int progressTrack() { return color(ColorsModule::progressTrack, 0x40FFFFFF); }
	public static int offDot() { return color(ColorsModule::offDot, 0xFF7A7280); }

	/** Behind selected text: Colors' own, or the accent over a wallpaper. */
	public static int textSelection() {
		WallpaperModule.Palette p = WallpaperModule.active();
		return p != null ? 0x90000000 | p.accent() : color(ColorsModule::textSelection, 0x90FF77C8);
	}

	// ---------------------------------------------------------------- the main menu

	public static int swirl() { return color(ColorsModule::swirl, 0xFFFFFFFF); }
	public static int blob(int i) { return color(c -> c.blob(i), ColorsModule.DEFAULT_BLOBS[i]); }
	public static int menuTopShade() { return color(ColorsModule::topShade, 0x60000000); }
	public static int menuBottomShade() { return color(ColorsModule::bottomShade, 0x70000000); }
	public static int menuTitle() { return color(ColorsModule::titleText, 0xFFFFFFFF); }
	public static int menuTagline() { return color(ColorsModule::taglineText, 0xFFE8CFE2); }
	public static int menuPrimary() { return color(ColorsModule::primary, 0xFFFF6FBF); }
	public static int menuPrimaryHover() { return color(ColorsModule::primaryHover, 0xFFFF92CF); }
	public static int menuSecondary() { return color(ColorsModule::secondary, 0xF0401838); }
	public static int menuSecondaryHover() { return color(ColorsModule::secondaryHover, 0xF0582452); }
	public static int menuButtonText() { return color(ColorsModule::buttonText, 0xFFFFFFFF); }
	public static int menuNameTag() { return color(ColorsModule::nameTag, 0xF0401838); }
	public static int menuFooter() { return color(ColorsModule::footer, 0xB0FFFFFF); }

	// ---------------------------------------------------------------- helpers

	private static int color(ToIntFunction<ColorsModule> getter, int fallback) {
		ColorsModule colors = active(ColorsModule.class);
		return colors != null ? getter.applyAsInt(colors) : fallback;
	}

	private static int alpha(int rgb, float alpha) {
		return Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | (rgb & 0x00FFFFFF);
	}

	private static <T extends Module> T active(Class<T> type) {
		if (FemboyWare.getModules() == null) return null;
		try {
			T module = FemboyWare.getModules().get(type);
			return module != null && module.isEnabled() ? module : null;
		} catch (IllegalArgumentException notYet) {
			return null;
		}
	}
}
