package dev.femboyware.module.larp;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.util.FxRender;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The Support Desk emote's desk, for anyone doing it: a white standing desk where you stand, two
 * monitors (the support dashboard and the ticket queue), a keyboard and mouse, a mug and a nameplate.
 */
public final class SupportDesk {
	private static final int WHITE = 0xFFE9EAEC, STEEL = 0xFF3A3D44, BLACK = 0xFF0E0F12, BLUE = 0xFF0052FF, PAPER = 0xFFF5F7FA,
		LINE = 0xFFC9CED6, DARK = 0xFF15171C, ROW = 0xFF22252C;

	private SupportDesk() {
	}

	/** The desk in front of {@code at}, {@code yaw} the way you face, its screens ticking along with {@code seconds}. */
	public static void draw(Render3DEvent event, Vec3 at, float yaw, float seconds) {
		Vec3 cam = event.camera().pos;
		if (cam.distanceToSqr(at) > 80 * 80) return;
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw + 180));
		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn,
			(float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
		// The desk: its +z edge faces you
		float top = 0.8f;
		b.box(-0.8f, top - 0.04f, -0.36f, 0.8f, top, 0.3f, WHITE);
		for (float lx : new float[]{-0.7f, 0.7f}) {
			b.box(lx - 0.03f, 0, -0.03f - 0.02f, lx + 0.03f, top - 0.04f, 0.03f - 0.02f, STEEL);
			b.box(lx - 0.035f, 0, -0.3f, lx + 0.035f, 0.03f, 0.26f, STEEL);
		}
		b.box(-0.7f, top - 0.1f, -0.06f, 0.7f, top - 0.04f, 0.02f, STEEL);
		// Keyboard, keys in rows, and the mouse
		float k = top;
		b.box(-0.21f, k, 0.04f, 0.21f, k + 0.014f, 0.19f, 0xFF2A2B30);
		for (int row = 0; row < 4; row++) {
			float z = 0.06f + row * 0.032f;
			for (int key = 0; key < 12; key++) {
				float x = -0.19f + key * 0.032f;
				// The key being pressed dips
				boolean down = ((int) (seconds * 11) + key * 7 + row * 3) % 17 == 0;
				b.box(x, k + 0.014f, z, x + 0.026f, k + (down ? 0.017f : 0.022f), z + 0.024f, 0xFF4A4C53);
			}
		}
		b.box(0.29f, k, 0.07f, 0.35f, k + 0.022f, 0.16f, 0xFF2A2B30);
		// The mug, and the nameplate with the logo
		b.box(0.52f, k, -0.02f, 0.6f, k + 0.1f, 0.06f, PAPER);
		b.box(0.53f, k + 0.04f, -0.021f, 0.59f, k + 0.07f, -0.02f, BLUE);
		b.box(-0.62f, k, 0.12f, -0.38f, k + 0.06f, 0.16f, BLUE);
		b.box(-0.52f, k + 0.015f, 0.161f, -0.48f, k + 0.045f, 0.163f, PAPER);
		// Two monitors on stands
		for (int m = 0; m < 2; m++) {
			float cx = m == 0 ? -0.33f : 0.33f, bottom = top + 0.17f, w = 0.31f, h = 0.36f, z = -0.22f;
			b.box(cx - 0.12f, k, z - 0.12f, cx + 0.12f, k + 0.012f, z + 0.02f, STEEL);
			b.box(cx - 0.025f, k, z - 0.07f, cx + 0.025f, bottom + 0.05f, z - 0.04f, STEEL);
			b.box(cx - w, bottom, z - 0.04f, cx + w, bottom + h, z, BLACK);
			float sx1 = cx - w + 0.015f, sx2 = cx + w - 0.015f, sy1 = bottom + 0.015f, sy2 = bottom + h - 0.015f, f = z + 0.001f;
			if (m == 0) dashboard(b, sx1, sy1, sx2, sy2, f, seconds);
			else queue(b, sx1, sy1, sx2, sy2, f, seconds);
		}
		// A sticky note on the right monitor's bezel
		b.box(0.6f, top + 0.42f, -0.218f, 0.64f, top + 0.46f, -0.216f, 0xFFF6D44C);
	}

	/** A face flat on the screen at depth {@code f}, from (x1, y1) to (x2, y2). */
	private static void panel(ShapeBuilder b, float x1, float y1, float x2, float y2, float f, int color) {
		b.box(x1, y1, f, x2, y2, f + 0.001f, color);
	}

	/** The left screen: the support dashboard, blue bar over white, with a list and a big blue button. */
	private static void dashboard(ShapeBuilder b, float x1, float y1, float x2, float y2, float f, float seconds) {
		panel(b, x1, y1, x2, y2, f, PAPER);
		panel(b, x1, y2 - 0.045f, x2, y2, f + 0.001f, BLUE);
		panel(b, x1 + 0.012f, y2 - 0.034f, x1 + 0.034f, y2 - 0.012f, f + 0.002f, PAPER);
		// A side bar and its items
		panel(b, x1, y1, x1 + 0.12f, y2 - 0.045f, f + 0.001f, 0xFFE4E8EF);
		for (int i = 0; i < 6; i++) {
			float y = y2 - 0.075f - i * 0.04f;
			panel(b, x1 + 0.015f, y, x1 + 0.1f, y + 0.012f, f + 0.002f, i == 1 ? BLUE : LINE);
		}
		// Lines of a conversation, one typing out
		float type = (seconds * 0.6f) % 1;
		for (int i = 0; i < 5; i++) {
			float y = y2 - 0.08f - i * 0.045f, len = i == 4 ? 0.3f * type : 0.18f + ((i * 37) % 11) / 100f;
			boolean mine = i % 2 == 1;
			float lx = mine ? x2 - 0.02f - len : x1 + 0.14f;
			panel(b, lx, y, lx + len, y + 0.022f, f + 0.002f, mine ? 0xFFD6E2FF : 0xFFEDEFF3);
		}
		panel(b, x2 - 0.13f, y1 + 0.015f, x2 - 0.02f, y1 + 0.045f, f + 0.002f, BLUE);
	}

	/** The right screen: the ticket queue in dark mode, rows with status dots and a little bar chart. */
	private static void queue(ShapeBuilder b, float x1, float y1, float x2, float y2, float f, float seconds) {
		panel(b, x1, y1, x2, y2, f, DARK);
		int[] status = {0xFF27AD75, 0xFFF6B73C, 0xFF27AD75, 0xFFF0616D, 0xFF27AD75, 0xFF27AD75};
		int shift = (int) (seconds / 2.5f);
		for (int i = 0; i < 6; i++) {
			float y = y2 - 0.04f - i * 0.036f;
			panel(b, x1 + 0.012f, y, x2 - 0.012f, y + 0.028f, f + 0.001f, ROW);
			panel(b, x1 + 0.02f, y + 0.008f, x1 + 0.032f, y + 0.02f, f + 0.002f, status[(i + shift) % status.length]);
			panel(b, x1 + 0.045f, y + 0.01f, x1 + 0.2f + ((i * 13) % 7) / 100f, y + 0.018f, f + 0.002f, 0xFF5B616E);
		}
		for (int i = 0; i < 9; i++) {
			float h = 0.02f + 0.06f * (0.5f + 0.5f * (float) Math.sin(i * 1.3f + seconds * 0.4f));
			float x = x1 + 0.03f + i * 0.06f;
			panel(b, x, y1 + 0.015f, x + 0.035f, y1 + 0.015f + h, f + 0.001f, BLUE);
		}
	}
}
