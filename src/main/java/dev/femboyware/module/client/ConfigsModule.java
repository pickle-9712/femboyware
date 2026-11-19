package dev.femboyware.module.client;

import com.google.gson.JsonElement;
import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.manager.ConfigManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ButtonSetting;
import dev.femboyware.setting.ChoiceSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FilePicker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Your configs: everything (modules on or off, binds, settings) saved under a name to switch between,
 * imported from a file someone sent, or exported to one. Switching backs up what you had first, to
 * femboyware/configs/backups. Not something to turn on: its card opens these settings.
 * {@code .config} does the same from chat.
 */
public class ConfigsModule extends Module {
	private static final String NONE = "(none)";
	private List<String> names = List.of(NONE);

	private final Profile profile = add(new Profile());
	private final ButtonSetting load = add(new ButtonSetting("Load", "Switch to the config picked above (what you have now is backed up first)", this::loadPicked));
	private final StringSetting saveName = add(new StringSetting("Save As", "Name to save everything as it is now under; empty saves over the config picked above", ""));
	private final ButtonSetting save = add(new ButtonSetting("Save", "Saves everything as it is now, under Save As or over the picked config", this::savePicked));
	private final ButtonSetting delete = add(new ButtonSetting("Delete", "Deletes the config picked above (not what you're using now)", this::deletePicked));
	private final ButtonSetting importFile = add(new ButtonSetting("Import...", "Pick a config file from your computer, like one a friend sent, and use it. Your passwords and saved larp money stay yours", this::importFile));
	private final ButtonSetting export = add(new ButtonSetting("Export...", "Save everything as it is now to a file to keep or send (passwords left out)", this::exportFile));
	private final ButtonSetting folder = add(new ButtonSetting("Open Folder", "Opens femboyware/configs, with your configs and the backups", ConfigsModule::openFolder));

	public ConfigsModule() {
		super("Configs", "Save, switch, import and export your whole setup", Category.MISC);
	}

	/** Never stays on: it isn't something that runs. */
	@Override
	protected void onEnable() {
		setEnabled(false);
	}

	@Override
	public String getInfo() {
		return profile.get().equals(NONE) ? null : profile.get();
	}

	private final class Profile extends ChoiceSetting {
		Profile() {
			super("Config", "A saved config to load, save over or delete", NONE, () -> names);
		}

		@Override
		public List<String> optionNames() {
			refresh();
			return names;
		}

		@Override
		public void fromJson(JsonElement element) {
			value = element.getAsString();
		}
	}

	private static ConfigManager config() {
		return FemboyWare.getConfig();
	}

	private void refresh() {
		List<String> found = config().profileNames();
		names = found.isEmpty() ? List.of(NONE) : found;
	}

	private void loadPicked() {
		String name = profile.get();
		if (name.equals(NONE)) {
			ChatUtil.info("Pick a config first, or Import one");
			return;
		}
		load(name);
	}

	public void load(String name) {
		String error = config().loadProfile(name);
		if (error != null) ChatUtil.info(error);
		else {
			profile.set(ConfigManager.clean(name));
			ChatUtil.info("Loaded " + name + " (what you had is in configs/backups)");
		}
	}

	private void savePicked() {
		String name = ConfigManager.clean(saveName.get());
		if (name.isEmpty()) name = profile.get().equals(NONE) ? "" : profile.get();
		if (name.isEmpty()) {
			ChatUtil.info("Type a name in Save As first");
			return;
		}
		save(name);
		saveName.set("");
	}

	public void save(String name) {
		name = ConfigManager.clean(name);
		if (!config().saveProfile(name)) {
			ChatUtil.info("Couldn't save " + name);
			return;
		}
		refresh();
		profile.set(name);
		ChatUtil.info("Saved your config as " + name);
	}

	private void deletePicked() {
		String name = profile.get();
		if (name.equals(NONE)) return;
		if (config().deleteProfile(name)) ChatUtil.info("Deleted " + name);
		refresh();
		profile.set(names.getFirst());
	}

	public void importFile() {
		FilePicker.pick("Choose a femboyware config", "Configs", List.of("json"), path -> {
			String error = config().importFrom(path);
			if (error != null) {
				ChatUtil.info(error);
				return;
			}
			// Kept as a config of its own too, so you can come back to it
			String name = ConfigManager.clean(path.getFileName().toString().replaceAll("(?i)\\.json$", ""));
			if (!name.isEmpty() && !name.equalsIgnoreCase("config")) {
				FilePicker.copyInto(path, ConfigManager.profiles(), name + ".json");
				refresh();
				profile.set(name);
			}
			ChatUtil.info("Imported " + path.getFileName() + " (what you had is in configs/backups)");
		});
	}

	public void exportFile() {
		String suggested = profile.get().equals(NONE) ? "femboyware config" : profile.get();
		FilePicker.saveAs("Export your femboyware config", "femboyware configs", "json", suggested + ".json", path -> {
			if (config().exportTo(path)) ChatUtil.info("Exported your config to " + path);
			else ChatUtil.info("Couldn't write " + path);
		});
	}

	public static void openFolder() {
		Path dir = ConfigManager.profiles();
		try {
			Files.createDirectories(dir);
			//? if >=26.3 {
			/*com.mojang.blaze3d.Blaze3D.openPath(dir);
			*///?} else {
			net.minecraft.util.Util.getPlatform().openPath(dir);
			//?}
		} catch (Exception e) {
			ChatUtil.info("Your configs: " + dir);
		}
	}

	/** {@code .config [list|save <name>|load <name>|delete <name>|import|export|folder]}. */
	public void command(String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
		String rest = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
		switch (sub) {
			case "list", "ls" -> {
				List<String> all = config().profileNames();
				ChatUtil.info(all.isEmpty() ? "No saved configs yet: " + CommandManager.PREFIX + "config save <name>" : "Configs: " + String.join(", ", all));
			}
			case "save" -> {
				if (rest.isBlank()) ChatUtil.info("Usage: " + CommandManager.PREFIX + "config save <name>");
				else save(rest);
			}
			case "load", "use" -> {
				if (rest.isBlank()) ChatUtil.info("Usage: " + CommandManager.PREFIX + "config load <name>");
				else load(rest);
			}
			case "delete", "remove" -> {
				if (config().deleteProfile(rest)) ChatUtil.info("Deleted " + rest);
				else ChatUtil.info("There's no config called " + rest);
				refresh();
			}
			case "import" -> importFile();
			case "export" -> exportFile();
			case "folder", "dir", "open" -> openFolder();
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "config [list|save <name>|load <name>|delete <name>|import|export|folder]");
		}
	}
}
