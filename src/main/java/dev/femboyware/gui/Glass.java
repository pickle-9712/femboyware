package dev.femboyware.gui;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Liquid glass surfaces: a rounded, translucent body with a sheen that's strongest at the top, a
 * bright rim that fades down the sides and catches the light again along the bottom edge, and an
 * optional soft shadow.
 *
 * <p>Each shape is drawn once, pixel by pixel with smooth edges, into a texture at the real
 * screen resolution, then reused while its size and look stay the same: a whole panel is one
 * textured quad, and every module pill of the same size shares one texture. Drawing them from
 * thousands of thin rectangles each frame made the ClickGUI lag.
 */
public final class Glass {
	/** Alpha of the sheen at the very top and bottom of a surface. */
	private static final int SHEEN_TOP = 0x40;
	private static final int SHEEN_BOTTOM = 0x08;
	/** Alpha of the rim highlight at the top, halfway down and at the bottom. */
	private static final int RIM_TOP = 0xC8;
	private static final int RIM_MIDDLE = 0x22;
	private static final int RIM_BOTTOM = 0x70;
	private static final float SHADOW_ALPHA = 0.28f;
	/** Rounded shapes with corners this small, like slider tracks, are cheap enough to draw directly. */
	private static final int DIRECT_RADIUS = 8;
	/** Textures kept for reuse, and roughly how many pixels they may hold between them. */
	private static final int MAX_TEXTURES = 200;
	private static final long MAX_PIXELS = 24_000_000;
	/** Plain shapes bigger than this many pixels are drawn from fills rather than kept as a texture. */
	private static final long DIRECT_AREA = 250_000;
	/**
	 * A texture drawn this recently may still be waiting in this frame's batch, so it's never let go
	 * then: releasing one that's about to be drawn is what makes the GL error and the garbage on screen.
	 */
	private static final long IN_USE_NANOS = 500_000_000L;

	/** Everything a shape's pixels depend on. */
	private record Key(int width, int height, int radius, int rim, int tint, int highlight, int rimStrength, int sheenStrength,
					   int shadow, int shadowOffset, Kind kind) {}

	private enum Kind { GLASS, SOLID, ELLIPSE }

	private static final class Cached {
		final Identifier id;
		final int margin;
		final long pixels;
		long used = System.nanoTime();

		Cached(Identifier id, int margin, long pixels) {
			this.id = id;
			this.margin = margin;
			this.pixels = pixels;
		}
	}

	private static final Map<Key, Cached> CACHE = new LinkedHashMap<>(64, 0.75f, true);
	private static long cachedPixels;
	private static int nextId;

	private Glass() {
	}

	/** A glass surface over {@code x1..x2, y1..y2} in GUI units, tinted by {@code tint} (its alpha is how strong). */
	public static void draw(GuiGraphics context, int x1, int y1, int x2, int y2, int radius, int tint, boolean shadow) {
		int density = UiScale.density();
		int width = (x2 - x1) * density, height = (y2 - y1) * density;
		if (width <= 0 || height <= 0) return;
		int r = Math.min(radius * density, Math.min(width, height) / 2);
		int rim = Math.max(1, Math.round(density * 0.5f));
		boolean withShadow = shadow && Theme.shadow();
		Key key = new Key(width, height, r, rim, tint, Theme.highlight(), Math.round(Theme.rim() * 100), Math.round(Theme.sheen() * 100),
			withShadow ? Math.max(2, density * 2) : 0, withShadow ? density : 0, Kind.GLASS);
		blit(context, key, x1 * density, y1 * density, density);
	}

	/** A plain rounded rectangle, solid or translucent, with no glass effects. */
	public static void rounded(GuiGraphics context, int x1, int y1, int x2, int y2, int radius, int color) {
		int density = UiScale.density();
		int width = (x2 - x1) * density, height = (y2 - y1) * density;
		if (width <= 0 || height <= 0) return;
		int r = Math.min(radius * density, Math.min(width, height) / 2);
		// Small corners, and big panels (whole app windows and their backdrops), go straight from fills:
		// a texture the size of a window costs millions of pixels to paint and keep for a barely softer edge
		if (r <= DIRECT_RADIUS || (long) width * height > DIRECT_AREA) {
			context.pose().pushMatrix();
			context.pose().scale(1f / density, 1f / density);
			pixels(context, x1 * density, y1 * density, x2 * density, y2 * density, r, color);
			context.pose().popMatrix();
			return;
		}
		blit(context, new Key(width, height, r, 0, color, 0, 0, 0, 0, 0, Kind.SOLID), x1 * density, y1 * density, density);
	}

