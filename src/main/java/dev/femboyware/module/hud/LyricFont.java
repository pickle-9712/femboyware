package dev.femboyware.module.hud;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.femboyware.gui.UiScale;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Smooth fonts for lyrics. Glyphs look sharpest rasterised at the size they land on screen, and lyrics
 * can be any size and tilted, so each font has copies at a range of sizes and the next size up is used.
 * Inter covers Latin, Cyrillic and Greek; other scripts (Chinese, Japanese, Korean) fall back to
 * Minecraft's font. Inter is under the SIL Open Font License; see assets/femboyware/font/inter-ofl.txt.
 */
public enum LyricFont {
	INTER("inter"), INTER_BOLD("inter_bold"), POPPINS("poppins");

	private static final int[] SIZES = {2, 3, 4, 6, 8, 10, 12, 16, 20, 24};
	/** Glyph textures holding lyric fonts, which are drawn with smooth filtering. */
	private static final Set<GpuTextureView> SMOOTH = Collections.newSetFromMap(new WeakHashMap<>());

	private final String name;
	private final FontDescription[] sources = new FontDescription[10];

	LyricFont(String name) {
		this.name = name;
	}

	private FontDescription source(int index) {
		if (sources[index] == null) sources[index] = new FontDescription.Resource(Identifier.fromNamespaceAndPath("femboyware", "lyric_" + name + "_" + SIZES[index]));
		return sources[index];
	}

	/** {@code string} in this font, for drawing at {@code scale} times normal GUI size. */
	public Component text(String string, float scale, boolean italic) {
		float pixels = UiScale.density() * scale;
		int index = 0;
		while (index < SIZES.length - 1 && SIZES[index] < pixels) index++;
		return Component.literal(string).setStyle(Style.EMPTY.withFont(source(index)).withItalic(italic));
	}

	public static void noteAtlas(Identifier font, GpuTextureView view) {
		if (font.getNamespace().equals("femboyware") && font.getPath().startsWith("lyric_")) SMOOTH.add(view);
	}

	public static boolean isSmooth(GpuTextureView view) {
		return SMOOTH.contains(view);
	}

	/** {@code value} moved to the nearest real screen pixel, so straight text stays crisp under smooth filtering. */
	static float snap(float value) {
		int density = UiScale.density();
		return Math.round(value * density) / (float) density;
	}

	/** {@code color} with its alpha multiplied by {@code alpha}. */
	static int fade(int color, float alpha) {
		return (Math.round((color >>> 24) * Math.max(0, Math.min(1, alpha))) << 24) | (color & 0xFFFFFF);
	}

	/** Blends from {@code from} to {@code to}, alpha included. */
	static int mix(int from, int to, float amount) {
		float t = Math.max(0, Math.min(1, amount));
		int color = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			int a = (from >>> shift) & 0xFF, b = (to >>> shift) & 0xFF;
			color |= Math.round(a + (b - a) * t) << shift;
		}
		return color;
	}
}
