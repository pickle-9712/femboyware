package dev.femboyware.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class StringSetting extends Setting<String> {
	public StringSetting(String name, String description, String defaultValue) {
		super(name, description, defaultValue);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement element) {
		set(element.getAsString());
	}
}
