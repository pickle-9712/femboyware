package dev.femboyware.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** ARGB colour, edited in the ClickGUI as four channel sliders. */
public class ColorSetting extends Setting<Integer> {
	public ColorSetting(String name, String description, int defaultArgb) {
		super(name, description, defaultArgb);
	}

	public int getChannel(int shift) {
		return (get() >>> shift) & 0xFF;
	}

	public void setChannel(int shift, int channelValue) {
		int clamped = Math.max(0, Math.min(255, channelValue));
		set((get() & ~(0xFF << shift)) | (clamped << shift));
	}

	public int getAlpha() {
		return getChannel(24);
	}

	public int withAlpha(int alpha) {
		return (get() & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(Integer.toHexString(get()));
	}

	@Override
	public void fromJson(JsonElement element) {
		set((int) Long.parseLong(element.getAsString(), 16));
	}
}
