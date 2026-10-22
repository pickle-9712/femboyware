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
}
