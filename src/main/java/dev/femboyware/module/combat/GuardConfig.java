package dev.femboyware.module.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ButtonSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.Setting;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Module settings sent from the principal to the guards over the guard link, so guards fight with the
 * principal's aura settings. Only the modules in {@link #MIRRORED} travel, and never keybinds, buttons
 * or whether the module is on: the guard decides that.
 */
public final class GuardConfig {
	static final List<Class<? extends Module>> MIRRORED = List.of(LegitAuraModule.class, AuraModule.class);
	/** Longest settings a guard will unpack, so a bad message can't blow up. */
	private static final int MAX_JSON = 64 * 1024;

	private GuardConfig() {
	}

	private static boolean travels(Setting<?> setting) {
		return !(setting instanceof KeySetting) && !(setting instanceof ButtonSetting);
	}

	/** The module's name and settings, packed small enough for one link message. */
	public static String encode(Module module) {
		JsonObject settings = new JsonObject();
		for (Setting<?> setting : module.getSettings()) {
			if (travels(setting)) settings.add(setting.getName(), setting.toJson());
		}
		byte[] text = (module.getName() + "\n" + settings).getBytes(StandardCharsets.UTF_8);
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(text);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
		deflater.end();
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	/** Applies settings from {@link #encode}; the module's name, or null if it's not one that's mirrored or the data's bad. */
	public static String apply(String packed) {
		String text;
		try {
			Inflater inflater = new Inflater();
			inflater.setInput(Base64.getDecoder().decode(packed.trim()));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			while (!inflater.finished()) {
				int count = inflater.inflate(buffer);
				if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
				out.write(buffer, 0, count);
				if (out.size() > MAX_JSON) {
					inflater.end();
					return null;
				}
			}
			inflater.end();
			text = out.toString(StandardCharsets.UTF_8);
		} catch (IllegalArgumentException | DataFormatException e) {
			return null;
		}
		int split = text.indexOf('\n');
		if (split < 0) return null;
		String name = text.substring(0, split);
		Module module = null;
		for (Class<? extends Module> type : MIRRORED) {
			Module candidate = FemboyWare.getModules().get(type);
			if (candidate.getName().equals(name)) module = candidate;
		}
		if (module == null) return null;
		JsonObject settings;
		try {
			settings = JsonParser.parseString(text.substring(split + 1)).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
		for (Setting<?> setting : module.getSettings()) {
			JsonElement element = settings.get(setting.getName());
			if (element == null || !travels(setting)) continue;
			try {
				setting.fromJson(element);
			} catch (RuntimeException e) {
				FemboyWare.LOGGER.debug("Ignoring mirrored {}.{}", name, setting.getName());
			}
		}
		return name;
	}

	/** A module by name, ignoring case and spaces ("legitpot", "Legit Pot"); null if none. */
	public static Module find(String name) {
		String wanted = name.replace(" ", "").toLowerCase(Locale.ROOT);
		if (wanted.isEmpty()) return null;
		for (Module module : FemboyWare.getModules().getModules()) {
			if (module.getName().replace(" ", "").toLowerCase(Locale.ROOT).equals(wanted)) return module;
		}
		return null;
	}
}
