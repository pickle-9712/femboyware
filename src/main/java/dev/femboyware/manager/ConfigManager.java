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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Saves every module's state, keybind and settings to {@code .minecraft/femboyware/config.json}, and
 * keeps named configs in {@code femboyware/configs}: saved, loaded, imported from a file or exported
 * to one. Loading or importing first backs up what you had to {@code configs/backups}.
 */
public class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("femboyware");
	private static final int BACKUPS_KEPT = 15;
	private final Path file = DIR.resolve("config.json");

	/** Every module, as config.json has it. {@code sharing} leaves out passwords, for a file going to someone else. */
	public JsonObject snapshot(boolean sharing) {
		JsonObject root = new JsonObject();
		for (Module module : FemboyWare.getModules().getModules()) root.add(module.getName(), snapshot(module, sharing));
		return root;
	}

	/** One module's state, keybind and settings. */
	public JsonObject snapshot(Module module, boolean sharing) {
		JsonObject moduleJson = new JsonObject();
		moduleJson.addProperty("enabled", module.persistsEnabled() && module.isEnabled());
		moduleJson.addProperty("key", module.getKey());
		JsonObject settingsJson = new JsonObject();
		for (Setting<?> setting : module.getSettings()) {
			if (sharing && secret(setting)) continue;
			settingsJson.add(setting.getName(), setting.toJson());
		}
		moduleJson.add("settings", settingsJson);
		return moduleJson;
	}

	public void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(snapshot(false)));
		} catch (IOException e) {
			FemboyWare.LOGGER.error("Failed to save config", e);
		}
	}

	public void load() {
		if (Files.exists(file)) {
			try {
				apply(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), true);
			} catch (IOException | RuntimeException e) {
				FemboyWare.LOGGER.error("Failed to load config", e);
			}
		}
		// The first time there are configs, what you already had is kept as one, so it's never lost
		if (!Files.isDirectory(profiles())) saveProfile("My Config");
	}

	/**
	 * Puts a config into effect. At startup that's everything; later (a saved config, an import) your
	 * own passwords and saved larp data stay as they are, the Configs module isn't touched, and the
	 * ClickGUI stays open.
	 */
	private void apply(JsonObject root, boolean startup) {
		migrateColors(root);
		migrateMoved(root);
		for (Module module : FemboyWare.getModules().getModules()) {
			if (!startup && module instanceof dev.femboyware.module.client.ConfigsModule) continue;
			// Older configs stored names without spaces ("AutoCrystal")
			String key = root.has(module.getName()) ? module.getName() : module.getName().replace(" ", "");
			if (!root.has(key) || !root.get(key).isJsonObject()) continue;
			apply(module, root.getAsJsonObject(key), startup);
		}
	}

	/** One module's part of a config (see {@link #snapshot(Module, boolean)}). */
	public void apply(Module module, JsonObject moduleJson, boolean startup) {
		try {
			if (moduleJson.has("key")) module.setKey(moduleJson.get("key").getAsInt());
		} catch (RuntimeException e) {
			FemboyWare.LOGGER.warn("Ignoring bad key for {}", module.getName());
		}
		if (moduleJson.has("settings") && moduleJson.get("settings").isJsonObject()) {
			JsonObject settingsJson = moduleJson.getAsJsonObject("settings");
			for (Setting<?> setting : module.getSettings()) {
				if (!startup && personal(setting)) continue;
				JsonElement element = settingsJson.get(setting.getName());
				if (element == null) continue;
				try {
					setting.fromJson(element);
				} catch (RuntimeException e) {
					FemboyWare.LOGGER.warn("Ignoring bad value for {}.{}", module.getName(), setting.getName());
				}
			}
		}
		if (!startup && module instanceof dev.femboyware.module.client.ClickGuiModule) return;
		try {
			if (module.persistsEnabled() && moduleJson.has("enabled") && moduleJson.get("enabled").getAsBoolean() != module.isEnabled()) module.setEnabled(moduleJson.get("enabled").getAsBoolean());
		} catch (RuntimeException e) {
			FemboyWare.LOGGER.warn("Couldn't turn {} on or off from a config", module.getName(), e);
		}
	}

	/** Passwords: never written into a config meant for someone else. */
	private static boolean secret(Setting<?> setting) {
		return setting.getName().equalsIgnoreCase("Link Key");
	}

	/**
	 * Yours whatever config you switch to: passwords (so a config from someone can't take over your
	 * guards) and the hidden settings that only hold saved data, like the phone's bank balance.
	 */
	private static boolean personal(Setting<?> setting) {
		return secret(setting) || setting.getDescription() == null || setting.getDescription().isEmpty();
	}

	// ---------------------------------------------------------------- named configs

	public static Path profiles() {
		return DIR.resolve("configs");
	}

	public static String clean(String name) {
		return name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "").trim();
	}

	/** Your saved configs by name, A to Z. */
	public List<String> profileNames() {
		List<String> names = new ArrayList<>();
		if (!Files.isDirectory(profiles())) return names;
		try (Stream<Path> files = Files.list(profiles())) {
			files.filter(Files::isRegularFile).map(f -> f.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".json"))
				.map(n -> n.substring(0, n.length() - 5)).sorted(String.CASE_INSENSITIVE_ORDER).forEach(names::add);
		} catch (IOException ignored) {
		}
		return names;
	}

	/** Saves everything as it is now under {@code name}, replacing one already called that. */
	public boolean saveProfile(String name) {
		name = clean(name);
		if (name.isEmpty()) return false;
		try {
			Files.createDirectories(profiles());
			Files.writeString(profiles().resolve(name + ".json"), GSON.toJson(snapshot(false)));
			return true;
		} catch (IOException e) {
			FemboyWare.LOGGER.error("Failed to save config {}", name, e);
			return false;
		}
	}

	/** Switches to a saved config, backing up what you had first. Null if it worked, or what went wrong. */
	public String loadProfile(String name) {
		Path path = profiles().resolve(clean(name) + ".json");
		if (!Files.exists(path)) return "There's no config called " + name;
		return importFrom(path);
	}

	public boolean deleteProfile(String name) {
		try {
			return Files.deleteIfExists(profiles().resolve(clean(name) + ".json"));
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Puts a config file into effect (one of yours, or one someone sent), backing up what you had
	 * first. Null if it worked, or what was wrong.
	 */
	public String importFrom(Path path) {
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(path));
			if (!parsed.isJsonObject()) return "That isn't a femboyware config";
			root = parsed.getAsJsonObject();
		} catch (IOException e) {
			return "Couldn't read " + path.getFileName();
		} catch (RuntimeException e) {
			return "That isn't a femboyware config (it isn't valid JSON)";
		}
		// It has to be one of ours: at least one module it knows, laid out the way config.json is
		int known = 0;
		for (Module module : FemboyWare.getModules().getModules()) {
			String key = root.has(module.getName()) ? module.getName() : module.getName().replace(" ", "");
			if (root.has(key) && root.get(key).isJsonObject()) known++;
		}
		if (known == 0) return "That isn't a femboyware config (no modules in it)";
		backup();
		apply(root, false);
		save();
		return null;
	}

	/** Writes everything as it is now to a file to keep or send, less passwords. */
	public boolean exportTo(Path path) {
		try {
			if (path.getParent() != null) Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(snapshot(true)));
			return true;
		} catch (IOException e) {
			FemboyWare.LOGGER.error("Failed to export config", e);
			return false;
		}
	}

	/** What you had, kept before a load or import replaces it; the newest few are kept. */
	public void backup() {
		Path backups = profiles().resolve("backups");
		try {
			Files.createDirectories(backups);
			String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
			Files.writeString(backups.resolve(stamp + ".json"), GSON.toJson(snapshot(false)));
			try (Stream<Path> files = Files.list(backups)) {
				List<Path> all = files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
				for (int i = 0; i < all.size() - BACKUPS_KEPT; i++) Files.deleteIfExists(all.get(i));
			}
		} catch (IOException e) {
			FemboyWare.LOGGER.warn("Couldn't back up the config", e);
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

	/** Settings that moved to another module: old module, old name, new module, new name. */
	private static final String[][] MOVED_SETTINGS = {
		{"Roblox Hats", "Hats", "Cosmetics", "Hats"},
		{"Roblox Hats", "Size", "Cosmetics", "Hat Size"},
		{"Roblox Hats", "Raise", "Cosmetics", "Hat Raise"},
	};

	private static void migrateMoved(JsonObject root) {
		for (String[] move : MOVED_SETTINGS) {
			String from = root.has(move[0]) ? move[0] : move[0].replace(" ", "");
			if (!root.has(from) || !root.get(from).isJsonObject()) continue;
			JsonObject old = root.getAsJsonObject(from);
			if (!old.has("settings") || !old.getAsJsonObject("settings").has(move[1])) continue;
			JsonObject target = root.has(move[2]) && root.get(move[2]).isJsonObject() ? root.getAsJsonObject(move[2]) : new JsonObject();
			JsonObject settings = target.has("settings") ? target.getAsJsonObject("settings") : new JsonObject();
			if (settings.has(move[3])) continue;
			settings.add(move[3], old.getAsJsonObject("settings").get(move[1]));
			target.add("settings", settings);
			root.add(move[2], target);
		}
	}

	/** Copies colours from where an older config kept them into Colors, unless Colors already has its own. */
	private static void migrateColors(JsonObject root) {
		JsonObject colors = root.has("Colors") && root.get("Colors").isJsonObject() ? root.getAsJsonObject("Colors") : new JsonObject();
		JsonObject colorSettings = colors.has("settings") ? colors.getAsJsonObject("settings") : new JsonObject();
		boolean changed = false;
		for (String[] move : MOVED_COLORS) {
			String key = root.has(move[0]) ? move[0] : move[0].replace(" ", "");
			if (!root.has(key) || !root.get(key).isJsonObject() || colorSettings.has(move[2])) continue;
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
