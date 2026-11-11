package dev.femboyware.gui;

import net.minecraft.client.Minecraft;

/**
 * Femboyware's screens lay out on a canvas about 360 units tall and scale it to the window, so
 * they take the same share of the screen whatever Minecraft's GUI scale is. The scale is always a
 * whole number of screen pixels per unit, so the clean font lands exactly on pixels and stays
 * crisp; {@link #density()} tells the font which size to rasterise at.
 */
public final class UiScale {
	/** Rough height of the canvas in layout units. */
	public static final int VIRTUAL_HEIGHT = 360;
	/** Below this many screen pixels per unit a smooth 8 pixel font turns to mush; the pixel font is better there. */
	public static final int MIN_CLEAN_DENSITY = 2;
	/** Densest copy of the clean font there is; enough for an 8K screen. */
	public static final int MAX_DENSITY = 12;

	/** Screen pixels per unit while one of our canvases is being drawn, or 0 outside them. */
	private static int canvasDensity;
	/** How deep we are in femboyware's own drawing, which uses the clean font even inside a world. */
	private static int clientDepth;

	private UiScale() {
	}

	/**
	 * Screen pixels per layout unit on our canvas: the window height over 360, rounded to a whole
	 * number, and never below 2, since the clean font is unreadable drawn at one pixel per pixel.
	 */
	public static int canvasDensity() {
		int pixels = Minecraft.getInstance().getWindow().getHeight();
		return Math.min(MAX_DENSITY, Math.max(MIN_CLEAN_DENSITY, Math.round(pixels / (float) VIRTUAL_HEIGHT)));
	}

	/** How much to scale GUI coordinates by to draw on the canvas. */
	public static float scale() {
		return canvasDensity() / (float) Minecraft.getInstance().getWindow().getGuiScale();
	}

	/** Marks the start of drawing on our canvas, so text uses the canvas density. */
	public static void begin() {
		canvasDensity = canvasDensity();
		beginClient();
	}

	/** Like {@link #begin()}, at a density the caller picked, for screens that need a canvas of some size. */
	public static void begin(int density) {
		canvasDensity = density;
		beginClient();
	}

	/**
	 * For text drawn scaled up: switches to the font copy for {@code density} pixels per unit so the
	 * glyphs are made at the size they'll show at, not stretched. Returns what to restore.
	 */
	public static int pushDensity(int density) {
		int old = canvasDensity;
		canvasDensity = Math.max(1, Math.min(MAX_DENSITY, density));
		return old;
	}

	public static void popDensity(int old) {
		canvasDensity = old;
	}

	public static void end() {
		canvasDensity = 0;
		endClient();
	}

	/** Marks femboyware's own drawing (HUD, nametags, ESP labels) so it gets the clean font in a world too. */
	public static void beginClient() {
		clientDepth++;
	}

	public static void endClient() {
		clientDepth = Math.max(0, clientDepth - 1);
	}

	/** Draws Minecraft's own text (like a copy of a vanilla name tag) from inside our drawing, in its normal font. Returns what to resume. */
	public static int suspendClient() {
		int depth = clientDepth;
		clientDepth = 0;
		return depth;
	}

	public static void resumeClient(int depth) {
		clientDepth = depth;
	}

	public static boolean isClientText() {
		return clientDepth > 0;
	}

	/** Screen pixels per GUI unit for text being drawn right now: the canvas's, or Minecraft's GUI scale. */
	public static int density() {
		if (canvasDensity > 0) return canvasDensity;
		return Math.max(1, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
	}
}
