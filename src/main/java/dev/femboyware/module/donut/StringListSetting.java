package dev.femboyware.module.donut;

import dev.femboyware.setting.StringSetting;
import java.util.ArrayList;
import java.util.List;

/** A comma-separated list in one text setting, standing in for the Anubis client's list setting (GPL-3.0, 4ldenz). */
public class StringListSetting extends StringSetting {
	public StringListSetting(String name, String description, String... defaults) {
		super(name, description + " (comma separated)", String.join(", ", defaults));
	}

	public List<String> items() {
		List<String> out = new ArrayList<>();
		for (String part : get().split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) out.add(trimmed);
		}
		return out;
	}
}
