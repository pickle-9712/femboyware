package dev.femboyware.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class EnumSetting<E extends Enum<E>> extends Setting<E> {
	private final E[] constants;

	public EnumSetting(String name, String description, E defaultValue) {
		super(name, description, defaultValue);
		this.constants = defaultValue.getDeclaringClass().getEnumConstants();
	}

	public boolean is(E mode) {
		return get() == mode;
	}

	public void cycle(boolean forward) {
		int index = get().ordinal() + (forward ? 1 : -1);
		set(constants[Math.floorMod(index, constants.length)]);
	}

	public String getDisplayName() {
		return prettify(get().name());
	}

	public static String prettify(String constant) {
		StringBuilder builder = new StringBuilder();
		for (String word : constant.split("_")) {
			if (word.isEmpty()) continue;
			if (!builder.isEmpty()) builder.append(' ');
			builder.append(word.charAt(0)).append(word.substring(1).toLowerCase());
		}
		return builder.toString();
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get().name());
	}

	@Override
	public void fromJson(JsonElement element) {
		for (E constant : constants) {
			if (constant.name().equalsIgnoreCase(element.getAsString())) {
				set(constant);
				return;
			}
		}
	}
}
