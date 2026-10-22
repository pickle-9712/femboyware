package dev.femboyware.util;

import java.util.function.DoubleUnaryOperator;

/** Standard easing curves mapping t in [0, 1] to [0, 1]. */
public enum Easing {
	LINEAR(t -> t),
	SINE_IN(t -> 1 - Math.cos(t * Math.PI / 2)),
	SINE_OUT(t -> Math.sin(t * Math.PI / 2)),
	SINE_IN_OUT(t -> -(Math.cos(Math.PI * t) - 1) / 2),
	QUAD_IN(t -> t * t),
	QUAD_OUT(t -> 1 - (1 - t) * (1 - t)),
	QUAD_IN_OUT(t -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2),
	CUBIC_IN(t -> t * t * t),
	CUBIC_OUT(t -> 1 - Math.pow(1 - t, 3)),
	CUBIC_IN_OUT(t -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
	EXPO_OUT(t -> t >= 1 ? 1 : 1 - Math.pow(2, -10 * t)),
	CIRC_OUT(t -> Math.sqrt(1 - Math.pow(t - 1, 2)));

	private final DoubleUnaryOperator function;

	Easing(DoubleUnaryOperator function) {
		this.function = function;
	}

	public double apply(double t) {
		return function.applyAsDouble(Math.max(0, Math.min(1, t)));
	}
}
