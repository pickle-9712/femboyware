package dev.femboyware.module.effects;

/** Smooth value noise over the world, for ground effects that need to join up across blocks. */
final class Noise {
	private Noise() {
	}

	/** A steady pseudo-random 0..1 for a lattice point. */
	static float hash(int x, int z) {
		int h = x * 374761393 + z * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
	}

	/** Smooth 0..1 noise at a world point, with features about {@code scale} blocks across. */
	static float at(double x, double z, double scale) {
		double fx = x / scale, fz = z / scale;
		int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
		float tx = smooth((float) (fx - ix)), tz = smooth((float) (fz - iz));
		float a = hash(ix, iz), b = hash(ix + 1, iz), c = hash(ix, iz + 1), d = hash(ix + 1, iz + 1);
		return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
	}

	/** Two octaves, for something a little less regular. */
	static float fbm(double x, double z, double scale) {
		return at(x, z, scale) * 0.65f + at(x + 91.7, z - 37.3, scale * 0.4) * 0.35f;
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}
}
