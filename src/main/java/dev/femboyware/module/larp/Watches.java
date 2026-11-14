package dev.femboyware.module.larp;

import java.util.List;

/**
 * Rolexes and Audemars Piguets, modelled small: a round case with a coloured or fluted bezel for a
 * Rolex, the octagonal bezel with its eight screws for a Royal Oak; the dial, hands and bracelet.
 * Drawn in any plane (lying on a cushion, or standing in a winder), for the mansion's watch room
 * and anywhere else a watch shows.
 */
public final class Watches {
	public enum Shape { ROUND, OCTAGON }

	/** One model: its name, the metal, dial and bezel colours (two for a two-tone bezel), its shape, and whether it's iced out. */
	public record Style(String name, int metal, int dial, int bezel, int bezel2, Shape shape, boolean iced, long price) {
		public String brand() {
			return shape == Shape.OCTAGON ? "Audemars Piguet" : "Rolex";
		}
	}

	private static final int STEEL = 0xFFD4D8DE, GOLD = 0xFFE3BE5C, ROSE = 0xFFE3A58C, WHITE_GOLD = 0xFFE9ECEF, PLATINUM = 0xFFE6EAEE;

	/** The collection, in the order the display cases fill. */
	public static final List<Style> COLLECTION = List.of(
		new Style("Submariner \"Hulk\"", STEEL, 0xFF1E7A3A, 0xFF1E7A3A, 0, Shape.ROUND, false, 26_500),
		new Style("GMT-Master II \"Pepsi\"", WHITE_GOLD, 0xFF101114, 0xFFC0262D, 0xFF1F3F9A, Shape.ROUND, false, 32_000),
		new Style("Royal Oak 15500ST", STEEL, 0xFF1E3B70, STEEL, 0, Shape.OCTAGON, false, 68_000),
		new Style("Daytona \"Panda\"", STEEL, 0xFFF3F1EA, 0xFF111214, 0, Shape.ROUND, false, 41_000),
		new Style("Day-Date 40 \"President\"", GOLD, 0xFFE8D6A4, GOLD, 0, Shape.ROUND, false, 52_000),
		new Style("Royal Oak Offshore", ROSE, 0xFF1A1A1C, ROSE, 0, Shape.OCTAGON, false, 64_000),
		new Style("Submariner \"Kermit\"", STEEL, 0xFF101114, 0xFF2F9E44, 0, Shape.ROUND, false, 21_000),
		new Style("Daytona Iced Out", GOLD, 0xFF121214, 0xFFF4F7FF, 0, Shape.ROUND, true, 185_000),
		new Style("Royal Oak Rose Gold", ROSE, 0xFF5E6A78, ROSE, 0, Shape.OCTAGON, false, 112_000),
		new Style("GMT-Master II \"Batman\"", STEEL, 0xFF101114, 0xFF101114, 0xFF1F3F9A, Shape.ROUND, false, 23_500),
		new Style("Day-Date Platinum \"Ice Blue\"", PLATINUM, 0xFFBFE3F2, PLATINUM, 0, Shape.ROUND, false, 89_000),
		new Style("Royal Oak Iced Out", WHITE_GOLD, 0xFF16181C, 0xFFF4F7FF, 0, Shape.OCTAGON, true, 420_000),
		new Style("Yacht-Master Everose", ROSE, 0xFF101114, 0xFF2A2B2E, 0, Shape.ROUND, false, 38_000),
		new Style("Royal Oak Jumbo Gold", GOLD, 0xFF2E3446, GOLD, 0, Shape.OCTAGON, false, 145_000),
		new Style("Sky-Dweller Blue", STEEL, 0xFF1E3B70, STEEL, 0, Shape.ROUND, false, 30_000),
		new Style("Day-Date Rainbow", ROSE, 0xFF15161A, 0xFFE85D75, 0xFF4BA3C3, Shape.ROUND, true, 650_000));

	private Watches() {
	}

	/** A point {@code a} right and {@code b} up from {@code c}, lifted {@code n} along the face's normal. */
	private static float[] at(float[] c, float[] right, float[] up, float[] normal, double a, double b, float n) {
		return new float[]{(float) (c[0] + right[0] * a + up[0] * b + normal[0] * n), (float) (c[1] + right[1] * a + up[1] * b + normal[1] * n),
			(float) (c[2] + right[2] * a + up[2] * b + normal[2] * n)};
	}

	private static float[][] polygon(float[] c, float[] right, float[] up, float[] normal, float radius, int sides, double turn, float lift) {
		float[][] out = new float[sides][];
		for (int i = 0; i < sides; i++) {
			double a = turn + Math.PI * 2 * i / sides;
			out[i] = at(c, right, up, normal, Math.cos(a) * radius, Math.sin(a) * radius, lift);
		}
		return out;
	}

