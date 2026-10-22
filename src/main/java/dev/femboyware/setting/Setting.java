package dev.femboyware.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
	private final String name;
	private final String description;
	private final T defaultValue;
	protected T value;
	private BooleanSupplier visibility = () -> true;

	protected Setting(String name, String description, T defaultValue) {
		this.name = name;
		this.description = description;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
	}

	public void reset() {
		set(defaultValue);
	}

	public boolean isVisible() {
		return visibility.getAsBoolean();
	}

	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S visibleWhen(BooleanSupplier visibility) {
		this.visibility = visibility;
		return (S) this;
	}

	public abstract JsonElement toJson();

	public abstract void fromJson(JsonElement element);
}