	/** A soft-edged oval, such as a shadow on the ground, filling {@code x1..x2, y1..y2} in GUI units. */
	public static void ellipse(GuiGraphics context, int x1, int y1, int x2, int y2, int color) {
		int density = UiScale.density();
		int width = (x2 - x1) * density, height = (y2 - y1) * density;
		if (width <= 0 || height <= 0) return;
		blit(context, new Key(width, height, 0, 0, color, 0, 0, 0, 0, 0, Kind.ELLIPSE), x1 * density, y1 * density, density);
	}

	// ---------------------------------------------------------------- texture cache

	private static void blit(GuiGraphics context, Key key, int left, int top, int density) {
		Cached cached = CACHE.get(key);
		if (cached == null) cached = create(key);
		cached.used = System.nanoTime();
		int margin = cached.margin;
		int textureWidth = key.width + margin * 2;
		int textureHeight = key.height + margin * 2;
		context.pose().pushMatrix();
		context.pose().scale(1f / density, 1f / density);
		context.blit(RenderPipelines.GUI_TEXTURED, cached.id, left - margin, top - margin, 0, 0,
			textureWidth, textureHeight, textureWidth, textureHeight, textureWidth, textureHeight);
		context.pose().popMatrix();
	}

	private static Cached create(Key key) {
		int margin = key.shadow + key.shadowOffset;
		int textureWidth = key.width + margin * 2;
		int textureHeight = key.height + margin * 2;
		NativeImage image = new NativeImage(textureWidth, textureHeight, true);
		paint(image, key, margin);
		Identifier id = Identifier.fromNamespaceAndPath("femboyware", "glass/" + nextId++);
		DynamicTexture texture = new DynamicTexture(() -> "femboyware glass", image);
		Minecraft.getInstance().getTextureManager().register(id, texture);
		texture.upload();

		Cached cached = new Cached(id, margin, (long) textureWidth * textureHeight);
		CACHE.put(key, cached);
		cachedPixels += cached.pixels;
		// Forget the least recently drawn shapes once there are too many, or too many pixels, but
		// never one still in use: if they all are, go over the limit for now rather than break the frame
		long now = System.nanoTime();
		Iterator<Map.Entry<Key, Cached>> oldest = CACHE.entrySet().iterator();
		while ((CACHE.size() > MAX_TEXTURES || cachedPixels > MAX_PIXELS) && oldest.hasNext()) {
			Cached old = oldest.next().getValue();
			if (old == cached || now - old.used < IN_USE_NANOS) break;
			oldest.remove();
			cachedPixels -= old.pixels;
			Minecraft.getInstance().getTextureManager().release(old.id);
		}
		return cached;
	}

	// ---------------------------------------------------------------- painting

	private static void paint(NativeImage image, Key key, int margin) {
		int width = image.getWidth(), height = image.getHeight();
		float halfWidth = key.width / 2f, halfHeight = key.height / 2f;
		// The body and rim colours only change down the shape, so work them out once per row
		int[] body = new int[key.height];
		int[] rim = new int[key.height];
		for (int row = 0; row < key.height; row++) {
			float t = (row + 0.5f) / key.height;
			body[row] = key.kind == Kind.GLASS ? surface(key.tint, t, key.highlight, key.sheenStrength / 100f) : key.tint;
			rim[row] = key.kind == Kind.GLASS ? rimColor(t, key.highlight, key.rimStrength / 100f) : 0;
		}
		for (int y = 0; y < height; y++) {
			float sy = y - margin + 0.5f;
			int row = Mth.clamp((int) sy, 0, key.height - 1);
			for (int x = 0; x < width; x++) {
				float sx = x - margin + 0.5f;
				int color = 0;
				if (key.kind == Kind.ELLIPSE) {
					// Fades smoothly from the middle out to the edge
					float dx = (sx - halfWidth) / halfWidth, dy = (sy - halfHeight) / halfHeight;
					float fade = 1 - Mth.clamp((float) Math.sqrt(dx * dx + dy * dy), 0, 1);
					image.setPixel(x, y, scaleAlpha(key.tint, fade * fade * (3 - 2 * fade)));
					continue;
				}
				if (key.shadow > 0) {
					float distance = roundedDistance(sx - halfWidth, sy - key.shadowOffset - halfHeight, halfWidth, halfHeight, key.radius);
					float fade = 1 - Mth.clamp(distance / key.shadow, 0, 1);
					color = ((int) (SHADOW_ALPHA * fade * fade * 255) << 24);
				}
				float distance = roundedDistance(sx - halfWidth, sy - halfHeight, halfWidth, halfHeight, key.radius);
				// How much of this pixel the shape covers, for smooth edges
				float coverage = Mth.clamp(0.5f - distance, 0, 1);
				if (coverage > 0) {
					color = over(scaleAlpha(body[row], coverage), color);
					if (key.rim > 0) {
						float rimCoverage = coverage * Mth.clamp(distance + key.rim + 0.5f, 0, 1);
						if (rimCoverage > 0) color = over(scaleAlpha(rim[row], rimCoverage), color);
					}
				}
				image.setPixel(x, y, color);
			}
		}
	}

