package dev.femboyware.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A row you click to do something once; it has no value and isn't saved. */
public class ButtonSetting extends Setting<Boolean> {
	private final Runnable action;

	public ButtonSetting(String name, String description, Runnable action) {
		super(name, description, false);
		this.action = action;
	}

	public void press() {
		action.run();
	}

	@Override
	public JsonElement toJson() {
		return JsonNull.INSTANCE;
	}

	@Override
	public void fromJson(JsonElement element) {
	}
}
