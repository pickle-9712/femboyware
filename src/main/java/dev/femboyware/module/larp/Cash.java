package dev.femboyware.module.larp;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.util.FxRender;
import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Cash, modelled: banded bricks of hundreds, loose bills fanned on a surface, and a cash
 * counting machine. Used for the mansion's cash room and the cash left round the house, and for the
 * Money Counter emote's table.
 */
public final class Cash {
	/** A brick of hundreds: the bills' length, width and the brick's height. */
	static final float LENGTH = 0.31f, WIDTH = 0.135f, HEIGHT = 0.07f;
	private static final int PAPER = 0xFFA9B898, EDGE = 0xFF8E9E7E, PORTRAIT = 0xFF7F9170, STRAP = 0xFFC9A14A;

	private Cash() {
	}

	/** One brick lying flat with its bottom at {@code y}, along x (or z), with its $10,000 strap round the middle. */
	static void brick(ShapeBuilder b, float x, float y, float z, boolean alongX) {
		float hl = LENGTH / 2, hw = WIDTH / 2, h = HEIGHT;
		float ax = alongX ? hl : hw, az = alongX ? hw : hl;
		b.box(x - ax, y, z - az, x + ax, y + h, z + az, EDGE);
		// The top bill: paper with the portrait panel in the middle
		b.box(x - ax + 0.004f, y + h, z - az + 0.004f, x + ax - 0.004f, y + h + 0.003f, z + az - 0.004f, PAPER);
		float px = alongX ? 0.045f : 0.035f, pz = alongX ? 0.035f : 0.045f;
		b.box(x - px, y + h + 0.003f, z - pz, x + px, y + h + 0.004f, z + pz, PORTRAIT);
		// The strap, across the short way
		float sx = alongX ? 0.022f : ax + 0.003f, sz = alongX ? az + 0.003f : 0.022f;
		b.box(x - sx, y - 0.002f, z - sz, x + sx, y + h + 0.005f, z + sz, STRAP);
	}

	/** A block of bricks, {@code across} by {@code deep} by {@code layers}, from the corner nearest -x -z, bottom at {@code y}. */
	static void stack(ShapeBuilder b, float x, float y, float z, int across, int deep, int layers, boolean alongX) {
		float sx = alongX ? LENGTH + 0.01f : WIDTH + 0.01f, sz = alongX ? WIDTH + 0.01f : LENGTH + 0.01f;
		for (int l = 0; l < layers; l++) {
			for (int i = 0; i < across; i++) {
				for (int j = 0; j < deep; j++) brick(b, x + sx * (i + 0.5f), y + l * (HEIGHT + 0.006f), z + sz * (j + 0.5f), alongX);
			}
		}
	}

	/** How tall {@code layers} of bricks stand. */
	static float height(int layers) {
		return layers * (HEIGHT + 0.006f);
	}

	/** Loose hundreds dropped on a surface at height {@code y}: {@code count} bills scattered round (x, z), each turned its own way. */
	static void loose(ShapeBuilder b, float x, float y, float z, int count, float spread, long seed) {
		Random r = new Random(seed);
		for (int i = 0; i < count; i++) {
			float cx = x + (r.nextFloat() - 0.5f) * spread, cz = z + (r.nextFloat() - 0.5f) * spread;
			bill(b, cx, y + 0.002f + i * 0.0015f, cz, r.nextFloat() * (float) Math.PI);
		}
	}

	/** One bill lying flat at height {@code y}, turned {@code angle} round the vertical. */
	static void bill(ShapeBuilder b, float x, float y, float z, float angle) {
		float c = (float) Math.cos(angle), s = (float) Math.sin(angle), l = LENGTH / 2, w = WIDTH / 2;
		float[][] k = {{-l, -w}, {-l, w}, {l, w}, {l, -w}};
		float[][] p = new float[4][];
		for (int i = 0; i < 4; i++) p[i] = new float[]{x + k[i][0] * c - k[i][1] * s, y, z + k[i][0] * s + k[i][1] * c};
		b.quad(p[0], p[1], p[2], p[3], PAPER);
		float pl = 0.045f, pw = 0.035f;
		float[][] m = {{-pl, -pw}, {-pl, pw}, {pl, pw}, {pl, -pw}};
		for (int i = 0; i < 4; i++) p[i] = new float[]{x + m[i][0] * c - m[i][1] * s, y + 0.0008f, z + m[i][0] * s + m[i][1] * c};
		b.quad(p[0], p[1], p[2], p[3], PORTRAIT);
	}

