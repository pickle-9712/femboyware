package dev.femboyware.module.larp;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Day and night for the larp props: their colours dim with the light where they stand (the sky's,
 * dark at night, or nearby torches), interiors stay softly lit as if their lamps were on, and their
 * own lights and windows glow brighter once it's dark.
 */
public final class LarpLight {
	private static Vec3 cam = Vec3.ZERO;
	/** Darkest a prop gets, and how bright interiors stay at night. */
	private static final float FLOOR = 0.22f, INSIDE = 0.72f;

	private LarpLight() {
	}

	/** Called each frame before the props draw, with where the camera is. */
	public static void frame(Vec3 camera) {
		cam = camera;
	}

	public static Vec3 camera() {
		return cam;
	}

	/** How lit a point in the world is, from the darkest a prop gets up to 1 in daylight. */
	public static float at(double x, double y, double z) {
		var level = Minecraft.getInstance().level;
		if (level == null) return 1;
		int raw = level.getMaxLocalRawBrightness(BlockPos.containing(x, y, z));
		return FLOOR + (1 - FLOOR) * raw / 15f;
	}

	/** The same, for something drawn {@code ox, oy, oz} from the camera; a little above it, so it's in the open air. */
	public static float relative(float ox, float oy, float oz) {
		return at(cam.x + ox, cam.y + oy + 1.5, cam.z + oz);
	}

	/** Interiors keep their lamps on. */
	public static float inside(float light) {
		return Math.max(light, INSIDE);
	}

	/** How dark it is: 0 in the day, 1 at night. */
	public static float night() {
		var level = Minecraft.getInstance().level;
		return level == null ? 0 : Math.min(1, level.getSkyDarken() / 11f);
	}

	/** A colour dimmed by {@code light}, keeping its alpha. */
	public static int shade(int color, float light) {
		if (light >= 0.999f) return color;
		int r = (int) (((color >> 16) & 0xFF) * light), g = (int) (((color >> 8) & 0xFF) * light), b = (int) ((color & 0xFF) * light);
		return (color & 0xFF000000) | r << 16 | g << 8 | b;
	}

	/** A prop's light glowing: softer in daylight, full at night. */
	public static int glow(int color) {
		float strength = 0.4f + 0.6f * night();
		int alpha = Math.round(((color >>> 24) & 0xFF) * strength);
		return (alpha << 24) | (color & 0xFFFFFF);
	}

	/** A window: tinted glass by day, a warm glow from inside at night. */
	public static int window(int day) {
		float t = night();
		if (t <= 0.01f) return day;
		int warm = 0xB0FFD39A;
		int a = Math.round(((day >>> 24) & 0xFF) * (1 - t) + ((warm >>> 24) & 0xFF) * t);
		int r = Math.round(((day >> 16) & 0xFF) * (1 - t) + ((warm >> 16) & 0xFF) * t);
		int g = Math.round(((day >> 8) & 0xFF) * (1 - t) + ((warm >> 8) & 0xFF) * t);
		int b = Math.round((day & 0xFF) * (1 - t) + (warm & 0xFF) * t);
		return a << 24 | r << 16 | g << 8 | b;
	}
}
