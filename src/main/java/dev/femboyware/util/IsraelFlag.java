package dev.femboyware.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** The Star of David and the Israeli flag, shared by the effects that draw them. */
public final class IsraelFlag {
	public static final int BLUE = 0xFF0038B8;
	public static final int LIGHT_BLUE = 0xFF3D7BFF;

	/** Top to bottom: white, blue stripe, white (with the star), blue stripe, white. */
	private static final float[] BANDS = {0, 0.1f, 0.25f, 0.75f, 0.9f, 1};

	private IsraelFlag() {
	}

	/** The three points of one of the star's triangles, outer radius 1: pointing up, or down. */
	public static float[][] triangle(boolean down) {
		float[][] p = new float[3][2];
		for (int i = 0; i < 3; i++) {
			double a = Math.PI / 2 + (down ? Math.PI : 0) + Math.PI * 2 * i / 3;
			p[i][0] = (float) Math.cos(a);
			p[i][1] = (float) Math.sin(a);
		}
		return p;
	}

	/** A Star of David outline in the XY plane: two interlaced triangles of radius {@code r}. */
	public static void starXY(VertexConsumer c, PoseStack.Pose pose, float cx, float cy, float r, float width, int color) {
		for (int tri = 0; tri < 2; tri++) {
			float[][] p = triangle(tri == 1);
			for (int i = 0; i < 3; i++) {
				FxRender.strokeXY(c, pose, cx + p[i][0] * r, cy + p[i][1] * r, cx + p[(i + 1) % 3][0] * r, cy + p[(i + 1) % 3][1] * r, width, color, color);
			}
		}
	}

	/** The same outline lying flat in the XZ plane, at height {@code y}. */
	public static void starXZ(VertexConsumer c, PoseStack.Pose pose, float r, float y, float width, int color) {
		for (int tri = 0; tri < 2; tri++) {
			float[][] p = triangle(tri == 1);
			for (int i = 0; i < 3; i++) {
				FxRender.strokeXZ(c, pose, p[i][0] * r, p[i][1] * r, p[(i + 1) % 3][0] * r, p[(i + 1) % 3][1] * r, y, width, color, color);
			}
		}
	}

	/** How far the cloth sways out at {@code u} along it: nothing at the pole, most at the free end. */
	public static float wave(float u, float t) {
		return (float) Math.sin(u * 6 - t * 6) * 0.1f * u;
	}

	/**
	 * A waving flag in the XY plane, hung from {@code x0} and {@code fw} wide, its bottom edge at
	 * {@code bottom}. Meant for the solid buffer so the star sits properly on the cloth; the star's
	 * drawn on both sides. {@code sway} scales how far it ripples out.
	 */
	public static void cloth(VertexConsumer c, PoseStack.Pose pose, float x0, float bottom, float fw, float fh, float t, float sway) {
		int columns = 20;
		for (int i = 0; i < columns; i++) {
			float u1 = i / (float) columns, u2 = (i + 1) / (float) columns;
			float x1 = x0 + fw * u1, x2 = x0 + fw * u2;
			float z1 = wave(u1, t) * sway, z2 = wave(u2, t) * sway;
			float shade1 = 0.85f + 0.15f * (float) Math.cos(t * 6 - u1 * 7), shade2 = 0.85f + 0.15f * (float) Math.cos(t * 6 - u2 * 7);
			for (int b = 0; b < 5; b++) {
				int color = b == 1 || b == 3 ? BLUE : 0xFFFFFFFF;
				int c1 = FxRender.darken(color, 1 - shade1), c2 = FxRender.darken(color, 1 - shade2);
				float yb = bottom + fh * (1 - BANDS[b + 1]), yt = bottom + fh * (1 - BANDS[b]);
				FxRender.quad(c, pose, x1, yb, z1, c1, x2, yb, z2, c2, x2, yt, z2, c2, x1, yt, z1, c1);
			}
		}
		// The star in the middle, in short pieces so each one sits on the cloth where it bends
		float cx = x0 + fw / 2, cy = bottom + fh / 2, r = fh * 0.2f, width = fh * 0.032f, lift = Math.max(0.002f, fh * 0.006f);
		for (int tri = 0; tri < 2; tri++) {
			float[][] p = triangle(tri == 1);
			for (int i = 0; i < 3; i++) {
				float sx = cx + p[i][0] * r, sy = cy + p[i][1] * r, ex = cx + p[(i + 1) % 3][0] * r, ey = cy + p[(i + 1) % 3][1] * r;
				for (int k = 0; k < 8; k++) {
					float ax = sx + (ex - sx) * k / 8f, ay = sy + (ey - sy) * k / 8f;
					float bx = sx + (ex - sx) * (k + 1) / 8f, by = sy + (ey - sy) * (k + 1) / 8f;
					float az = wave((ax - x0) / fw, t) * sway, bz = wave((bx - x0) / fw, t) * sway;
					for (int side = -1; side <= 1; side += 2) stroke(c, pose, ax, ay, az + side * lift, bx, by, bz + side * lift, width, BLUE);
				}
			}
		}
	}

	/** A pole in the XY plane from the origin up, as two crossed strips. */
	public static void pole(VertexConsumer c, PoseStack.Pose pose, float height, float width) {
		int light = 0xFFDADDE3, dark = 0xFF9AA0AA;
		FxRender.quad(c, pose, -width, 0, 0, dark, width, 0, 0, light, width, height, 0, light, -width, height, 0, dark);
		FxRender.quad(c, pose, 0, 0, -width, dark, 0, 0, width, light, 0, height, width, light, 0, height, -width, dark);
	}

	/** A thin strip between two points, flat in the XY plane but following their depths. */
	private static void stroke(VertexConsumer c, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz, float width, int color) {
		float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 1e-5f) return;
		float nx = -dy / len * width / 2, ny = dx / len * width / 2;
		FxRender.quad(c, pose, ax - nx, ay - ny, az, color, bx - nx, by - ny, bz, color, bx + nx, by + ny, bz, color, ax + nx, ay + ny, az, color);
	}
}
