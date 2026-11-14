package dev.femboyware.module.theme;

import com.mojang.blaze3d.platform.NativeImage;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * A picture behind the ClickGUI: the window turns solid with it, and the menu and HUD take on the
 * picture's palette. Your own colours from Colors come back when it's off.
 */
public class WallpaperModule extends Module {
	/** The colours a wallpaper brings with it: everything else is worked out from these. */
	public record Palette(int base, int accent, int text, int muted) {
	}

	public enum Preset {
		SAKURA(new Palette(0x2A1020, 0xFF8FC8, 0xFFF3F8, 0xD7A9C0)),
		MIDNIGHT(new Palette(0x0B0E1F, 0x8C9CFF, 0xEEF0FF, 0x9AA0C8)),
		OCEAN(new Palette(0x06202E, 0x3FD7E0, 0xE8FBFF, 0x8FBFCC)),
		SUNSET(new Palette(0x2B1210, 0xFF9A4D, 0xFFF1E6, 0xD9A890)),
		AURORA(new Palette(0x07161A, 0x5CFFB0, 0xEAFFF6, 0x8CC8B4)),
		SYNTHWAVE(new Palette(0x12081F, 0xFF4FD8, 0xF6E9FF, 0xB392C8)),
		FOREST(new Palette(0x0F1A10, 0x8BD86A, 0xF0FFE8, 0xA3BF98));

		public final Palette palette;

		Preset(Palette palette) {
			this.palette = palette;
		}
	}

	private static final int W = 640, H = 360;

	private final EnumSetting<Preset> preset = add(new EnumSetting<>("Preset", "Which picture, and the palette that comes with it", Preset.SAKURA));
	private final NumberSetting shade = add(new NumberSetting("Shade", "How much the panels darken the picture so text stays readable", 0.45, 0, 0.9, 0.05));

	private final Map<Preset, Identifier> textures = new EnumMap<>(Preset.class);
	private final Map<Preset, Boolean> loading = new EnumMap<>(Preset.class);

	public WallpaperModule() {
		super("Wallpaper", "Preset backgrounds for the ClickGUI that recolour the menu and HUD to match", Category.THEME);
	}

	@Override
	public String getInfo() {
		return preset.getDisplayName();
	}

	/** The palette in use, or null while it's off. */
	public static Palette active() {
		WallpaperModule module = module();
		return module != null && module.isEnabled() ? module.preset.get().palette : null;
	}

	private static WallpaperModule module() {
		if (FemboyWare.getModules() == null) return null;
		try {
			return FemboyWare.getModules().get(WallpaperModule.class);
		} catch (IllegalArgumentException notYet) {
			return null;
		}
	}

	/** How much the glass panels shade the picture, 0 to 1. */
	public static float shade() {
		WallpaperModule module = module();
		return module == null ? 0.45f : module.shade.getFloat();
	}

	/**
	 * Fills the rounded window {@code x1..x2, y1..y2} with the picture, cropped to cover it. False if
	 * it's off or the picture isn't made yet, so the usual see-through glass is drawn instead.
	 */
	public static boolean draw(GuiGraphics context, int x1, int y1, int x2, int y2, int radius) {
		WallpaperModule module = module();
		if (module == null || !module.isEnabled()) return false;
		Identifier texture = module.texture(module.preset.get());
		if (texture == null) return false;
		int w = x2 - x1, h = y2 - y1;
		if (w <= 0 || h <= 0) return true;
		// Cover the window, cropping the picture's long side
		float scale = Math.max(w / (float) W, h / (float) H);
		float u0 = (W - w / scale) / 2, v0 = (H - h / scale) / 2;
		int r = Math.min(radius, Math.min(w, h) / 2);
		// Row by row through the rounded corners, then the middle in one go
		for (int row = 0; row < r; row++) {
			double dy = r - row - 0.5;
			int inset = (int) Math.ceil(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			strip(context, texture, x1, y1, w, scale, u0, v0, inset, row, 1);
			strip(context, texture, x1, y1, w, scale, u0, v0, inset, h - 1 - row, 1);
		}
		strip(context, texture, x1, y1, w, scale, u0, v0, 0, r, h - 2 * r);
		return true;
	}

	private static void strip(GuiGraphics context, Identifier texture, int x1, int y1, int w, float scale, float u0, float v0, int inset, int row, int rows) {
		if (rows <= 0 || w - 2 * inset <= 0) return;
		float u = u0 + inset / scale, v = v0 + row / scale;
		context.blit(RenderPipelines.GUI_TEXTURED, texture, x1 + inset, y1 + row, u, v, w - 2 * inset, rows,
			Math.max(1, Math.round((w - 2 * inset) / scale)), Math.max(1, Math.round(rows / scale)), W, H);
	}

	private Identifier texture(Preset which) {
		Identifier ready = textures.get(which);
		if (ready != null || loading.containsKey(which)) return ready;
		loading.put(which, true);
		CompletableFuture.supplyAsync(() -> generate(which)).thenAccept(image -> mc.execute(() -> {
			Identifier location = Identifier.fromNamespaceAndPath("femboyware", "wallpaper/" + which.name().toLowerCase());
			DynamicTexture tex = new DynamicTexture(() -> "femboyware wallpaper", image);
			mc.getTextureManager().register(location, tex);
			tex.upload();
			textures.put(which, location);
		}));
		return null;
	}

	// ---------------------------------------------------------------- the pictures

	private static NativeImage generate(Preset which) {
		NativeImage image = new NativeImage(W, H, false);
		int[] px = new int[W * H];
		Random random = new Random(which.ordinal() * 7919L + 3);
		switch (which) {
			case SAKURA -> sakura(px, random);
			case MIDNIGHT -> midnight(px, random);
			case OCEAN -> ocean(px, random);
			case SUNSET -> sunset(px, random);
			case AURORA -> aurora(px, random);
			case SYNTHWAVE -> synthwave(px, random);
			case FOREST -> forest(px, random);
		}
		for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) image.setPixel(x, y, 0xFF000000 | px[y * W + x]);
		return image;
	}

