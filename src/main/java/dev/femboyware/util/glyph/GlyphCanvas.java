package dev.femboyware.util.glyph;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.RenderUtil;
import java.util.Arrays;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class GlyphCanvas {
	public static final int MAX_QUADS = 12000;
	private static final int MAX_LINES = 4000;
	private static final int MIN_ALPHA = 3;
	private static final int VERTEX_BITS = 9;
	private static final double RIBBON_NEAR = 0.05;
	private double[] quadPos = new double[3072];
	private int[] quadColor = new int[256];
	private int quads;
	private double[] linePos = new double[384];
	private int[] lineColor = new int[64];
	private float[] lineWidth = new float[64];
	private int lines;
	private double camX;
	private double camY;
	private double camZ;
	private double rightX;
	private double rightY;
	private double rightZ;
	private double upX;
	private double upY;
	private double upZ;
	private double lookX;
	private double lookY;
	private double lookZ;

	public void begin(Render3DEvent event) {
		this.quads = 0;
		this.lines = 0;
		Vec3 eye = event.camera().pos;
		this.camX = eye.x;
		this.camY = eye.y;
		this.camZ = eye.z;
		Quaternionf orientation = event.camera().orientation;
		Vector3f right = new Vector3f(1, 0, 0).rotate(orientation);
		Vector3f up = new Vector3f(0, 1, 0).rotate(orientation);
		Vector3f forward = new Vector3f(0, 0, -1).rotate(orientation);
		this.rightX = right.x();
		this.rightY = right.y();
		this.rightZ = right.z();
		this.upX = up.x();
		this.upY = up.y();
		this.upZ = up.z();
		this.lookX = forward.x();
		this.lookY = forward.y();
		this.lookZ = forward.z();
	}

	/** Draws everything collected since {@link #begin}, in femboyware's world buffers. */
	public void submit(Render3DEvent event, boolean onTop) {
		PoseStack.Pose entry = event.matrices().last();
		if (this.quads > 0) {
			VertexConsumer out = FxRender.quads(event, false, onTop);
			double[] q = this.quadPos;
			for (int i = 0; i < this.quads; i++) {
				int p = i * 12, c = this.quadColor[i];
				for (int k = 0; k < 12; k += 3)
					out.addVertex(entry, (float) (q[p + k] - camX), (float) (q[p + k + 1] - camY), (float) (q[p + k + 2] - camZ)).setColor(c);
			}
		}
		this.quads = 0;
		double[] l = this.linePos;
		for (int i = 0; i < this.lines; i++) {
			int p = i * 6;
			RenderUtil.drawLine(event, new Vec3(l[p], l[p + 1], l[p + 2]), new Vec3(l[p + 3], l[p + 4], l[p + 5]), this.lineColor[i], this.lineWidth[i], onTop);
		}
		this.lines = 0;
	}

	public double camX() {
		return this.camX;
	}

	public double camY() {
		return this.camY;
	}

	public double camZ() {
		return this.camZ;
	}

	public double depth(double x, double y, double z) {
		return (x - this.camX) * this.lookX + (y - this.camY) * this.lookY + (z - this.camZ) * this.lookZ;
	}

	public double distanceSqr(double x, double y, double z) {
		double dx = x - this.camX;
		double dy = y - this.camY;
		double dz = z - this.camZ;
		return dx * dx + dy * dy + dz * dz;
	}

	public void quad(
		double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz, double dx, double dy, double dz, int argb
	) {
		if (ARGB.alpha(argb) >= 3 && this.reserveQuad()) {
			int p = this.quads * 12;
			double[] out = this.quadPos;
			out[p] = ax;
			out[p + 1] = ay;
			out[p + 2] = az;
			out[p + 3] = bx;
			out[p + 4] = by;
			out[p + 5] = bz;
			out[p + 6] = cx;
			out[p + 7] = cy;
			out[p + 8] = cz;
			out[p + 9] = dx;
			out[p + 10] = dy;
			out[p + 11] = dz;
			this.quadColor[this.quads++] = argb;
		}
	}

	public void outwardTriangle(
		double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz, double ox, double oy, double oz, int argb
	) {
		double ux = bx - ax;
		double uy = by - ay;
		double uz = bz - az;
		double vx = cx - ax;
		double vy = cy - ay;
		double vz = cz - az;
		double nx = uy * vz - uz * vy;
		double ny = uz * vx - ux * vz;
		double nz = ux * vy - uy * vx;
		double mx = (ax + bx + cx) / 3.0 - ox;
		double my = (ay + by + cy) / 3.0 - oy;
		double mz = (az + bz + cz) / 3.0 - oz;
		if (nx * mx + ny * my + nz * mz >= 0.0) {
			this.quad(ax, ay, az, bx, by, bz, cx, cy, cz, cx, cy, cz, argb);
		} else {
			this.quad(ax, ay, az, cx, cy, cz, bx, by, bz, bx, by, bz, argb);
		}
	}

	public void line(double ax, double ay, double az, double bx, double by, double bz, int argb, float width) {
		if (ARGB.alpha(argb) >= 3 && this.reserveLine()) {
			int p = this.lines * 6;
			double[] out = this.linePos;
			out[p] = ax;
			out[p + 1] = ay;
			out[p + 2] = az;
			out[p + 3] = bx;
			out[p + 4] = by;
			out[p + 5] = bz;
			this.lineColor[this.lines] = argb;
			this.lineWidth[this.lines++] = width;
		}
	}

	public void ribbon(double ax, double ay, double az, double bx, double by, double bz, int argb, float width, double pixel) {
		double depthA = this.depth(ax, ay, az);
		double depthB = this.depth(bx, by, bz);
		if (!(depthA < 0.05) || !(depthB < 0.05)) {
			if (depthA < 0.05) {
				double t = (0.05 - depthA) / (depthB - depthA);
				ax += (bx - ax) * t;
				ay += (by - ay) * t;
				az += (bz - az) * t;
				depthA = 0.05;
			} else if (depthB < 0.05) {
				double t = (0.05 - depthB) / (depthA - depthB);
				bx += (ax - bx) * t;
				by += (ay - by) * t;
				bz += (az - bz) * t;
				depthB = 0.05;
			}

			double fx = ax - this.camX;
			double fy = ay - this.camY;
			double fz = az - this.camZ;
			double ex = bx - ax;
			double ey = by - ay;
			double ez = bz - az;
			double sx = fy * ez - fz * ey;
			double sy = fz * ex - fx * ez;
			double sz = fx * ey - fy * ex;
			double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
			if (!(length < 1.0E-9)) {
				double scale = width * 0.5 * pixel / length;
				double ka = scale * depthA;
				double kb = scale * depthB;
				this.quad(
					ax + sx * ka,
					ay + sy * ka,
					az + sz * ka,
					bx + sx * kb,
					by + sy * kb,
					bz + sz * kb,
					bx - sx * kb,
					by - sy * kb,
					bz - sz * kb,
					ax - sx * ka,
					ay - sy * ka,
					az - sz * ka,
					argb
				);
			}
		}
	}

	public void shape(float[] shape, double x, double y, double z, double ux, double uy, double uz, double vx, double vy, double vz, int argb) {
		if (ARGB.alpha(argb) >= 3) {
			for (int i = 0; i + 7 < shape.length; i += 8) {
				float u0 = shape[i];
				float v0 = shape[i + 1];
				float u1 = shape[i + 2];
				float v1 = shape[i + 3];
				float u2 = shape[i + 4];
				float v2 = shape[i + 5];
				float u3 = shape[i + 6];
				float v3 = shape[i + 7];
				this.quad(
					x + ux * u0 + vx * v0,
					y + uy * u0 + vy * v0,
					z + uz * u0 + vz * v0,
					x + ux * u1 + vx * v1,
					y + uy * u1 + vy * v1,
					z + uz * u1 + vz * v1,
					x + ux * u2 + vx * v2,
					y + uy * u2 + vy * v2,
					z + uz * u2 + vz * v2,
					x + ux * u3 + vx * v3,
					y + uy * u3 + vy * v3,
					z + uz * u3 + vz * v3,
					argb
				);
			}
		}
	}

	public void sprite(float[] shape, double x, double y, double z, float size, float angle, int argb) {
		this.sprite(shape, x, y, z, size, size, angle, argb);
	}

	public void sprite(float[] shape, double x, double y, double z, float width, float height, float angle, int argb) {
		this.sprite(shape, x, y, z, width, height, angle, 0.0F, 0.0F, argb);
	}

	public void sprite(float[] shape, double x, double y, double z, float width, float height, float angle, float shiftU, float shiftV, int argb) {
		if (ARGB.alpha(argb) >= 3) {
			float cos = Mth.cos(angle);
			float sin = Mth.sin(angle);
			double ux = (this.rightX * cos + this.upX * sin) * width;
			double uy = (this.rightY * cos + this.upY * sin) * width;
			double uz = (this.rightZ * cos + this.upZ * sin) * width;
			double vx = (this.upX * cos - this.rightX * sin) * height;
			double vy = (this.upY * cos - this.rightY * sin) * height;
			double vz = (this.upZ * cos - this.rightZ * sin) * height;
			this.shape(shape, x + ux * shiftU + vx * shiftV, y + uy * shiftU + vy * shiftV, z + uz * shiftU + vz * shiftV, ux, uy, uz, vx, vy, vz, argb);
		}
	}

	public void flat(float[] shape, double x, double y, double z, float size, float angle, int argb) {
		float cos = Mth.cos(angle) * size;
		float sin = Mth.sin(angle) * size;
		double flip = this.camY >= y ? -1.0 : 1.0;
		this.shape(shape, x, y, z, cos, 0.0, flip * sin, -sin, 0.0, flip * cos, argb);
	}

	public void band(double x, double y, double z, float inner, float outer, float from, float to, int segments, int argb) {
		if (!(outer <= inner) && segments > 0 && ARGB.alpha(argb) >= 3) {
			double flip = this.camY >= y ? -1.0 : 1.0;
			float step = (to - from) / segments;
			float cosA = Mth.cos(from);
			float sinA = Mth.sin(from);

			for (int i = 1; i <= segments; i++) {
				float angle = from + step * i;
				float cosB = Mth.cos(angle);
				float sinB = Mth.sin(angle);
				this.quad(
					x + cosA * inner,
					y,
					z + flip * sinA * inner,
					x + cosA * outer,
					y,
					z + flip * sinA * outer,
					x + cosB * outer,
					y,
					z + flip * sinB * outer,
					x + cosB * inner,
					y,
					z + flip * sinB * inner,
					argb
				);
				cosA = cosB;
				sinA = sinB;
			}
		}
	}

	public void ring(double x, double y, double z, float inner, float outer, int segments, int argb) {
		this.band(x, y, z, inner, outer, 0.0F, (float) (Math.PI * 2), segments, argb);
	}

	private boolean reserveQuad() {
		if (this.quads < this.quadColor.length) {
			return true;
		}

		if (this.quads >= 12000) {
			return false;
		}

		int grown = Math.min(12000, this.quadColor.length * 2);
		this.quadColor = Arrays.copyOf(this.quadColor, grown);
		this.quadPos = Arrays.copyOf(this.quadPos, grown * 12);
		return true;
	}

	private boolean reserveLine() {
		if (this.lines < this.lineColor.length) {
			return true;
		}

		if (this.lines >= 4000) {
			return false;
		}

		int grown = Math.min(4000, this.lineColor.length * 2);
		this.lineColor = Arrays.copyOf(this.lineColor, grown);
		this.lineWidth = Arrays.copyOf(this.lineWidth, grown);
		this.linePos = Arrays.copyOf(this.linePos, grown * 6);
		return true;
	}
}
