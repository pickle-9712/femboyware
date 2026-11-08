package dev.femboyware.util;

import net.minecraft.client.Minecraft;

/**
 * Turns the camera the way a mouse does: only in whole mouse steps at your sensitivity, with
 * whatever is left over from rounding carried into the next turn so nothing drifts.
 */
public final class MouseTurner {
	private static final Minecraft mc = Minecraft.getInstance();

	private double yawCarry;
	private double pitchCarry;

	public void reset() {
		yawCarry = pitchCarry = 0;
	}

	/** Turns the camera by about {@code yaw} and {@code pitch} degrees, rounded to whole mouse steps. */
	public void turn(double yaw, double pitch) {
		double yawStep = yaw + yawCarry;
		double pitchStep = pitch + pitchCarry;
		double sensitivity = mc.options.sensitivity().get() * 0.6 + 0.2;
		double perCount = sensitivity * sensitivity * sensitivity * 8;
		double degreesPerCount = perCount * 0.15;
		long yawCounts = Math.round(yawStep / degreesPerCount);
		long pitchCounts = Math.round(pitchStep / degreesPerCount);
		yawCarry = yawStep - yawCounts * degreesPerCount;
		pitchCarry = pitchStep - pitchCounts * degreesPerCount;
		if (yawCounts != 0 || pitchCounts != 0) mc.player.turn(yawCounts * perCount, pitchCounts * perCount);
	}
}
