package dev.femboyware.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Module;
import dev.femboyware.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saves every module's state, keybind and settings to {@code .minecraft/femboyware/config.json}. */
public class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("config.json");

	public void save() {
		JsonObject root = new JsonObject();
		for (Module module : FemboyWare.getModules().getModules()) {
			JsonObject moduleJson = new JsonObject();
			moduleJson.addProperty("enabled", module.isEnabled());
			moduleJson.addProperty("key", module.getKey());
			JsonObject settingsJson = new JsonObject();
			for (Setting<?> setting : module.getSettings()) {
				settingsJson.add(setting.getName(), setting.toJson());
			}
			moduleJson.add("settings", settingsJson);
			root.add(module.getName(), moduleJson);
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(root));
		} catch (IOException e) {
			FemboyWare.LOGGER.error("Failed to save config", e);
		}
	}

	public void load() {
		if (!Files.exists(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			migrateColors(root);
			for (Module module : FemboyWare.getModules().getModules()) {
				// Older configs stored names without spaces ("AutoCrystal")
				String key = root.has(module.getName()) ? module.getName() : module.getName().replace(" ", "");
				if (!root.has(key)) continue;
				JsonObject moduleJson = root.getAsJsonObject(key);
				if (moduleJson.has("key")) module.setKey(moduleJson.get("key").getAsInt());
				if (moduleJson.has("settings")) {
					JsonObject settingsJson = moduleJson.getAsJsonObject("settings");
					for (Setting<?> setting : module.getSettings()) {
						JsonElement element = settingsJson.get(setting.getName());
						if (element == null) continue;
						try {
							setting.fromJson(element);
						} catch (RuntimeException e) {
							FemboyWare.LOGGER.warn("Ignoring bad value for {}.{}", module.getName(), setting.getName());
						}
					}
				}
				if (moduleJson.has("enabled")) module.setEnabled(moduleJson.get("enabled").getAsBoolean());
			}
		} catch (IOException | RuntimeException e) {
			FemboyWare.LOGGER.error("Failed to load config", e);
		}
	}

	/** Where colours used to live before they all moved into Colors: module, old name, new name in Colors. */
	private static final String[][] MOVED_COLORS = {
		{"Glass", "Panel Color", "Panel Color"},
		{"Glass", "List Color", "HUD Background"},
		{"Glass", "Tooltip Color", "Tooltip Color"},
		{"Shine", "Highlight Color", "Highlight Color"},
		{"Background", "Dim Color", "Dim Color"},
		{"Island", "Pill Color", "Island Pill"},
		{"Island", "Highlight Color", "Island Highlight"},
		{"Island", "Text Color", "Island Text"},
		{"Island", "Equalizer Color", "Island Equalizer"},
		{"Island", "Time Color", "Island Time"},
		{"Module List", "Colors", "Module List Colors"},
		{"Module List", "Color", "Module List Color"},
		{"Watermark", "Colors", "Watermark Colors"},
		{"Watermark", "Color", "Watermark Color"},
	};

	/** Copies colours from where an older config kept them into Colors, unless Colors already has its own. */
	private static void migrateColors(JsonObject root) {
		JsonObject colors = root.has("Colors") ? root.getAsJsonObject("Colors") : new JsonObject();
		JsonObject colorSettings = colors.has("settings") ? colors.getAsJsonObject("settings") : new JsonObject();
		boolean changed = false;
		for (String[] move : MOVED_COLORS) {
			String key = root.has(move[0]) ? move[0] : move[0].replace(" ", "");
			if (!root.has(key) || colorSettings.has(move[2])) continue;
			JsonObject old = root.getAsJsonObject(key);
			if (!old.has("settings") || !old.getAsJsonObject("settings").has(move[1])) continue;
			colorSettings.add(move[2], old.getAsJsonObject("settings").get(move[1]));
			changed = true;
		}
		if (!changed) return;
		colors.add("settings", colorSettings);
		root.add("Colors", colors);
	}
}
