package dev.femboyware.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Saved spots, each tied to the server (or singleplayer world) and dimension it was made in, saved
 * to {@code .minecraft/femboyware/waypoints.json}.
 */
public class WaypointManager {
	/** A saved spot. {@code death} marks the automatic one where you last died. */
	public record Waypoint(String name, BlockPos pos, String world, String dimension, boolean death) {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("waypoints.json");
	private final List<Waypoint> waypoints = new ArrayList<>();

	/** Waypoints for the server and dimension we're in right now. */
	public List<Waypoint> here() {
		String world = currentWorld();
		String dimension = currentDimension();
		List<Waypoint> here = new ArrayList<>();
		for (Waypoint waypoint : waypoints) {
			if (waypoint.world.equals(world) && waypoint.dimension.equals(dimension)) here.add(waypoint);
		}
		return here;
	}

	/** Every waypoint on this server or world, in any dimension. */
	public List<Waypoint> onThisWorld() {
		String world = currentWorld();
		return waypoints.stream().filter(waypoint -> waypoint.world.equals(world)).toList();
	}

	/** Saves a waypoint here, replacing one with the same name on this world. */
	public Waypoint add(String name, BlockPos pos, boolean death) {
		remove(name);
		Waypoint waypoint = new Waypoint(name, pos.immutable(), currentWorld(), currentDimension(), death);
		waypoints.add(waypoint);
		save();
		return waypoint;
	}

	/** Removes the waypoint called {@code name} on this world; false if there's none. */
	public boolean remove(String name) {
		String world = currentWorld();
		boolean removed = waypoints.removeIf(waypoint -> waypoint.world.equals(world) && waypoint.name.equalsIgnoreCase(name));
		if (removed) save();
		return removed;
	}

	/** Removes every waypoint on this world. */
	public int clear() {
		String world = currentWorld();
		int before = waypoints.size();
		waypoints.removeIf(waypoint -> waypoint.world.equals(world));
		save();
		return before - waypoints.size();
	}

	/** The server address, or the singleplayer world's name, so waypoints don't mix between them. */
	public static String currentWorld() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isLocalServer() && mc.getSingleplayerServer() != null) return "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
		if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
		return "unknown";
	}

	public static String currentDimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? "" : mc.level.dimension().identifier().toString();
	}

	public void load() {
		if (!Files.exists(file)) return;
		try {
			for (JsonElement element : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
				JsonObject json = element.getAsJsonObject();
				waypoints.add(new Waypoint(json.get("name").getAsString(),
					new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt()),
					json.get("world").getAsString(), json.get("dimension").getAsString(),
					json.has("death") && json.get("death").getAsBoolean()));
			}
		} catch (IOException | RuntimeException e) {
			FemboyWare.LOGGER.error("Failed to load waypoints", e);
		}
	}

	private void save() {
		JsonArray array = new JsonArray();
		for (Waypoint waypoint : waypoints) {
			JsonObject json = new JsonObject();
			json.addProperty("name", waypoint.name);
			json.addProperty("x", waypoint.pos.getX());
			json.addProperty("y", waypoint.pos.getY());
			json.addProperty("z", waypoint.pos.getZ());
			json.addProperty("world", waypoint.world);
			json.addProperty("dimension", waypoint.dimension);
			json.addProperty("death", waypoint.death);
			array.add(json);
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(array));
		} catch (IOException e) {
			FemboyWare.LOGGER.error("Failed to save waypoints", e);
		}
	}
}
