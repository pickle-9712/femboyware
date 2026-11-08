package dev.femboyware.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public record Rotation(float yaw, float pitch) {
	public static Rotation between(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		return new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f));
	}

	public Vec3 toVector() {
		float yawRad = (float) Math.toRadians(-yaw) - (float) Math.PI;
		float pitchRad = (float) Math.toRadians(-pitch);
		float cosPitch = -Mth.cos(pitchRad);
		return new Vec3(Mth.sin(yawRad) * cosPitch, Mth.sin(pitchRad), Mth.cos(yawRad) * cosPitch);
	}

	/** Angular distance to {@code other}, accounting for yaw wrap-around. */
	public float distanceTo(Rotation other) {
		float yawDelta = Mth.wrapDegrees(other.yaw - yaw);
		float pitchDelta = other.pitch - pitch;
		return (float) Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
	}

	/** Moves towards {@code target} by at most {@code maxStep} degrees on each axis. */
	public Rotation stepTowards(Rotation target, float maxYawStep, float maxPitchStep) {
		float yawDelta = Mth.clamp(Mth.wrapDegrees(target.yaw - yaw), -maxYawStep, maxYawStep);
		float pitchDelta = Mth.clamp(target.pitch - pitch, -maxPitchStep, maxPitchStep);
		return new Rotation(yaw + yawDelta, Mth.clamp(pitch + pitchDelta, -90f, 90f));
	}

	/**
	 * Snaps the change from {@code previous} onto the mouse sensitivity grid, so the rotation
	 * looks like it could have been produced by real mouse input.
	 */
	public Rotation applyGcd(Rotation previous, double sensitivity) {
		double f = sensitivity * 0.6 + 0.2;
		double gcd = f * f * f * 8.0 * 0.15;
		float yawDelta = Mth.wrapDegrees(yaw - previous.yaw);
		float pitchDelta = pitch - previous.pitch;
		float fixedYaw = previous.yaw + (float) (Math.round(yawDelta / gcd) * gcd);
		float fixedPitch = previous.pitch + (float) (Math.round(pitchDelta / gcd) * gcd);
		return new Rotation(fixedYaw, Mth.clamp(fixedPitch, -90f, 90f));
	}
}
