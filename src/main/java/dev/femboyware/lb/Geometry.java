/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified for femboyware: ported from Kotlin (utils/math/geometry: Line, LineSegment,
 * LinearGeometry3, NormalizedPlane, AlignedFace) to Java, and the nearest point on a box
 * found by a convex search instead of the original candidate markers.
 */
package dev.femboyware.lb;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Line, segment, plane and axis-aligned face maths used by the scaffold's target finding. */
public final class Geometry {
	static final double EPSILON = 1e-9;
	private static final double PARALLEL_SINE_SQUARED_EPSILON = 1e-9;

	private Geometry() {
	}

	static boolean likelyZero(Vec3 v) {
		return v.lengthSqr() < 1e-12;
	}

	/** Where along a line parameters are allowed: the whole line, or just a segment's 0..1. */
	public enum Domain {
		UNBOUNDED(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
		SEGMENT(0.0, 1.0);

		final double lower, upper;

		Domain(double lower, double upper) {
			this.lower = lower;
			this.upper = upper;
		}

		double normalize(double t) {
			if (!Double.isFinite(t) || t < lower - EPSILON || t > upper + EPSILON) return Double.NaN;
			return Mth.clamp(t, lower, upper);
		}

		double project(double t) {
			return Double.isFinite(t) ? Mth.clamp(t, lower, upper) : Double.NaN;
		}
	}

	/** A point on a line, and its squared distance to whatever it's nearest to. */
	public record NearestPoint(Vec3 point, double distanceSquared) {
	}

	/** A line through {@code anchor} along {@code direction}, or a segment from anchor to anchor + direction. */
	public record Line(Vec3 anchor, Vec3 direction, Domain domain) {
		public Line {
			if (likelyZero(direction)) throw new IllegalArgumentException("Direction should be not zero, actual: " + direction);
		}

		public static Line of(Vec3 position, Vec3 direction) {
			return new Line(position, direction, Domain.UNBOUNDED);
		}

		public static Line segment(Vec3 start, Vec3 end) {
			return new Line(start, end.subtract(start), Domain.SEGMENT);
		}

		public Vec3 start() {
			return anchor;
		}

		public Vec3 end() {
			return anchor.add(direction);
		}

		public Vec3 pointAt(double t) {
			return anchor.add(direction.scale(t));
		}

		public Vec3 pointAtOrNull(double t) {
			double n = domain.normalize(t);
			return Double.isNaN(n) ? null : pointAt(n);
		}

		public double parameterFor(Vec3 p) {
			return direction.dot(p.subtract(anchor)) / direction.lengthSqr();
		}

		public Vec3 nearestPointTo(Vec3 p) {
			return pointAt(domain.project(parameterFor(p)));
		}

		public double distanceToSqr(Vec3 p) {
			return nearestPointTo(p).distanceToSqr(p);
		}

		/** The nearest point on this line to {@code box}. The distance to a box is convex along a line, so a ternary search finds it. */
		public NearestPoint nearestPointTo(AABB box) {
			// The minimum lies within the projection of the box onto the line
			double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < 8; i++) {
				double t = parameterFor(new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ));
				lo = Math.min(lo, t);
				hi = Math.max(hi, t);
			}
			lo = domain.project(lo);
			hi = domain.project(hi);
			for (int i = 0; i < 60 && hi - lo > 1e-9; i++) {
				double a = lo + (hi - lo) / 3, b = hi - (hi - lo) / 3;
				if (box.distanceToSqr(pointAt(a)) <= box.distanceToSqr(pointAt(b))) hi = b;
				else lo = a;
			}
			Vec3 point = pointAt((lo + hi) / 2);
			return new NearestPoint(point, box.distanceToSqr(point));
		}

		public NearestPoint nearestPointTo(VoxelShape shape) {
			if (shape.isEmpty()) return null;
			NearestPoint best = null;
			for (AABB box : shape.toAabbs()) {
				NearestPoint result = nearestPointTo(box);
				if (best == null || result.distanceSquared() < best.distanceSquared() - EPSILON) best = result;
			}
			return best;
		}