	/** Signed distance from a point (relative to the centre) to a rounded rectangle's edge; negative inside. */
	private static float roundedDistance(float x, float y, float halfWidth, float halfHeight, float radius) {
		float qx = Math.abs(x) - (halfWidth - radius);
		float qy = Math.abs(y) - (halfHeight - radius);
		float outside = (float) Math.sqrt(Math.max(qx, 0) * Math.max(qx, 0) + Math.max(qy, 0) * Math.max(qy, 0));
		return outside + Math.min(Math.max(qx, qy), 0) - radius;
	}

	/** {@code top} laid over {@code bottom}, both ARGB with straight alpha. */
	private static int over(int top, int bottom) {
		float topAlpha = (top >>> 24) / 255f;
		float bottomAlpha = (bottom >>> 24) / 255f;
		float alpha = topAlpha + bottomAlpha * (1 - topAlpha);
		if (alpha <= 0) return 0;
		int r = mixChannel((top >> 16) & 0xFF, topAlpha, (bottom >> 16) & 0xFF, bottomAlpha, alpha);
		int g = mixChannel((top >> 8) & 0xFF, topAlpha, (bottom >> 8) & 0xFF, bottomAlpha, alpha);
		int b = mixChannel(top & 0xFF, topAlpha, bottom & 0xFF, bottomAlpha, alpha);
		return (Math.round(alpha * 255) << 24) | (r << 16) | (g << 8) | b;
	}

	private static int mixChannel(int top, float topAlpha, int bottom, float bottomAlpha, float alpha) {
		return Math.min(255, Math.round((top * topAlpha + bottom * bottomAlpha * (1 - topAlpha)) / alpha));
	}

	private static int scaleAlpha(int color, float factor) {
		return ((int) ((color >>> 24) * factor) << 24) | (color & 0xFFFFFF);
	}

	/** The body colour {@code t} of the way down: the highlight's sheen over the tint, as one colour. */
	private static int surface(int tint, float t, int highlight, float sheenStrength) {
		float fade = (1 - t) * (1 - t);
		float sheen = Math.min(1, (SHEEN_BOTTOM + (SHEEN_TOP - SHEEN_BOTTOM) * fade) / 255f * sheenStrength);
		return over(((int) (sheen * 255) << 24) | highlight, tint);
	}

	/** The rim's highlight {@code t} of the way down: bright on top, faint halfway, catching light again at the bottom. */
	private static int rimColor(float t, int highlight, float rimStrength) {
		float alpha = t < 0.5f
			? RIM_TOP + (RIM_MIDDLE - RIM_TOP) * (t / 0.5f)
			: RIM_MIDDLE + (RIM_BOTTOM - RIM_MIDDLE) * ((t - 0.5f) / 0.5f);
		return ((int) Math.min(255, alpha * rimStrength) << 24) | highlight;
	}

	/** A small rounded rectangle drawn straight from fills, in screen pixels. */
	private static void pixels(GuiGraphics context, int left, int top, int right, int bottom, int r, int color) {
		context.fill(left, top + r, right, bottom - r, color);
		for (int row = 0; row < r; row++) {
			double dy = r - row - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
			context.fill(left + inset, top + row, right - inset, top + row + 1, color);
			context.fill(left + inset, bottom - row - 1, right - inset, bottom - row, color);
		}
	}
}
