package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.util.FxRender;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Builds solid shaded shapes in a model's own space (x right, y up, z forward) and draws them turned
 * by any rotation at a camera-relative spot: boxes, side-on outlines made solid, flat plates like
 * wings, and lofts (round tubes whose radius changes along their length) like a fuselage.
 */
final class ShapeBuilder {
	private final VertexConsumer c;
	private final PoseStack.Pose pose;
	private final Quaternionf rotation;
	private final float ox, oy, oz;
	private final Vector3f scratch = new Vector3f();
	private boolean soft;

	ShapeBuilder(VertexConsumer c, PoseStack.Pose pose, Quaternionf rotation, float ox, float oy, float oz) {
		this.c = c;
		this.pose = pose;
		this.rotation = rotation;
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
	}

	/** Evenly lit, for interiors: faces only darken a touch with their angle. */
	ShapeBuilder soft() {
		soft = true;
		return this;
	}

	static int shade(int color, float by) {
		int r = (int) (((color >> 16) & 0xFF) * by), g = (int) (((color >> 8) & 0xFF) * by), b = (int) ((color & 0xFF) * by);
		return (color & 0xFF000000) | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
	}

	/**
	 * How bright a face is, from where its normal points in model space: lit from above and a little
	 * in front, getting smoothly darker the more it faces down, so round shapes shade evenly.
	 */
	private static float light(float nx, float ny, float nz) {
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length == 0) return 0.8f;
		ny /= length;
		nz /= length;
		nx /= length;
		return Math.max(0.5f, 0.76f + 0.24f * ny + 0.05f * nz - 0.03f * Math.abs(nx));
	}

	/** Vertices held back to draw later (x, y, z, colour), when built without a consumer. */
	private final java.util.List<float[]> held = new java.util.ArrayList<>();

	private void vertex(float x, float y, float z, int color) {
		scratch.set(x, y, z).rotate(rotation);
		if (c == null) held.add(new float[]{ox + scratch.x, oy + scratch.y, oz + scratch.z, Float.intBitsToFloat(color)});
		else FxRender.vertex(c, pose, ox + scratch.x, oy + scratch.y, oz + scratch.z, color);
	}

	/**
	 * Draws everything held back into {@code into}. Buffers for different layers can't be written
	 * to in turns, so see-through parts are built aside and drawn once the solid ones are done.
	 */
	void flush(VertexConsumer into) {
		for (float[] v : held) FxRender.vertex(into, pose, v[0], v[1], v[2], Float.floatToRawIntBits(v[3]));
		held.clear();
	}

	/** A quad, shaded from its own facing. */
	void quad(float[] a, float[] b, float[] d, float[] e, int color) {
		float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = d[0] - a[0], vy = d[1] - a[1], vz = d[2] - a[2];
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		if (soft) {
			float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			float facing = length == 0 ? 1 : Math.abs(ny) / length;
			int even = shade(color, 0.86f + 0.14f * facing);
			vertex(a[0], a[1], a[2], even);
			vertex(b[0], b[1], b[2], even);
			vertex(d[0], d[1], d[2], even);
			vertex(e[0], e[1], e[2], even);
			return;
		}
		int shaded = shade(color, light(nx, ny, nz));
		vertex(a[0], a[1], a[2], shaded);
		vertex(b[0], b[1], b[2], shaded);
		vertex(d[0], d[1], d[2], shaded);
		vertex(e[0], e[1], e[2], shaded);
	}

	/** An unshaded quad, for glass and lights that shouldn't darken. */
	void flat(float[] a, float[] b, float[] d, float[] e, int color) {
		vertex(a[0], a[1], a[2], color);
		vertex(b[0], b[1], b[2], color);
		vertex(d[0], d[1], d[2], color);
		vertex(e[0], e[1], e[2], color);
	}

	void box(float x1, float y1, float z1, float x2, float y2, float z2, int color) {
		quad(new float[]{x1, y2, z1}, new float[]{x1, y2, z2}, new float[]{x2, y2, z2}, new float[]{x2, y2, z1}, color);
		quad(new float[]{x1, y1, z1}, new float[]{x2, y1, z1}, new float[]{x2, y1, z2}, new float[]{x1, y1, z2}, color);
		quad(new float[]{x1, y1, z1}, new float[]{x1, y1, z2}, new float[]{x1, y2, z2}, new float[]{x1, y2, z1}, color);
		quad(new float[]{x2, y1, z2}, new float[]{x2, y1, z1}, new float[]{x2, y2, z1}, new float[]{x2, y2, z2}, color);
		quad(new float[]{x1, y1, z2}, new float[]{x2, y1, z2}, new float[]{x2, y2, z2}, new float[]{x1, y2, z2}, color);
		quad(new float[]{x2, y1, z1}, new float[]{x1, y1, z1}, new float[]{x1, y2, z1}, new float[]{x2, y2, z1}, color);
	}

	/**
	 * A flat plate like a wing or fin: an outline of corners going round, each (x, y, z), and the
	 * plate made {@code thickness} thick along {@code (tx, ty, tz)}.
	 */
	void plate(float[][] outline, float tx, float ty, float tz, float thickness, int color) {
		int n = outline.length;
		float hx = tx * thickness / 2, hy = ty * thickness / 2, hz = tz * thickness / 2;
		// Which way round the outline goes, so each face is shaded by the side it faces out of (mirrored outlines go the other way)
		float[] o0 = outline[0], o1 = outline[1], o2 = outline[2];
		float ux = o1[0] - o0[0], uy = o1[1] - o0[1], uz = o1[2] - o0[2], vx = o2[0] - o0[0], vy = o2[1] - o0[1], vz = o2[2] - o0[2];
		boolean along = (uy * vz - uz * vy) * tx + (uz * vx - ux * vz) * ty + (ux * vy - uy * vx) * tz >= 0;
		for (int i = 1; i < n - 1; i++) {
			float[] a = outline[0], p = along ? outline[i] : outline[i + 1], q = along ? outline[i + 1] : outline[i];
			quad(new float[]{a[0] + hx, a[1] + hy, a[2] + hz}, new float[]{p[0] + hx, p[1] + hy, p[2] + hz}, new float[]{q[0] + hx, q[1] + hy, q[2] + hz}, new float[]{q[0] + hx, q[1] + hy, q[2] + hz}, color);
			quad(new float[]{a[0] - hx, a[1] - hy, a[2] - hz}, new float[]{q[0] - hx, q[1] - hy, q[2] - hz}, new float[]{p[0] - hx, p[1] - hy, p[2] - hz}, new float[]{p[0] - hx, p[1] - hy, p[2] - hz}, color);
		}
		for (int i = 0; i < n; i++) {
			float[] p = outline[i], q = outline[(i + 1) % n];
			quad(new float[]{p[0] - hx, p[1] - hy, p[2] - hz}, new float[]{q[0] - hx, q[1] - hy, q[2] - hz}, new float[]{q[0] + hx, q[1] + hy, q[2] + hz}, new float[]{p[0] + hx, p[1] + hy, p[2] + hz}, shade(color, 0.85f));
		}
	}

	/** One ring of a loft: where it is along z, its radius, its centre's height and sideways offset. */
	record Station(float z, float radius, float y, float x) {
		Station(float z, float radius, float y) {
			this(z, radius, y, 0);
		}
	}

	/** A round tube through the stations, {@code sides} faces round, in one colour. */
	void loft(Station[] stations, int sides, int color) {
		loft(stations, sides, color, -1, 0, 0);
	}

	/**
	 * A round tube through the stations. Faces whose angle round the tube (0 at the top, going
	 * round the right side) falls between {@code bandFrom} and {@code bandTo} radians get
	 * {@code bandColor}, for a stripe.
	 */
	void loft(Station[] stations, int sides, int color, float bandFrom, float bandTo, int bandColor) {
		loft(stations, sides, color, bandFrom, bandTo, bandColor, null);
	}

	/** Which faces of a loft to leave open, by segment and angle round (wrapped to -pi..pi): windows to see out of. */
	interface Opening {
		boolean open(int segment, double angle);
	}

	/** A colour for one face of a loft, by segment and angle round, or 0 to leave it as it was. */
	interface FaceColor {
		int color(int segment, double angle);
	}

	void loft(Station[] stations, int sides, int color, float bandFrom, float bandTo, int bandColor, Opening opening) {
		loft(stations, sides, color, bandFrom, bandTo, bandColor, opening, null);
	}

	void loft(Station[] stations, int sides, int color, float bandFrom, float bandTo, int bandColor, Opening opening, FaceColor paint) {
		for (int s = 0; s < stations.length - 1; s++) {
			Station a = stations[s], b = stations[s + 1];
			for (int i = 0; i < sides; i++) {
				double t1 = Math.PI * 2 * i / sides, t2 = Math.PI * 2 * (i + 1) / sides, mid = (t1 + t2) / 2;
				double wrapped = mid > Math.PI ? mid - Math.PI * 2 : mid;
				if (opening != null && opening.open(s, wrapped)) continue;
				boolean band = bandFrom >= -Math.PI && (Math.abs(wrapped) >= bandFrom && Math.abs(wrapped) <= bandTo);
				int col = band ? bandColor : color;
				if (paint != null) {
					int painted = paint.color(s, wrapped);
					if (painted != 0) col = painted;
				}
				float[] p1 = ring(a, t1), p2 = ring(b, t1), p3 = ring(b, t2), p4 = ring(a, t2);
				// Shade by the side facing out from the tube's middle, whichever way round the corners go
				float ux = p2[0] - p1[0], uy = p2[1] - p1[1], uz = p2[2] - p1[2], vx = p3[0] - p1[0], vy = p3[1] - p1[1], vz = p3[2] - p1[2];
				float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz;
				float outX = (float) Math.sin(mid), outY = (float) Math.cos(mid);
				if (nx * outX + ny * outY >= 0) quad(p1, p2, p3, p4, col);
				else quad(p1, p4, p3, p2, col);
			}
		}
	}

	/** A point on a station's ring, {@code angle} round from the top (clockwise seen from behind). */
	static float[] ring(Station s, double angle) {
		return new float[]{s.x + (float) Math.sin(angle) * s.radius, s.y + (float) Math.cos(angle) * s.radius, s.z};
	}

	/** A patch sitting just proud of a loft's surface, between two angles and two stations: windows, doors. */
	void patch(Station a, Station b, double from, double to, float lift, int color) {
		Station la = new Station(a.z, a.radius + lift, a.y, a.x), lb = new Station(b.z, b.radius + lift, b.y, b.x);
		flat(ring(la, from), ring(lb, from), ring(lb, to), ring(la, to), color);
	}

	/** A station partway between two, for placing things on the tube. */
	static Station between(Station a, Station b, float along) {
		return new Station(a.z + (b.z - a.z) * along, a.radius + (b.radius - a.radius) * along, a.y + (b.y - a.y) * along, a.x + (b.x - a.x) * along);
	}
}
