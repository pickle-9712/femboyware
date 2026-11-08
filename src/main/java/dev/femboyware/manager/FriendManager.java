package dev.femboyware.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Your friends, by player name, saved to {@code .minecraft/femboyware/friends.json}. */
public class FriendManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("friends.json");
	/** Lowercase name to the name as it was written, so lookups ignore case but the list shows it nicely. */
	private final Map<String, String> friends = new TreeMap<>();

	public boolean isFriend(String name) {
		return friends.containsKey(name.toLowerCase(Locale.ROOT));
	}

	public boolean isFriend(Entity entity) {
		return entity instanceof Player player && isFriend(player.getGameProfile().name());
	}

	/** Adds {@code name}; false if they were already a friend. */
	public boolean add(String name) {
		if (friends.putIfAbsent(name.toLowerCase(Locale.ROOT), name) != null) return false;
		save();
		return true;
	}

	/** Removes {@code name}; false if they weren't a friend. */
	public boolean remove(String name) {
		if (friends.remove(name.toLowerCase(Locale.ROOT)) == null) return false;
		save();
		return true;
	}

	/** Adds or removes {@code name}; true if they're a friend now. */
	public boolean toggle(String name) {
		if (remove(name)) return false;
		add(name);
		return true;
	}

	public Collection<String> names() {
		return Collections.unmodifiableCollection(friends.values());
	}

	public void clear() {
		friends.clear();
		save();
	}

	public void load() {
		if (!Files.exists(file)) return;
		try {
			for (JsonElement element : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
				String name = element.getAsString();
				friends.put(name.toLowerCase(Locale.ROOT), name);
			}
		} catch (IOException | RuntimeException e) {
			FemboyWare.LOGGER.error("Failed to load friends", e);
		}
	}

	private void save() {
		JsonArray array = new JsonArray();
		friends.values().forEach(array::add);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(array));
		} catch (IOException e) {
			FemboyWare.LOGGER.error("Failed to save friends", e);
		}
	}
}
