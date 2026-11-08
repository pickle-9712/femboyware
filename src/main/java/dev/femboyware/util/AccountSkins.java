package dev.femboyware.util;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.util.UndashedUuid;
import dev.femboyware.FemboyWare;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Looks up real accounts' skins by name in the background, a few at a time so a slow one can't hold up the rest.
 * Skins are looked up again every so often, so they keep up with the account, and the old one stays on until the new one has downloaded.
 */
public final class AccountSkins {
	private static final long RETRY_MS = 15_000;
	private static final long NO_ACCOUNT_RETRY_MS = 600_000;
	private static final long REFRESH_MS = 600_000;
	/** How long a new skin gets to download before it's used anyway; an account really can have the default skin. */
	private static final long DOWNLOAD_WAIT_MS = 5_000;
	private static final String BULK_LOOKUP = "https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname";
	private static final int BULK_SIZE = 10, MOST_BULK_LOOKUPS = 300, MOST_BULK_FAILURES = 5;
	/** Bulk lookups sent at once each round, and the pause between rounds. */
	private static final int PARALLEL_LOOKUPS = 4;
	private static final long BULK_GAP_MS = 150, BULK_FAILED_WAIT_MS = 5_000;
	/** Accounts Mojang has confirmed before, with their ids, so they're used straight away next time. */
	private static final Path KNOWN_FILE = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("known-accounts.txt");
	private static final Map<String, String> KNOWN = new ConcurrentHashMap<>();
	private static final Map<String, String> KNOWN_IDS = new ConcurrentHashMap<>();
	private static volatile boolean knownLoaded;
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final long MOJANG_DOWN_MS = 60_000;
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
	private static final ExecutorService WORKER = Executors.newFixedThreadPool(8, task -> {
		Thread thread = new Thread(task, "femboyware account skins");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<String, Account> ACCOUNTS = new ConcurrentHashMap<>();
	/** Once Mojang times out, the rest of the list goes straight to PlayerDB for a while. */
	private static volatile long mojangDownUntil;

	private AccountSkins() {
	}

	private static final class Account {
		volatile Supplier<PlayerSkin> skin;
		/** A newer skin still downloading, what it looks like until it's done, and when it started. */
		volatile Supplier<PlayerSkin> downloading;
		volatile PlayerSkin downloadingDefault;
		volatile long downloadStarted;
		volatile long retryAt;
		volatile boolean loading;
	}

	/** The account's skin, or null while it loads or if there's no such account. */
	public static PlayerSkin get(String name) {
		Account account = ACCOUNTS.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new Account());
		Supplier<PlayerSkin> downloading = account.downloading;
		if (downloading != null && (!downloading.get().equals(account.downloadingDefault) || System.currentTimeMillis() - account.downloadStarted > DOWNLOAD_WAIT_MS)) {
			account.skin = downloading;
			account.downloading = null;
		}
		if (!account.loading && System.currentTimeMillis() >= account.retryAt) {
			account.loading = true;
			WORKER.execute(() -> load(name, account));
		}
		Supplier<PlayerSkin> skin = account.skin;
		return skin != null ? skin.get() : null;
	}

	private static void load(String name, Account account) {
		try {
			boolean current = false;
			GameProfile profile = null;
			if (VALID_NAME.matcher(name).matches()) {
				if (System.currentTimeMillis() >= mojangDownUntil) {
					try {
						String id = KNOWN_IDS.get(name.toLowerCase(Locale.ROOT));
						profile = id != null ? fromSession(id) : fromMojang(name);
						current = true;
					} catch (IOException | RuntimeException e) {
						mojangDownUntil = System.currentTimeMillis() + MOJANG_DOWN_MS;
						FemboyWare.LOGGER.debug("Mojang couldn't find the skin of {}", name, e);
					}
				}
				if (!current) profile = fromPlayerDb(name);
			}
			if (profile == null) {
				// No such account; the list can be edited, so look again much later
				account.skin = null;
				account.downloading = null;
				account.retryAt = System.currentTimeMillis() + NO_ACCOUNT_RETRY_MS;
				return;
			}
			account.downloadingDefault = DefaultPlayerSkin.get(profile);
			account.downloadStarted = System.currentTimeMillis();
			account.downloading = Minecraft.getInstance().getSkinManager().createLookup(profile, false);
			// PlayerDB's copy can be out of date, so only keep it until Mojang answers again
			account.retryAt = System.currentTimeMillis() + (current ? REFRESH_MS : MOJANG_DOWN_MS);
		} catch (IOException | RuntimeException e) {
			FemboyWare.LOGGER.warn("Couldn't load the skin of {}: {}", name, e.toString());
			account.retryAt = System.currentTimeMillis() + RETRY_MS;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			account.loading = false;
		}
	}

	/**
	 * Checks {@code candidates} with Mojang a few at a time, and hands the ones that are real accounts to {@code found} on the
	 * client thread, spelled as the account spells them. Stops once {@code keepGoing} says so, then calls {@code finished} on the
	 * client thread, with false if Mojang stopped answering or the candidates ran out first.
	 */
	public static void findAccounts(Iterator<String> candidates, BooleanSupplier keepGoing, Consumer<List<String>> found, Consumer<Boolean> finished) {
		Minecraft client = Minecraft.getInstance();
		Thread thread = new Thread(() -> {
			boolean done = false;
			try {
				// Accounts confirmed on an earlier run go in at once, no lookup needed
				loadKnown();
				if (!KNOWN.isEmpty()) {
					List<String> known = new ArrayList<>(KNOWN.values());
					Collections.shuffle(known);
					client.execute(() -> found.accept(known));
					// Let the client thread take them before deciding whether more are needed
					Thread.sleep(100);
				}
				int failures = 0;
				for (int lookup = 0; lookup < MOST_BULK_LOOKUPS && failures < MOST_BULK_FAILURES; lookup += PARALLEL_LOOKUPS) {
					if (!keepGoing.getAsBoolean()) {
						done = true;
						break;
					}
					List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
					for (int i = 0; i < PARALLEL_LOOKUPS; i++) {
						JsonArray batch = new JsonArray();
						while (batch.size() < BULK_SIZE && candidates.hasNext()) {
							String candidate = candidates.next();
							if (VALID_NAME.matcher(candidate).matches() && !KNOWN.containsKey(candidate.toLowerCase(Locale.ROOT))) batch.add(candidate);
						}
						if (batch.isEmpty()) break;
						requests.add(HTTP.sendAsync(bulkRequest(batch), HttpResponse.BodyHandlers.ofString()));
					}
					if (requests.isEmpty()) break;
					List<String> accounts = new ArrayList<>();
					boolean failed = false;
					for (CompletableFuture<HttpResponse<String>> request : requests) {
						try {
							accounts.addAll(readBulk(request.get()));
						} catch (ExecutionException | IOException | RuntimeException e) {
							failed = true;
							FemboyWare.LOGGER.warn("Couldn't check names with Mojang: {}", e.toString());
						}
					}
					if (!accounts.isEmpty()) {
						saveKnown();
						client.execute(() -> found.accept(accounts));
					}
					if (failed && accounts.isEmpty()) {
						failures++;
						Thread.sleep(BULK_FAILED_WAIT_MS);
					} else {
						failures = 0;
						Thread.sleep(BULK_GAP_MS);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			boolean succeeded = done;
			client.execute(() -> finished.accept(succeeded));
		}, "femboyware account finder");
		thread.setDaemon(true);
		thread.start();
	}

	private static void loadKnown() {
		if (knownLoaded) return;
		knownLoaded = true;
		try {
			if (!Files.exists(KNOWN_FILE)) return;
			for (String row : Files.readAllLines(KNOWN_FILE, StandardCharsets.UTF_8)) {
				String[] parts = row.trim().split("\\s+");
				if (parts.length < 2 || !VALID_NAME.matcher(parts[0]).matches()) continue;
				String key = parts[0].toLowerCase(Locale.ROOT);
				KNOWN.put(key, parts[0]);
				KNOWN_IDS.put(key, parts[1]);
			}
		} catch (IOException e) {
			FemboyWare.LOGGER.warn("Couldn't read known accounts: {}", e.toString());
		}
	}

	private static synchronized void saveKnown() {
		List<String> rows = new ArrayList<>();
		for (Map.Entry<String, String> entry : KNOWN.entrySet()) {
			String id = KNOWN_IDS.get(entry.getKey());
			if (id != null) rows.add(entry.getValue() + " " + id);
		}
		try {
			Files.createDirectories(KNOWN_FILE.getParent());
			Files.write(KNOWN_FILE, rows, StandardCharsets.UTF_8);
		} catch (IOException e) {
			FemboyWare.LOGGER.warn("Couldn't save known accounts: {}", e.toString());
		}
	}

	private static HttpRequest bulkRequest(JsonArray names) {
		return HttpRequest.newBuilder(URI.create(BULK_LOOKUP)).timeout(REQUEST_TIMEOUT).header("User-Agent", "femboyware")
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(names.toString())).build();
	}

	/** The real accounts in a bulk lookup's answer, remembered along with their ids. */
	private static List<String> readBulk(HttpResponse<String> response) throws IOException {
		if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + BULK_LOOKUP);
		List<String> accounts = new ArrayList<>();
		for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
			JsonObject account = element.getAsJsonObject();
			String name = account.get("name").getAsString();
			String key = name.toLowerCase(Locale.ROOT);
			KNOWN.put(key, name);
			if (account.has("id")) KNOWN_IDS.put(key, account.get("id").getAsString());
			accounts.add(name);
		}
		return accounts;
	}

	private static GameProfile fromMojang(String name) throws IOException, InterruptedException {
		String found = fetch("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name);
		if (found == null) return null;
		String id = JsonParser.parseString(found).getAsJsonObject().get("id").getAsString();
		KNOWN_IDS.put(name.toLowerCase(Locale.ROOT), id);
		return fromSession(id);
	}

	private static GameProfile fromSession(String id) throws IOException, InterruptedException {
		String session = fetch("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
		if (session == null) throw new IOException("No profile for " + id);
		return profile(JsonParser.parseString(session).getAsJsonObject());
	}

	private static GameProfile fromPlayerDb(String name) throws IOException, InterruptedException {
		String body = fetch("https://playerdb.co/api/player/minecraft/" + name);
		if (body == null) return null;
		JsonObject player = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data").getAsJsonObject("player");
		return profile(player);
	}

	/** Builds a profile from Mojang's format, which PlayerDB also uses: an id, a name and a list of properties. */
	private static GameProfile profile(JsonObject json) throws IOException {
		String name = json.has("username") ? json.get("username").getAsString() : json.get("name").getAsString();
		String id = json.has("raw_id") ? json.get("raw_id").getAsString() : json.get("id").getAsString();
		for (JsonElement element : json.getAsJsonArray("properties")) {
			JsonObject property = element.getAsJsonObject();
			if (!property.get("name").getAsString().equals("textures")) continue;
			ImmutableMultimap<String, Property> textures = ImmutableMultimap.of("textures", new Property("textures", property.get("value").getAsString()));
			return new GameProfile(UndashedUuid.fromStringLenient(id), name, new PropertyMap(textures));
		}
		throw new IOException("No textures for " + name);
	}

	/** The response body, or null for a 400, 404 or 204, which is how these services say nobody has that name. */
	private static String fetch(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).header("User-Agent", "femboyware").build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		int status = response.statusCode();
		if (status == 400 || status == 404 || status == 204) return null;
		if (status != 200) throw new IOException("HTTP " + status + " from " + url);
		return response.body();
	}
}
