package dev.femboyware.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.function.Supplier;

/** A dropdown whose options can change while the game runs, like the list of saved themes. */
public class ChoiceSetting extends Setting<String> implements Choice {
	private final Supplier<List<String>> options;

	public ChoiceSetting(String name, String description, String defaultValue, Supplier<List<String>> options) {
		super(name, description, defaultValue);
		this.options = options;
	}

	@Override
	public List<String> optionNames() {
		return options.get();
	}

	@Override
	public int selectedIndex() {
		return optionNames().indexOf(get());
	}

	@Override
	public void setIndex(int index) {
		List<String> names = optionNames();
		if (!names.isEmpty()) set(names.get(Math.floorMod(index, names.size())));
	}

	@Override
	public String getDisplayName() {
		return get();
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement element) {
		value = element.getAsString();
	}
}