	private static void sakura(int[] px, Random random) {
		gradient(px, 0xFFC4DD, 0xF7A1C4, 0x7A3C6B);
		haze(px, 0xFFFFFF, 0.18f, 60, 11);
		// A branch across the top corner, then petals drifting everywhere
		for (int i = 0; i < 260; i++) {
			double t = i / 260.0;
			int bx = (int) (W * 0.55 + t * W * 0.5), by = (int) (H * 0.08 + Math.sin(t * 5) * 18 + t * 40);
			disc(px, bx, by, 4.5f - (float) t * 2.5f, 0x3A1B2A, 1);
		}
		for (int i = 0; i < 70; i++) disc(px, (int) (W * 0.6 + random.nextDouble() * W * 0.45), (int) (random.nextDouble() * H * 0.35), 5 + random.nextFloat() * 7, mixColor(0xFFD6E7, 0xFF8FBF, random.nextFloat()), 0.85f);
		for (int i = 0; i < 160; i++) petal(px, random.nextInt(W), random.nextInt(H), 2.5f + random.nextFloat() * 3.5f, random.nextFloat() * 6.3f, mixColor(0xFFE4EF, 0xFF9CC8, random.nextFloat()));
	}

	private static void midnight(int[] px, Random random) {
		gradient(px, 0x050716, 0x111633, 0x2A2752);
		haze(px, 0x6B5BD6, 0.22f, 120, 5);
		for (int i = 0; i < 700; i++) {
			float bright = random.nextFloat();
			disc(px, random.nextInt(W), random.nextInt((int) (H * 0.85)), bright > 0.97f ? 1.6f : 0.7f, mixColor(0x9AA6FF, 0xFFFFFF, bright), 0.4f + bright * 0.6f);
		}
		glow(px, (int) (W * 0.78), (int) (H * 0.24), 70, 0xBFC8FF, 0.35f);
		disc(px, (int) (W * 0.78), (int) (H * 0.24), 26, 0xF2F0E6, 1);
		disc(px, (int) (W * 0.78) + 10, (int) (H * 0.24) - 6, 23, 0x111633, 1);
		hills(px, 0.82, 0.05, 0x070914, 3);
	}

