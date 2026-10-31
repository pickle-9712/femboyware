package dev.femboyware.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A keyboard key, picked in the ClickGUI by clicking the row and pressing the key. -1 is none. */
public class KeySetting extends Setting<Integer> {
	public KeySetting(String name, String description, int defaultKey) {
		super(name, description, defaultKey);
	}

	public boolean is(int key) {
		return get() >= 0 && get() == key;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement element) {
		set(element.getAsInt());
	}
}
