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
 * Modified for femboyware: ported from Kotlin (RotationsValueGroup, LinearAngleSmooth,
 * SigmoidAngleSmooth, InterpolationAngleSmooth, AccelerationAngleSmooth, FactorAngleSmooth,
 * ShortStopRotationProcessor, FailRotationProcessor, Rotation.towardsLinear) to Java. Ranges are
 * min/max number settings, the groups are flattened into one settings list on the owning module,
 * the AI smoother and DynamicAccel's entity distance are left out, and the movement correction
 * choice stays with femboyware's global Move Fix module.
 */
package dev.femboyware.lb;

import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.Setting;
import dev.femboyware.util.Rotation;
import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * LiquidBounce's rotation settings: how the server rotation turns towards a target each tick
 * (angle smoothing), the occasional short stop and deliberate miss, and how it hands back to
 * your camera when the module stops aiming.
 *
 * <p>A module creates one in its constructor with its own {@code add} and passes it to
 * {@link dev.femboyware.manager.RotationManager#request(Rotation, int, RotationSettings, boolean)}.
 */
public final class RotationSettings {
	public enum Smooth { LINEAR, SIGMOID, INTERPOLATION, ACCELERATION }

	private final EnumSetting<Smooth> mode;
	private final NumberSetting hMin, hMax, vMin, vMax;
	private final NumberSetting steepness, midpoint;
	private final NumberSetting hPctMin, hPctMax, vPctMin, vPctMax, dirMin, dirMax, interpMidpoint;
	private final NumberSetting yawAccMin, yawAccMax, pitchAccMin, pitchAccMax;
	private final BoolSetting accelError, constantError, sigmoidDecel;
	private final NumberSetting accelErrorAmount, constantErrorAmount;
	private final BoolSetting shortStop;
	private final NumberSetting shortStopRate, shortStopMin, shortStopMax;
	private final BoolSetting fail;
	private final NumberSetting failRate, failFactor, failHMin, failHMax, failVMin, failVMax, failTicksMin, failTicksMax;
	private final NumberSetting resetThreshold;
	private final BoolSetting smoothReset;

	// Processor state
	private Rotation previousTarget;
	private float prevYawDelta, prevPitchDelta;
	private int shortStopElapsed, shortStopDuration;
	private int failElapsed = Integer.MAX_VALUE, failDuration;
	private Rotation failShift = new Rotation(0, 0);

	/**
	 * @param add the owning module's {@code add}, e.g. {@code this::add}
	 * @param visible when these settings show, e.g. on the module's rotations page
	 * @param defaultSpeed starting Linear turn speed in degrees per tick
	 */
	@SuppressWarnings("unchecked")
	public RotationSettings(Function<Setting<?>, Setting<?>> add, BooleanSupplier visible, float defaultSpeed) {
		mode = (EnumSetting<Smooth>) add.apply(new EnumSetting<>("Angle Smooth", "How the aim turns each tick (LiquidBounce)", Smooth.LINEAR).visibleWhen(visible));
		BooleanSupplier linearish = () -> visible.getAsBoolean() && (mode.is(Smooth.LINEAR) || mode.is(Smooth.SIGMOID));
		hMin = num(add, "Turn H Min", "Slowest sideways turn in degrees per tick", defaultSpeed, 0, 180, 1, linearish);
		hMax = num(add, "Turn H Max", "Fastest sideways turn in degrees per tick", defaultSpeed, 0, 180, 1, linearish);
		vMin = num(add, "Turn V Min", "Slowest up/down turn in degrees per tick", defaultSpeed, 0, 180, 1, linearish);
		vMax = num(add, "Turn V Max", "Fastest up/down turn in degrees per tick", defaultSpeed, 0, 180, 1, linearish);
		BooleanSupplier sigmoid = () -> visible.getAsBoolean() && mode.is(Smooth.SIGMOID);
		steepness = num(add, "Steepness", "How sharply the speed rises with the angle left", 10, 0, 20, 0.1, sigmoid);
		midpoint = num(add, "Midpoint", "Share of 120 degrees where the speed is half", 0.3, 0, 1, 0.01, sigmoid);
		BooleanSupplier interp = () -> visible.getAsBoolean() && mode.is(Smooth.INTERPOLATION);
		hPctMin = num(add, "H Percent Min", "Least share of the yaw left to turn each tick", 80, 1, 100, 1, interp);
		hPctMax = num(add, "H Percent Max", "Most share of the yaw left to turn each tick", 85, 1, 100, 1, interp);
		vPctMin = num(add, "V Percent Min", "Least share of the pitch left to turn each tick", 20, 1, 100, 1, interp);
		vPctMax = num(add, "V Percent Max", "Most share of the pitch left to turn each tick", 25, 1, 100, 1, interp);
		dirMin = num(add, "Direction Change Min", "Extra speed when the target jumps somewhere new (percent)", 95, 0, 100, 1, interp);
		dirMax = num(add, "Direction Change Max", "Extra speed when the target jumps somewhere new (percent)", 100, 0, 100, 1, interp);
		interpMidpoint = num(add, "Interp Midpoint", "Above this share of 180 degrees it eases in, below it slows down", 0.35, 0, 1, 0.01, interp);
		BooleanSupplier accel = () -> visible.getAsBoolean() && mode.is(Smooth.ACCELERATION);
		yawAccMin = num(add, "Accel Yaw Min", "Least yaw speed change per tick", 20, 1, 180, 1, accel);
		yawAccMax = num(add, "Accel Yaw Max", "Most yaw speed change per tick", 25, 1, 180, 1, accel);
		pitchAccMin = num(add, "Accel Pitch Min", "Least pitch speed change per tick", 20, 1, 180, 1, accel);
		pitchAccMax = num(add, "Accel Pitch Max", "Most pitch speed change per tick", 25, 1, 180, 1, accel);
		accelError = bool(add, "Accel Error", "Wobble in proportion to the speed change", true, accel);
		accelErrorAmount = num(add, "Accel Error Amount", "How much", 0.1, 0.01, 1, 0.01, () -> accel.getAsBoolean() && accelError.get());
		constantError = bool(add, "Constant Error", "A small random wobble every tick", true, accel);
		constantErrorAmount = num(add, "Constant Error Amount", "Degrees", 0.1, 0.01, 1, 0.01, () -> accel.getAsBoolean() && constantError.get());
		sigmoidDecel = bool(add, "Sigmoid Decel", "Slow down near the target", false, accel);

		shortStop = bool(add, "Short Stop", "Now and then nearly stop turning for a tick or two", false, visible);
		BooleanSupplier ss = () -> visible.getAsBoolean() && shortStop.get();
		shortStopRate = num(add, "Short Stop Rate", "Chance per tick (percent)", 3, 1, 25, 1, ss);
		shortStopMin = num(add, "Short Stop Min", "Shortest stop in ticks", 1, 1, 5, 1, ss);
		shortStopMax = num(add, "Short Stop Max", "Longest stop in ticks", 2, 1, 5, 1, ss);

		fail = bool(add, "Fail", "Now and then aim a little off on purpose", false, visible);
		BooleanSupplier f = () -> visible.getAsBoolean() && fail.get();
		failRate = num(add, "Fail Rate", "Chance per tick (percent)", 3, 1, 100, 1, f);
		failFactor = num(add, "Fail Factor", "How much of the last turn carries into the miss", 0.04, 0.01, 0.99, 0.01, f);
		failHMin = num(add, "Fail H Min", "Least sideways miss in degrees", 5, 1, 90, 0.5, f);
		failHMax = num(add, "Fail H Max", "Most sideways miss in degrees", 10, 1, 90, 0.5, f);
		failVMin = num(add, "Fail V Min", "Least up/down miss in degrees", 0, 0, 90, 0.5, f);
		failVMax = num(add, "Fail V Max", "Most up/down miss in degrees", 2, 0, 90, 0.5, f);
		failTicksMin = num(add, "Fail Ticks Min", "Shortest miss", 1, 0, 20, 1, f);
		failTicksMax = num(add, "Fail Ticks Max", "Longest miss", 4, 0, 20, 1, f);

		smoothReset = bool(add, "Smooth Reset", "Turn back to your camera smoothly when done, instead of snapping", true, visible);
		resetThreshold = num(add, "Reset Threshold", "Stop turning back once this close (degrees)", 2, 1, 180, 0.5, () -> visible.getAsBoolean() && smoothReset.get());
	}

	private static NumberSetting num(Function<Setting<?>, Setting<?>> add, String name, String desc, double def, double min, double max, double step, BooleanSupplier visible) {
		return (NumberSetting) add.apply(new NumberSetting(name, desc, def, min, max, step).visibleWhen(visible));
	}

	private static BoolSetting bool(Function<Setting<?>, Setting<?>> add, String name, String desc, boolean def, BooleanSupplier visible) {
		return (BoolSetting) add.apply(new BoolSetting(name, desc, def).visibleWhen(visible));
	}

	public boolean smoothReset() {
		return smoothReset.get();
	}

	public float resetThreshold() {
		return resetThreshold.getFloat();
	}

	/** Clears the state carried between ticks, for when the module stops aiming. */
	public void reset() {
		previousTarget = null;
		prevYawDelta = prevPitchDelta = 0;
		shortStopElapsed = shortStopDuration = 0;
		failElapsed = Integer.MAX_VALUE;
	}

	// ---------------------------------------------------------------- processing

	/**
	 * The rotation to have next tick, going from {@code current} towards {@code target}.
	 * Angle smooth first, then short stop, then fail, as in LiquidBounce's RotationTarget.
	 *
	 * @param aiming false while handing back to the camera: smoothing only, at its slowest
	 */
	public Rotation process(Rotation current, Rotation target, boolean aiming) {
		Rotation next = smooth(current, target, aiming);
		if (aiming) {
			next = shortStop(current, next);
			next = fail(current, next);
			previousTarget = target;
		}
		prevYawDelta = Mth.wrapDegrees(next.yaw() - current.yaw());
		prevPitchDelta = next.pitch() - current.pitch();
		return new Rotation(next.yaw(), Mth.clamp(next.pitch(), -90f, 90f));
	}

	private Rotation smooth(Rotation current, Rotation target, boolean aiming) {
		return switch (mode.get()) {
			case LINEAR -> towardsLinear(current, target, range(hMin, hMax, aiming), range(vMin, vMax, aiming));
			case SIGMOID -> {
				float diff = Math.min(current.distanceTo(target), 180f);
				yield towardsLinear(current, target, sigmoidFactor(diff, range(hMin, hMax, aiming)), sigmoidFactor(diff, range(vMin, vMax, aiming)));
			}
			case INTERPOLATION -> interpolation(current, target, aiming);
			case ACCELERATION -> acceleration(current, target);
		};
	}

	/** Turns along the straight line to {@code target}, at most the given factors on each axis. */
	public static Rotation towardsLinear(Rotation current, Rotation target, float horizontalFactor, float verticalFactor) {
		float dYaw = Mth.wrapDegrees(target.yaw() - current.yaw());
		float dPitch = target.pitch() - current.pitch();
		float length = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
		if (length < 1e-6f) return current;
		float straightYaw = Math.abs(dYaw / length) * horizontalFactor;
		float straightPitch = Math.abs(dPitch / length) * verticalFactor;
		return new Rotation(current.yaw() + Mth.clamp(dYaw, -straightYaw, straightYaw),
			current.pitch() + Mth.clamp(dPitch, -straightPitch, straightPitch));
	}

	private float sigmoidFactor(float diff, float turnSpeed) {
		double scaled = diff / 120f;
		double sigmoid = 1 / (1 + Math.exp(-steepness.get() * (scaled - midpoint.get())));
		return Mth.clamp((float) (sigmoid * turnSpeed), 0f, 180f);
	}

	private Rotation interpolation(Rotation current, Rotation target, boolean aiming) {
		float yawDiff = Mth.wrapDegrees(target.yaw() - current.yaw());
		float pitchDiff = target.pitch() - current.pitch();
		float directionChange = 0f;
		if (aiming && previousTarget != null) {
			directionChange = normalize(previousTarget.distanceTo(target)) * (range(dirMin, dirMax, true) / 100f);
		}
		float h = Mth.clamp(range(hPctMin, hPctMax, aiming) / 100f, 0f, 1f);
		float v = Mth.clamp(range(vPctMin, vPctMax, aiming) / 100f, 0f, 1f);
		float hFactor = interpFactor(Math.abs(yawDiff), h, directionChange) * Math.abs(yawDiff);
		float vFactor = interpFactor(Math.abs(pitchDiff), v, directionChange) * Math.abs(pitchDiff);
		return towardsLinear(current, target, hFactor, vFactor);
	}

	private static float normalize(float angle) {
		return Mth.clamp(angle / 180f, 0f, 1f);
	}

	private float interpFactor(float difference, float turnSpeed, float directionChange) {
		float t = normalize(difference);
		float u = 1f - t;
		float bezier = (1f - u) * (1f - u) * 0.05f + 2f * (1f - u) * u + u * u;
		float sigmoid = (float) (1f / (1f + Math.exp(-0.5f * (t - 0.3f))));
		if (t > interpMidpoint.get()) return bezier * turnSpeed;
		return sigmoid * Mth.clamp(turnSpeed + directionChange, 0f, 1f);
	}

	private Rotation acceleration(Rotation current, Rotation target) {
		float yawDiff = Mth.wrapDegrees(target.yaw() - current.yaw());
		float pitchDiff = target.pitch() - current.pitch();
		float decel = 1f;
		if (sigmoidDecel.get()) {
			double scaled = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff) / 120f;
			decel = Mth.clamp((float) (1 / (1 + Math.exp(-steepness.get() * (scaled - midpoint.get())))), 0f, 180f);
		}
		float aYaw = accel(yawDiff, prevYawDelta, -random(yawAccMin, yawAccMax), random(yawAccMin, yawAccMax), decel);
		float aPitch = accel(pitchDiff, prevPitchDelta, -random(pitchAccMin, pitchAccMax), random(pitchAccMin, pitchAccMax), decel);
		float yaw = prevYawDelta + aYaw + error(aYaw);
		float pitch = prevPitchDelta + aPitch + error(aPitch);
		return new Rotation(current.yaw() + yaw, current.pitch() + pitch);
	}

	private static float accel(float diff, float prevDiff, float lo, float hi, float decel) {
		return Mth.clamp(Mth.wrapDegrees(diff - prevDiff), Math.min(lo, hi), Math.max(lo, hi)) * decel;
	}

	private float error(float acceleration) {
		float a = accelError.get() ? randomSym(accelErrorAmount.getFloat()) : 0f;
		float c = constantError.get() ? randomSym(constantErrorAmount.getFloat()) : 0f;
		return acceleration * a + c;
	}

	private Rotation shortStop(Rotation current, Rotation target) {
		if (!shortStop.get()) return target;
		if (ThreadLocalRandom.current().nextFloat() * 100f < shortStopRate.getFloat()) {
			shortStopDuration = Math.round(random(shortStopMin, shortStopMax));
			shortStopElapsed = 0;
		}
		if (shortStopElapsed < shortStopDuration) {
			shortStopElapsed++;
			ThreadLocalRandom r = ThreadLocalRandom.current();
			return towardsLinear(current, target, r.nextFloat() * 0.1f, r.nextFloat() * 0.1f);
		}
		return target;
	}

	private Rotation fail(Rotation current, Rotation target) {
		if (!fail.get()) return target;
		ThreadLocalRandom r = ThreadLocalRandom.current();
		if (r.nextFloat() * 100f < failRate.getFloat()) {
			failDuration = Math.round(random(failTicksMin, failTicksMax));
			float yaw = random(failHMin, failHMax) * (r.nextBoolean() ? 1 : -1);
			float pitch = random(failVMin, failVMax) * (r.nextBoolean() ? 1 : -1);
			failShift = new Rotation(yaw, pitch);
			failElapsed = 0;
		} else if (failElapsed != Integer.MAX_VALUE) {
			failElapsed++;
		}
		if (failElapsed >= failDuration) return target;
		float factor = failFactor.getFloat();
		return new Rotation(target.yaw() - prevYawDelta * factor + failShift.yaw(),
			target.pitch() - prevPitchDelta * factor + failShift.pitch());
	}

	/** About how many ticks turning from {@code current} to {@code target} takes at the slowest speeds (calculateTicks). */
	public int estimateTicks(Rotation current, Rotation target) {
		if (mode.is(Smooth.ACCELERATION)) {
			float accel = (float) Math.max(1, Math.min(Math.min(yawAccMin.get(), yawAccMax.get()), Math.min(pitchAccMin.get(), pitchAccMax.get())));
			return (int) Math.ceil(Math.sqrt(2 * current.distanceTo(target) / accel));
		}
		int ticks = 0;
		while (current.distanceTo(target) > 0.5f && ticks < 80) {
			Rotation next = smooth(current, target, false);
			if (next.distanceTo(current) < 1e-4f) return 80;
			current = next;
			ticks++;
		}
		return ticks;
	}

	// ---------------------------------------------------------------- ranges

	/** A random value in the range while aiming, its low end otherwise (LiquidBounce's slowest speed). */
	private static float range(NumberSetting min, NumberSetting max, boolean random) {
		return random ? random(min, max) : (float) Math.min(min.get(), max.get());
	}

	private static float random(NumberSetting min, NumberSetting max) {
		double lo = Math.min(min.get(), max.get()), hi = Math.max(min.get(), max.get());
		if (hi - lo < 1e-9) return (float) lo;
		return (float) ThreadLocalRandom.current().nextDouble(lo, hi);
	}

	private static float randomSym(float amount) {
		return (float) ThreadLocalRandom.current().nextDouble(-amount, amount);
	}
}