	private static void ocean(int[] px, Random random) {
		gradient(px, 0x0A6F8F, 0x064063, 0x021526);
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				// Caustic ripples, fading with depth
				double ripple = Math.sin(x * 0.045 + Math.sin(y * 0.03) * 3) * Math.sin(y * 0.05 + Math.sin(x * 0.02) * 2);
				float light = (float) Math.max(0, ripple) * (1 - y / (float) H) * 0.35f;
				px[y * W + x] = mixColor(px[y * W + x], 0x7FF0FF, light);
			}
		}
		for (int i = 0; i < 6; i++) rays(px, (int) (W * (0.15 + i * 0.15)), 0x9FF6FF, 0.08f, random);
		for (int i = 0; i < 90; i++) disc(px, random.nextInt(W), random.nextInt(H), 1 + random.nextFloat() * 3, 0xBFF8FF, 0.3f);
	}

	private static void sunset(int[] px, Random random) {
		gradient(px, 0x3B1E5C, 0xE0566B, 0xFFB35C);
		glow(px, W / 2, (int) (H * 0.66), 160, 0xFFE2A0, 0.5f);
		disc(px, W / 2, (int) (H * 0.66), 52, 0xFFE9B0, 1);
		haze(px, 0xFFC3A0, 0.15f, 80, 9);
		hills(px, 0.70, 0.08, 0x5A2445, 2);
		hills(px, 0.80, 0.06, 0x3A1530, 5);
		hills(px, 0.90, 0.04, 0x220B1E, 8);
	}

	private static void aurora(int[] px, Random random) {
		gradient(px, 0x02060E, 0x061826, 0x0B2A2A);
		for (int i = 0; i < 400; i++) disc(px, random.nextInt(W), random.nextInt(H), 0.7f, 0xDFFFF4, 0.3f + random.nextFloat() * 0.5f);
		// Curtains: soft vertical streaks along a wavy line
		int[] colors = {0x45FFA8, 0x2FD6C8, 0xB46CFF};
		for (int band = 0; band < 3; band++) {
			for (int x = 0; x < W; x++) {
				double centre = H * (0.28 + band * 0.1) + Math.sin(x * 0.012 + band * 2) * 30 + Math.sin(x * 0.031 + band) * 12;
				float flicker = 0.55f + 0.45f * (float) Math.sin(x * 0.09 + band * 5);
				for (int y = 0; y < H; y++) {
					double d = y - centre;
					float a = d < 0 ? (float) Math.exp(-d * d / 300) : (float) Math.exp(-d / 70) * (d < 90 ? 1 : 0);
					if (a > 0.01f) px[y * W + x] = add(px[y * W + x], colors[band], a * 0.5f * flicker);
				}
			}
		}
		hills(px, 0.86, 0.05, 0x030A0C, 4);
	}

	private static void synthwave(int[] px, Random random) {
		gradient(px, 0x12051F, 0x4A1259, 0xFF4F8B);
		int horizon = (int) (H * 0.6);
		glow(px, W / 2, horizon, 140, 0xFF8A3D, 0.4f);
		// A striped sun
		for (int y = horizon - 80; y < horizon; y++) {
			if ((horizon - y) % 14 < 4 && y > horizon - 50) continue;
			for (int x = W / 2 - 80; x < W / 2 + 80; x++) {
				double d = Math.hypot(x - W / 2.0, y - horizon);
				if (d < 80) px[y * W + x] = mixColor(0xFFE45C, 0xFF3FA4, (horizon - y) / 80f);
			}
		}
		for (int y = horizon; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = mixColor(0x1A0628, 0x0B0214, (y - horizon) / (float) (H - horizon));
		// The grid, lines meeting at the middle of the horizon
		for (int i = -20; i <= 20; i++) {
			for (int y = horizon; y < H; y++) {
				double t = (y - horizon) / (double) (H - horizon);
				int x = (int) (W / 2.0 + i * 40 * (0.08 + t * 2.4));
				if (x >= 0 && x < W) px[y * W + x] = add(px[y * W + x], 0xFF4FD8, 0.8f);
			}
		}
		for (int k = 1; k < 14; k++) {
			int y = horizon + (int) ((H - horizon) * Math.pow(k / 14.0, 2));
			if (y < H) for (int x = 0; x < W; x++) px[y * W + x] = add(px[y * W + x], 0xFF4FD8, 0.7f);
		}
	}

	private static void forest(int[] px, Random random) {
		gradient(px, 0xA9C9A0, 0x5E8A62, 0x1C3020);
		haze(px, 0xE6F2DC, 0.25f, 90, 13);
		int[] tones = {0x6E8F6A, 0x46684A, 0x2B4430, 0x14231A};
		for (int layer = 0; layer < 4; layer++) {
			int count = 14 + layer * 6;
			for (int i = 0; i < count; i++) {
				int cx = random.nextInt(W + 60) - 30;
				int base = (int) (H * (0.62 + layer * 0.11));
				int height = 70 + layer * 35 + random.nextInt(40);
				pine(px, cx, base, height, tones[layer]);
			}
			for (int y = (int) (H * (0.62 + layer * 0.11)); y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = tones[layer];
		}
	}

	// ---------------------------------------------------------------- drawing helpers

	private static void gradient(int[] px, int top, int middle, int bottom) {
		for (int y = 0; y < H; y++) {
			float t = y / (float) (H - 1);
			int c = t < 0.5f ? mixColor(top, middle, t * 2) : mixColor(middle, bottom, (t - 0.5f) * 2);
			for (int x = 0; x < W; x++) px[y * W + x] = c;
		}
	}

	/** Soft blotches of {@code color}, like mist or clouds. */
	private static void haze(int[] px, int color, float strength, double scale, int seed) {
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				float n = fbm(x / scale, y / scale, seed);
				px[y * W + x] = mixColor(px[y * W + x], color, Math.max(0, n - 0.45f) * 2 * strength);
			}
		}
	}

	private static void glow(int[] px, int cx, int cy, int radius, int color, float strength) {
		for (int y = Math.max(0, cy - radius * 2); y < Math.min(H, cy + radius * 2); y++) {
			for (int x = Math.max(0, cx - radius * 2); x < Math.min(W, cx + radius * 2); x++) {
				double d = Math.hypot(x - cx, y - cy) / (radius * 2.0);
				if (d < 1) px[y * W + x] = add(px[y * W + x], color, (float) ((1 - d) * (1 - d)) * strength);
			}
		}
	}

	private static void disc(int[] px, int cx, int cy, float radius, int color, float alpha) {
		int r = (int) Math.ceil(radius + 1);
		for (int y = cy - r; y <= cy + r; y++) {
			if (y < 0 || y >= H) continue;
			for (int x = cx - r; x <= cx + r; x++) {
				if (x < 0 || x >= W) continue;
				float edge = Math.min(1, Math.max(0, radius + 0.5f - (float) Math.hypot(x - cx, y - cy)));
				if (edge > 0) px[y * W + x] = mixColor(px[y * W + x], color, edge * alpha);
			}
		}
	}

	private static void petal(int[] px, int cx, int cy, float size, float angle, int color) {
		float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
		int r = (int) Math.ceil(size * 1.6f);
		for (int y = cy - r; y <= cy + r; y++) {
			if (y < 0 || y >= H) continue;
			for (int x = cx - r; x <= cx + r; x++) {
				if (x < 0 || x >= W) continue;
				float lx = ((x - cx) * c + (y - cy) * s) / (size * 1.5f), ly = (-(x - cx) * s + (y - cy) * c) / size;
				float d = lx * lx + ly * ly;
				if (d < 1) px[y * W + x] = mixColor(px[y * W + x], color, Math.min(1, (1 - d) * 3) * 0.9f);
			}
		}
	}

	private static void rays(int[] px, int x0, int color, float strength, Random random) {
		double lean = (random.nextDouble() - 0.5) * 0.6;
		int width = 18 + random.nextInt(25);
		for (int y = 0; y < H; y++) {
			int centre = (int) (x0 + y * lean);
			float fade = 1 - y / (float) H;
			for (int x = centre - width; x <= centre + width; x++) {
				if (x < 0 || x >= W) continue;
				float a = (1 - Math.abs(x - centre) / (float) width) * fade * strength;
				px[y * W + x] = add(px[y * W + x], color, a);
			}
		}
	}

	private static void hills(int[] px, double level, double height, int color, int seed) {
		for (int x = 0; x < W; x++) {
			double top = H * (level - height * fbm(x / 140.0, seed, seed));
			for (int y = Math.max(0, (int) top); y < H; y++) px[y * W + x] = color;
		}
	}

	private static void pine(int[] px, int cx, int base, int height, int color) {
		for (int y = base - height; y < base; y++) {
			if (y < 0 || y >= H) continue;
			float t = (y - (base - height)) / (float) height;
			// Layered tiers, wider towards the bottom
			float tier = (t * 5) % 1;
			int half = (int) (height * 0.22f * t * (0.6f + 0.4f * tier));
			for (int x = cx - half; x <= cx + half; x++) if (x >= 0 && x < W) px[y * W + x] = color;
		}
	}

	private static float fbm(double x, double y, int seed) {
		float total = 0, amplitude = 0.5f;
		for (int octave = 0; octave < 4; octave++) {
			total += amplitude * valueNoise(x, y, seed + octave * 31);
			x *= 2;
			y *= 2;
			amplitude *= 0.5f;
		}
		return total / 0.9375f;
	}

	private static float valueNoise(double x, double y, int seed) {
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
		float fx = (float) (x - x0), fy = (float) (y - y0);
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		float a = hash(x0, y0, seed), b = hash(x0 + 1, y0, seed), c = hash(x0, y0 + 1, seed), d = hash(x0 + 1, y0 + 1, seed);
		return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
	}

	private static float hash(int x, int y, int seed) {
		int h = x * 374761393 + y * 668265263 + seed * 2147483647;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
	}

	public static int mixColor(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return r << 16 | g << 8 | bl;
	}

	private static int add(int a, int b, float t) {
		int r = Math.min(255, ((a >> 16) & 0xFF) + (int) (((b >> 16) & 0xFF) * t));
		int g = Math.min(255, ((a >> 8) & 0xFF) + (int) (((b >> 8) & 0xFF) * t));
		int bl = Math.min(255, (a & 0xFF) + (int) ((b & 0xFF) * t));
		return r << 16 | g << 8 | bl;
	}
}
