package dev.femboyware.module.larp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import dev.femboyware.manager.WaypointManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Keeps where your larp stuff is (the mansion, yacht, hangar, TV, parked jet and helicopter, and your
 * cars) for each world and dimension, in {@code femboyware/larp.json}, so it's all still there after
 * you log out or restart. While you're being raided or your assets are frozen nothing's saved, so
 * once the freeze lifts (or you restart) everything comes back the way it was before the raid.
 */
public final class LarpSave {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("larp.json");

	private static JsonObject root;
	/** The world and dimension we last restored for, or null while not in one. */
	private static String current;
	private static String lastSaved;

	private LarpSave() {
	}

	/** Every tick, from the module manager. */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			current = null;
			return;
		}
		String key = WaypointManager.currentWorld() + "|" + WaypointManager.currentDimension();
		if (!key.equals(current)) {
			current = key;
			restore();
			return;
		}
		// Lets the FBI freeze run out even with the raid module off
		FbiRaidModule.assetsFrozen();
		if (mc.player.tickCount % 20 != 0 || FemboyWare.getModules().get(FbiRaidModule.class).holdsSaves()) return;
		JsonObject state = capture();
		String text = state.toString();
		if (text.equals(lastSaved)) return;
		lastSaved = text;
		root().add(current, state);
		write();
	}

	/** Puts everything back as it was saved for this world, as when the FBI freeze lifts. */
	public static void restore() {
		if (current == null) return;
		JsonElement saved = root().get(current);
		JsonObject state = saved != null && saved.isJsonObject() ? saved.getAsJsonObject() : new JsonObject();
		var modules = FemboyWare.getModules();
		HangarModule hangar = modules.get(HangarModule.class);
		modules.get(MansionModule.class).restorePlace(numbers(state, "mansion"));
		modules.get(YachtModule.class).restorePlace(numbers(state, "yacht"));
		hangar.restorePlace(numbers(state, "hangar"));
		modules.get(TvModule.class).restorePlace(numbers(state, "tv"));
		modules.get(JetModule.class).restorePlace(numbers(state, "jet"));
		modules.get(HelicopterModule.class).restorePlace(numbers(state, "heli"));
		List<double[]> cars = new java.util.ArrayList<>();
		if (state.has("cars") && state.get("cars").isJsonArray()) {
			for (JsonElement car : state.getAsJsonArray("cars")) cars.add(numbers(car));
		}
		modules.get(CarsModule.class).restoreCars(cars, hangar);
		lastSaved = capture().toString();
	}

	private static JsonObject capture() {
		var modules = FemboyWare.getModules();
		JsonObject state = new JsonObject();
		put(state, "mansion", modules.get(MansionModule.class).savePlace());
		put(state, "yacht", modules.get(YachtModule.class).savePlace());
		put(state, "hangar", modules.get(HangarModule.class).savePlace());
		put(state, "tv", modules.get(TvModule.class).savePlace());
		put(state, "jet", modules.get(JetModule.class).savePlace());
		put(state, "heli", modules.get(HelicopterModule.class).savePlace());
		JsonArray cars = new JsonArray();
		for (double[] car : modules.get(CarsModule.class).saveCars(modules.get(HangarModule.class))) cars.add(array(car));
		if (!cars.isEmpty()) state.add("cars", cars);
		return state;
	}

	private static void put(JsonObject state, String name, double[] values) {
		if (values != null) state.add(name, array(values));
	}

	private static JsonArray array(double[] values) {
		JsonArray array = new JsonArray();
		for (double v : values) array.add(v);
		return array;
	}

	private static double[] numbers(JsonObject state, String name) {
		return state.has(name) ? numbers(state.get(name)) : null;
	}

	private static double[] numbers(JsonElement element) {
		if (element == null || !element.isJsonArray()) return null;
		JsonArray array = element.getAsJsonArray();
		double[] values = new double[array.size()];
		try {
			for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsDouble();
		} catch (RuntimeException e) {
			return null;
		}
		return values;
	}

	private static JsonObject root() {
		if (root != null) return root;
		root = new JsonObject();
		try {
			if (Files.exists(FILE)) {
				JsonElement read = JsonParser.parseString(Files.readString(FILE));
				if (read.isJsonObject()) root = read.getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			FemboyWare.LOGGER.warn("Couldn't read {}", FILE, e);
		}
		return root;
	}

	private static void write() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(root()));
		} catch (IOException e) {
			FemboyWare.LOGGER.warn("Couldn't save {}", FILE, e);
		}
	}
}
