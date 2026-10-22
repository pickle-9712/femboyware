package dev.femboyware.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting<Boolean> {
	public BoolSetting(String name, String description, boolean defaultValue) {
		super(name, description, defaultValue);
	}

	public void toggle() {
		set(!get());
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement element) {
		set(element.getAsBoolean());
	}
}
