package dev.femboyware.module.render;

import dev.femboyware.gui.UiScale;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * Draws text in a smooth font (Poppins) instead of Minecraft's pixel font: every screen outside
 * a world, and femboyware's own ClickGUI, HUD, nametags and labels in game. Minecraft's in-game
 * HUD, chat and inventory keep their normal font. Characters Poppins lacks fall back to
 * Minecraft's font. Poppins is under the SIL Open Font License; see assets/femboyware/font/ofl.txt.
 *
 * <p>Minecraft rasterises TTF glyphs at the font size times its oversample, then scales them
 * onto the screen. Unless that matches the real pixel size the glyphs get resampled and look
 * fuzzy, so there's a copy of the font for each pixel density and the matching one is picked.
 */
public class CleanFontModule extends Module {
	/** Ask for these to get the clean font at whatever density the text is drawn at. */
	public static final FontDescription CLEAN = font("clean");
	public static final FontDescription TITLE = font("title");
	private static final FontDescription[] CLEAN_BY_DENSITY = byDensity("clean");
	private static final FontDescription[] TITLE_BY_DENSITY = byDensity("title");

	public CleanFontModule() {
		super("Clean Font", "Smooth font for all text instead of Minecraft's pixel font", Category.COSMETIC);
		setEnabled(true);
	}

	/** Called from TextRendererMixin for every run of text: the font to really use. */
	public FontDescription replace(FontDescription source) {
		int density = Math.min(UiScale.MAX_DENSITY, UiScale.density());
		if (CLEAN.equals(source)) return CLEAN_BY_DENSITY[density];
		if (TITLE.equals(source)) return TITLE_BY_DENSITY[density];
		// In a small window text is drawn a pixel per pixel, where the pixel font reads better than any smooth one
		if (density < UiScale.MIN_CLEAN_DENSITY) return source;
		if (!isEnabled() || !FontDescription.DEFAULT.equals(source)) return source;
		// In a world, Minecraft's own HUD, chat and inventory keep their normal font; only ours changes
		if (mc.level != null && !UiScale.isClientText()) return source;
		return CLEAN_BY_DENSITY[density];
	}

	private static FontDescription font(String name) {
		return new FontDescription.Resource(Identifier.fromNamespaceAndPath("femboyware", name));
	}

	private static FontDescription[] byDensity(String name) {
		FontDescription[] fonts = new FontDescription[UiScale.MAX_DENSITY + 1];
		for (int density = 1; density <= UiScale.MAX_DENSITY; density++) fonts[density] = font(name + "_" + density);
		fonts[0] = fonts[1];
		return fonts;
	}
}