	/** A box with its z span mirrored when {@code dir} is -1, so things can face either way along z. */
	private static void box(ShapeBuilder b, float dir, float x1, float y1, float z1, float x2, float y2, float z2, float z, int color) {
		float a = z + z1 * dir, c = z + z2 * dir;
		b.box(x1, y1, Math.min(a, c), x2, y2, Math.max(a, c), color);
	}

	/**
	 * A cash counting machine on a surface at {@code y}, its front (the display and output tray)
	 * facing +z, or -z with {@code dir} -1: the grey body, the hopper on top at the back, the display.
	 * {@code running} feeds bills through from {@code seconds}, flicking from the hopper into the tray.
	 */
	static void counter(ShapeBuilder b, float x, float y, float z, float dir, boolean running, float seconds) {
		int body = 0xFF2E3036, trim = 0xFF4A4D55, screen = running ? 0xFF59F08A : 0xFF1F5A35;
		box(b, dir, x - 0.16f, y, -0.13f, x + 0.16f, y + 0.16f, 0.13f, z, body);
		box(b, dir, x - 0.165f, y, 0.1f, x + 0.165f, y + 0.03f, 0.2f, z, trim);
		// The hopper: bills standing on edge at the back
		box(b, dir, x - 0.12f, y + 0.16f, -0.13f, x + 0.12f, y + 0.24f, -0.03f, z, trim);
		box(b, dir, x - 0.08f, y + 0.16f, -0.12f, x + 0.08f, y + 0.25f, -0.06f, z, PAPER);
		// The display
		box(b, dir, x - 0.1f, y + 0.1f, 0.13f, x + 0.1f, y + 0.145f, 0.132f, z, 0xFF0B0C0F);
		box(b, dir, x - 0.08f, y + 0.108f, 0.132f, x + 0.03f, y + 0.137f, 0.134f, z, screen);
		// The output tray's pile, growing while it runs
		float pile = running ? 0.005f + 0.035f * (float) ((seconds % 6) / 6) : 0.02f;
		box(b, dir, x - 0.07f, y + 0.03f, 0.12f, x + 0.07f, y + 0.03f + pile, 0.19f, z, PAPER);
		if (!running) return;
		// Bills flicking through: each a quick arc from the top of the hopper out over the tray
		for (int i = 0; i < 4; i++) {
			float p = (float) ((seconds * 7 + i / 4f) % 1.0);
			float fz = z + dir * (-0.08f + p * 0.27f), fy = y + 0.25f - p * 0.2f + (float) Math.sin(p * Math.PI) * 0.05f;
			bill(b, x, fy, fz, (float) Math.PI / 2);
		}
	}

	/**
	 * The Money Counter emote's table, for anyone doing it: a black side table where you stand,
	 * {@code yaw} the way you face, with the machine running, cash bricks waiting and a pile counted.
	 */
	public static void drawCounterScene(Render3DEvent event, Vec3 at, float yaw, float seconds) {
		Vec3 cam = event.camera().pos;
		if (cam.distanceToSqr(at) > 80 * 80) return;
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw + 180));
		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn,
			(float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
		// The table: a black top on a steel frame, its near edge just in front of you (the table's +z faces you)
		float top = 0.82f;
		int black = 0xFF16171A, steel = 0xFF8C9098;
		b.box(-0.55f, top - 0.04f, -0.32f, 0.55f, top, 0.32f, black);
		for (float lx : new float[]{-0.5f, 0.5f}) {
			for (float lz : new float[]{-0.27f, 0.27f}) b.box(lx - 0.025f, 0, lz - 0.025f, lx + 0.025f, top - 0.04f, lz + 0.025f, steel);
		}
		counter(b, 0, top, 0.02f, 1, true, seconds);
		// Bricks waiting to go in, on your left; counted ones on your right
		stack(b, 0.22f, top, -0.25f, 1, 2, 3, false);
		stack(b, -0.42f, top, -0.2f, 1, 2, 2 + (int) Math.min(4, seconds / 6), false);
		loose(b, -0.3f, top, 0.18f, 5, 0.14f, 42);
	}
}
