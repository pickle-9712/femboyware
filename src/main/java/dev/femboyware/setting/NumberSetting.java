package dev.femboyware.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class NumberSetting extends Setting<Double> {
	private final double min;
	private final double max;
	private final double step;

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
		super(name, description, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
	}

	@Override
	public void set(Double value) {
		double clamped = Math.max(min, Math.min(max, value));
		double snapped = Math.round((clamped - min) / step) * step + min;
		// Round off floating point noise from the step snapping
		super.set(Math.round(Math.min(max, snapped) * 1e6) / 1e6);
	}

	public double getMin() {
		return min;
	}

	public double getMax() {
		return max;
	}

	public double getStep() {
		return step;
	}

	public float getFloat() {
		return get().floatValue();
	}

	public int getInt() {
		return (int) Math.round(get());
	}

	public boolean isInteger() {
		return step >= 1 && step == Math.floor(step);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement element) {
		set(element.getAsDouble());
	}
}