		/** The closest pair of points between this and {@code other}: [on this, on other], or null. */
		public Vec3[] nearestPointsTo(Line other) {
			Vec3 d1 = direction, d2 = other.direction;
			Vec3 delta = anchor.subtract(other.anchor);
			double a = d1.dot(d1), b = d1.dot(d2), c = d2.dot(d2), d = d1.dot(delta), e = d2.dot(delta);
			double determinant = a * c - b * b;
			double[] best = {Double.NaN, Double.NaN, Double.POSITIVE_INFINITY};
			java.util.function.BiConsumer<Double, Double> candidate = (t1, t2) -> {
				double first = domain.normalize(t1), second = other.domain.normalize(t2);
				if (Double.isNaN(first) || Double.isNaN(second)) return;
				Vec3 diff = delta.add(d1.scale(first)).subtract(d2.scale(second));
				double distance = diff.lengthSqr();
				if (Double.isNaN(best[0]) || distance < best[2] - EPSILON) {
					best[0] = first;
					best[1] = second;
					best[2] = distance;
				}
			};
			if (determinant > PARALLEL_SINE_SQUARED_EPSILON * a * c) {
				candidate.accept((b * e - c * d) / determinant, (a * e - b * d) / determinant);
			}
			if (domain == Domain.SEGMENT) {
				for (double boundary : new double[]{0, 1}) candidate.accept(boundary, other.domain.project((b * boundary + e) / c));
			}
			if (other.domain == Domain.SEGMENT) {
				for (double boundary : new double[]{0, 1}) candidate.accept(domain.project((b * boundary - d) / a), boundary);
			}
			candidate.accept(domain.project(-d / a), 0.0);
			candidate.accept(0.0, other.domain.project(e / c));
			if (Double.isNaN(best[0])) return null;
			return new Vec3[]{pointAt(best[0]), other.pointAt(best[1])};
		}
	}

	/** A plane through {@code pos} with a unit normal. */
	public record Plane(Vec3 pos, Vec3 normal) {
		public static Plane fromParams(Vec3 base, Vec3 directionA, Vec3 directionB) {
			Vec3 normal = directionA.cross(directionB).normalize();
			if (likelyZero(normal)) throw new IllegalArgumentException("Points must not be on the same line");
			return new Plane(base, normal);
		}

		public Vec3 intersection(Line line) {
			double e = line.direction().dot(normal);
			if (Mth.equal(e, 0.0)) return null;
			double t = (pos.dot(normal) - line.anchor().dot(normal)) / e;
			return line.pointAtOrNull(t);
		}

		public Line intersection(Plane other) {
			Vec3 firstNormal = other.normal, secondNormal = normal;
			Vec3 direction = firstNormal.cross(secondNormal);
			double lengthSqr = direction.lengthSqr();
			if (Mth.equal(lengthSqr, 0.0)) return null;
			double firstDistance = firstNormal.dot(other.pos), secondDistance = secondNormal.dot(pos);
			Vec3 point = secondNormal.cross(direction).scale(firstDistance)
				.add(direction.cross(firstNormal).scale(secondDistance))
				.scale(1.0 / lengthSqr);
			return Line.of(point, direction);
		}
	}

	/** A flat axis-aligned rectangle: one face of a box. */
	public static final class Face {
		public final Vec3 from, to, dimensions;

		public Face(Vec3 a, Vec3 b) {
			from = new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
			to = new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
			dimensions = to.subtract(from);
		}

		public static Face get(Direction direction, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			return switch (direction) {
				case DOWN -> new Face(new Vec3(minX, minY, minZ), new Vec3(maxX, minY, maxZ));
				case UP -> new Face(new Vec3(minX, maxY, minZ), new Vec3(maxX, maxY, maxZ));
				case SOUTH -> new Face(new Vec3(minX, minY, maxZ), new Vec3(maxX, maxY, maxZ));
				case NORTH -> new Face(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, minZ));
				case EAST -> new Face(new Vec3(maxX, minY, minZ), new Vec3(maxX, maxY, maxZ));
				case WEST -> new Face(new Vec3(minX, minY, minZ), new Vec3(minX, maxY, maxZ));
			};
		}

		public AABB asBox() {
			return new AABB(from, to);
		}

		public double area() {
			return dimensions.x * dimensions.y + dimensions.y * dimensions.z + dimensions.x * dimensions.z;
		}

		public Vec3 center() {
			return from.lerp(to, 0.5);
		}

		public Face clamp(AABB box) {
			return new Face(closest(box, from), closest(box, to));
		}

		private static Vec3 closest(AABB box, Vec3 p) {
			return new Vec3(Mth.clamp(p.x, box.minX, box.maxX), Mth.clamp(p.y, box.minY, box.maxY), Mth.clamp(p.z, box.minZ, box.maxZ));
		}

		public Face offset(Vec3 v) {
			return new Face(from.add(v), to.add(v));
		}

		public Vec3 randomPoint() {
			ThreadLocalRandom r = ThreadLocalRandom.current();
			return new Vec3(from.x == to.x ? from.x : r.nextDouble(from.x, to.x), from.y == to.y ? from.y : r.nextDouble(from.y, to.y),
				from.z == to.z ? from.z : r.nextDouble(from.z, to.z));
		}

		public List<Vec3> vertices() {
			AABB b = asBox();
			List<Vec3> out = new ArrayList<>(8);
			for (int i = 0; i < 8; i++) out.add(new Vec3((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ));
			return out;
		}

		private Vec3[] directionVectors() {
			if (dimensions.x == 0.0) return new Vec3[]{new Vec3(0, dimensions.y, 0), new Vec3(0, 0, dimensions.z)};
			if (dimensions.y == 0.0) return new Vec3[]{new Vec3(dimensions.x, 0, 0), new Vec3(0, 0, dimensions.z)};
			if (dimensions.z == 0.0) return new Vec3[]{new Vec3(0, dimensions.y, 0), new Vec3(dimensions.x, 0, 0)};
			throw new IllegalStateException("Face must be axis aligned. dimensions=" + dimensions);
		}

		private List<Line> edges() {
			Vec3[] d = directionVectors();
			List<Line> edges = new ArrayList<>(4);
			if (!likelyZero(d[0])) {
				edges.add(Line.segment(from, from.add(d[0])));
				edges.add(Line.segment(to, to.subtract(d[0])));
			}
			if (!likelyZero(d[1])) {
				edges.add(Line.segment(from, from.add(d[1])));
				edges.add(Line.segment(to, to.subtract(d[1])));
			}
			return edges;
		}

		public Plane toPlane() {
			Vec3[] d = directionVectors();
			return Plane.fromParams(from, d[0], d[1]);
		}

		/** The point on this face nearest to {@code line}. */
		public Vec3 nearestPointTo(Line line) {
			Vec3[] d = directionVectors();
			Plane plane = Plane.fromParams(from, d[0], d[1]);
			List<Line> edges = edges();
			Vec3 intersection = plane.intersection(line);
			Vec3 faceCenter = center();
			if (intersection != null) {
				boolean inFace = true;
				for (Line edge : edges) {
					Vec3 lineCenter = edge.pointAt(0.5);
					if (lineCenter.subtract(intersection).dot(lineCenter.subtract(faceCenter)) <= 0.0) {
						inFace = false;
						break;
					}
				}
				if (edges.isEmpty() || inFace) return intersection;
			}
			Vec3 best = null;
			double bestDistance = Double.POSITIVE_INFINITY;
			for (Line edge : edges) {
				Vec3[] points = edge.nearestPointsTo(line);
				if (points == null) continue;
				double distance = points[0].distanceToSqr(points[1]);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = points[0];
				}
			}
			return best != null ? best : intersection != null ? intersection : faceCenter;
		}

		/** The part of {@code line} that lies across this face, as a segment, or null. */
		public Line coerceInFace(Line line) {
			List<double[]> near = new ArrayList<>();
			List<Vec3> points = new ArrayList<>();
			for (Line edge : edges()) {
				Vec3[] p = line.nearestPointsTo(edge);
				if (p == null) continue;
				near.add(new double[]{p[1].distanceToSqr(p[0]), points.size()});
				points.add(p[1]);
			}
			if (near.size() < 2) return null;
			near.sort((x, y) -> Double.compare(x[0], y[0]));
			Vec3 p1 = points.get((int) near.get(0)[1]), p2 = points.get((int) near.get(1)[1]);
			if (likelyZero(p2.subtract(p1))) return null;
			return Line.segment(p1, p2);
		}
	}
}