	private static float[] cross(float[] a, float[] b) {
		return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	/** A flat polygon facing out along the normal, one-sided (a fan of quads), for faces that sit on something. */
	private static void disc(ShapeBuilder b, float[][] ring, float[] c, int color) {
		int n = ring.length;
		for (int i = 0; i < n; i += 2) b.quad(c, ring[i], ring[(i + 1) % n], ring[(i + 2) % n], color);
	}

	/** A flat quad between four points, one-sided. */
	private static void flatQuad(ShapeBuilder b, float[] p, float[] q, float[] r, float[] s, int color) {
		b.quad(p, q, r, s, color);
	}

	/**
	 * One watch with its dial facing out of the plane of {@code right} and {@code up} (12 o'clock is
	 * up), its case {@code size} across, the bracelet running up and down from it. Kept light: about
	 * sixty quads, since a room holds dozens.
	 */
	static void draw(ShapeBuilder b, float[] center, float[] right, float[] up, float size, Style style, float minutes) {
		float[] n = cross(right, up);
		float r = size / 2, t = size * 0.22f;
		boolean oct = style.shape == Shape.OCTAGON;
		int sides = oct ? 8 : 12;
		double turn = oct ? Math.PI / 8 : 0;
		// The bracelet: a strap up and one down from the case, its polished centre links on top
		float band = oct ? r * 1.25f : r * 1.0f, length = size * 1.15f;
		for (int dir = -1; dir <= 1; dir += 2) {
			float from = r * 0.7f * dir, to = (r * 0.7f + length) * dir;
			flatQuad(b, at(center, right, up, n, -band / 2, from, t * 0.35f), at(center, right, up, n, band / 2, from, t * 0.35f),
				at(center, right, up, n, band / 2 * 0.85f, to, t * 0.35f), at(center, right, up, n, -band / 2 * 0.85f, to, t * 0.35f), style.metal);
			flatQuad(b, at(center, right, up, n, -band / 6, from, t * 0.37f), at(center, right, up, n, band / 6, from, t * 0.37f),
				at(center, right, up, n, band / 6, to, t * 0.37f), at(center, right, up, n, -band / 6, to, t * 0.37f), ShapeBuilder.shade(style.metal, 1.12f));
		}
		// The case: a solid plate, so it reads from the side too
		b.plate(polygon(center, right, up, n, r, sides, turn, t / 2), n[0], n[1], n[2], t, style.metal);
		// The bezel: one colour, two halves, or iced
		float bz = t + size * 0.01f;
		float[] top = at(center, right, up, n, 0, 0, bz);
		if (style.bezel2 != 0) {
			float[][] ring = polygon(center, right, up, n, r * 0.97f, sides, turn, bz);
			for (int i = 0; i < sides; i += 2) b.quad(top, ring[i], ring[(i + 1) % sides], ring[(i + 2) % sides], i < sides / 2 ? style.bezel : style.bezel2);
		} else {
			disc(b, polygon(center, right, up, n, r * 0.97f, sides, turn, bz), top, style.bezel);
		}
		if (oct) {
			// The Royal Oak's eight screws
			for (int i = 0; i < 8; i++) {
				double a = Math.PI / 8 + Math.PI * 2 * i / 8;
				float sx = (float) Math.cos(a) * r * 0.86f, sy = (float) Math.sin(a) * r * 0.86f, d = size * 0.03f, lift = bz + size * 0.004f;
				flatQuad(b, at(center, right, up, n, sx - d, sy - d, lift), at(center, right, up, n, sx + d, sy - d, lift),
					at(center, right, up, n, sx + d, sy + d, lift), at(center, right, up, n, sx - d, sy + d, lift), 0xFFF2F3F5);
			}
		}
		// The dial, four markers and the hands
		float dz = bz + size * 0.008f, dial = r * (oct ? 0.72f : 0.74f);
		disc(b, polygon(center, right, up, n, dial, sides, turn, dz), at(center, right, up, n, 0, 0, dz), style.dial);
		boolean light = ((style.dial >> 16 & 0xFF) + (style.dial >> 8 & 0xFF) + (style.dial & 0xFF)) > 420;
		int marks = light ? 0xFF1A1B1E : 0xFFF2F2EE;
		for (int i = 0; i < 4; i++) {
			double a = Math.PI / 2 - Math.PI / 2 * i;
			float cx = (float) Math.cos(a) * dial * 0.8f, cy = (float) Math.sin(a) * dial * 0.8f, w = dial * 0.07f, h = dial * 0.13f;
			float ax = (float) Math.cos(a), ay = (float) Math.sin(a), px = -ay, py = ax, lift = dz + size * 0.004f;
			flatQuad(b, at(center, right, up, n, cx - px * w - ax * h, cy - py * w - ay * h, lift), at(center, right, up, n, cx + px * w - ax * h, cy + py * w - ay * h, lift),
				at(center, right, up, n, cx + px * w + ax * h, cy + py * w + ay * h, lift), at(center, right, up, n, cx - px * w + ax * h, cy - py * w + ay * h, lift), i == 0 ? 0xFFE3BE5C : marks);
		}
		hand(b, center, right, up, n, Math.PI / 2 - Math.PI * 2 * (minutes / 60f), dial * 0.85f, size * 0.03f, dz + size * 0.008f, marks);
		hand(b, center, right, up, n, Math.PI / 2 - Math.PI * 2 * (minutes / 720f), dial * 0.55f, size * 0.045f, dz + size * 0.012f, marks);
	}

	private static void hand(ShapeBuilder b, float[] c, float[] right, float[] up, float[] n, double angle, float length, float width, float lift, int color) {
		double ca = Math.cos(angle), sa = Math.sin(angle), px = -sa, py = ca;
		flatQuad(b, at(c, right, up, n, px * width, py * width, lift), at(c, right, up, n, ca * length + px * width * 0.4, sa * length + py * width * 0.4, lift),
			at(c, right, up, n, ca * length - px * width * 0.4, sa * length - py * width * 0.4, lift), at(c, right, up, n, -px * width, -py * width, lift), color);
	}

	/** Where an iced watch's diamonds catch the light: points round its bezel, for little twinkles. */
	static float[][] sparkles(float[] center, float[] right, float[] up, float size, Style style) {
		if (!style.iced) return new float[0][];
		float[] n = cross(right, up);
		float[][] out = new float[6][];
		for (int i = 0; i < 6; i++) {
			double a = Math.PI * 2 * i / 6 + 0.4;
			out[i] = at(center, right, up, n, Math.cos(a) * size * 0.47f, Math.sin(a) * size * 0.47f, size * 0.3f);
		}
		return out;
	}
}
