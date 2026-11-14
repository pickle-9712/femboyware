package dev.femboyware.module.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.sun.net.httpserver.HttpServer;
import dev.femboyware.FemboyWare;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Your Spotify library through Spotify's Web API: Liked Songs and your playlists, what's in them,
 * and playing them on the Spotify app on this PC. Signing in uses your own Spotify app's Client ID
 * (free, from developer.spotify.com) with PKCE, so there's no secret: the browser opens, you
 * agree, and Spotify hands the login back to a little server on this PC. The refresh token is kept
 * in femboyware/spotify.json so you only sign in once.
 *
 * <p>Everything here runs on one background thread; the getters are safe from the render thread.
 */
public final class SpotifyWeb {
	public static final int PORT = 43821;
	public static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";
	public static final String LIKED = "liked";
	private static final String SCOPES = "playlist-read-private playlist-read-collaborative user-library-read user-read-playback-state user-modify-playback-state";
	private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("spotify.json");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "femboyware spotify web");
		thread.setDaemon(true);
		return thread;
	});
	private static final SecureRandom RANDOM = new SecureRandom();

	/** A playlist (or Liked Songs, whose id is {@link #LIKED}). */
	public record Playlist(String id, String name, String uri, String owner, int count, String image) {}

	/** A song in a playlist. */
	public record Song(String name, String artists, String uri, int millis, String image) {}

	private static volatile String clientId = "", refreshToken = "", accessToken = "", userId = "";
	private static volatile long expiresAt;
	private static volatile boolean loaded, busy, signingIn;
	private static volatile String status;
	private static volatile List<Playlist> playlists;
	private static final Map<String, List<Song>> SONGS = new ConcurrentHashMap<>();
	private static HttpServer server;

	private SpotifyWeb() {
	}

	// ---------------------------------------------------------------- state for the app

	private static void load() {
		if (loaded) return;
		loaded = true;
		try {
			if (Files.exists(FILE)) {
				JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
				clientId = string(json, "client_id");
				refreshToken = string(json, "refresh_token");
			}
		} catch (Exception e) {
			FemboyWare.LOGGER.debug("Couldn't read spotify.json", e);
		}
	}

	private static void save() {
		try {
			JsonObject json = new JsonObject();
			json.addProperty("client_id", clientId);
			json.addProperty("refresh_token", refreshToken);
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, json.toString());
		} catch (IOException e) {
			FemboyWare.LOGGER.debug("Couldn't save spotify.json", e);
		}
	}

	public static boolean hasClientId() {
		load();
		return !clientId.isBlank();
	}

	public static boolean signedIn() {
		load();
		return !refreshToken.isBlank();
	}

	public static boolean signingIn() {
		return signingIn;
	}

	public static boolean busy() {
		return busy;
	}

	/** What went wrong last, or a note about what just happened; null when there's nothing to say. */
	public static String status() {
		return status;
	}

	public static void clearStatus() {
		status = null;
	}

	/** Your playlists with Liked Songs first, or null until they've loaded. */
	public static List<Playlist> playlists() {
		return playlists;
	}

	/** The songs in a playlist, or null until they've loaded (ask with {@link #loadSongs}). */
	public static List<Song> songs(String playlistId) {
		return SONGS.get(playlistId);
	}

	/** Saves a Client ID pasted from the developer dashboard. False if it doesn't look like one. */
	public static boolean setClientId(String text) {
		load();
		String id = text == null ? "" : text.trim();
		if (!id.matches("[0-9a-fA-F]{32}")) return false;
		clientId = id.toLowerCase(Locale.ROOT);
		refreshToken = "";
		accessToken = "";
		save();
		return true;
	}

	public static void signOut() {
		refreshToken = "";
		accessToken = "";
		playlists = null;
		SONGS.clear();
		save();
	}

	// ---------------------------------------------------------------- signing in

	/** Opens the browser to Spotify's sign-in, and waits for it to come back to this PC. */
	public static void signIn() {
		load();
		if (clientId.isBlank() || signingIn) return;
		signingIn = true;
		status = "Finish signing in in your browser";
		WORKER.submit(() -> {
			try {
				byte[] raw = new byte[48];
				RANDOM.nextBytes(raw);
				String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
				String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
				byte[] stateBytes = new byte[12];
				RANDOM.nextBytes(stateBytes);
				String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
				startServer(verifier, state);
				String url = "https://accounts.spotify.com/authorize?response_type=code&client_id=" + clientId
					+ "&scope=" + enc(SCOPES) + "&redirect_uri=" + enc(REDIRECT) + "&code_challenge_method=S256&code_challenge=" + challenge + "&state=" + state;
				openInBrowser(url);
			} catch (Exception e) {
				FemboyWare.LOGGER.warn("Spotify sign-in failed to start", e);
				status = "Couldn't start signing in: " + e.getMessage();
				signingIn = false;
			}
		});
	}

	private static synchronized void startServer(String verifier, String state) throws IOException {
		stopServer();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
		server.createContext("/callback", exchange -> {
			Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
			boolean ok = state.equals(query.get("state")) && query.containsKey("code");
			String page = ok ? "Signed in to Spotify. You can close this tab and go back to Minecraft."
				: "Spotify sign-in didn't work" + (query.containsKey("error") ? ": " + query.get("error") : "") + ". Try again from the phone.";
			byte[] body = ("<!doctype html><meta charset=utf-8><title>femboyware</title><body style=\"font:16px sans-serif;background:#121212;color:#fff;padding:40px\">"
				+ page + "</body>").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
			if (ok) WORKER.submit(() -> finishSignIn(query.get("code"), verifier));
			else {
				status = "Sign-in was cancelled";
				signingIn = false;
			}
			WORKER.submit(SpotifyWeb::stopServer);
		});
		server.start();
	}

	private static synchronized void stopServer() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	private static void finishSignIn(String code, String verifier) {
		try {
			JsonObject token = tokenRequest("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT)
				+ "&client_id=" + clientId + "&code_verifier=" + verifier);
			if (token == null) return;
			status = null;
			refreshPlaylists();
		} finally {
			signingIn = false;
		}
	}

	/** Swaps a code or refresh token for an access token; null (with a status) if it failed. */
	private static JsonObject tokenRequest(String form) {
		try {
			HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
				.timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			if (response.statusCode() != 200) {
				status = "Spotify said: " + (json.has("error_description") ? json.get("error_description").getAsString() : string(json, "error"));
				// A refresh token that's no good any more means signing in again
				if ("invalid_grant".equals(string(json, "error"))) {
					refreshToken = "";
					save();
				}
				return null;
			}
			accessToken = json.get("access_token").getAsString();
			expiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000 - 30_000;
			if (json.has("refresh_token")) {
				refreshToken = json.get("refresh_token").getAsString();
				save();
			}
			return json;
		} catch (Exception e) {
			status = "Couldn't reach Spotify";
			FemboyWare.LOGGER.debug("Spotify token request failed", e);
			return null;
		}
	}

	private static boolean ensureToken() {
		if (!accessToken.isBlank() && System.currentTimeMillis() < expiresAt) return true;
		if (refreshToken.isBlank()) return false;
		return tokenRequest("grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + clientId) != null;
	}

	// ---------------------------------------------------------------- the library

	/** Loads (or reloads) your playlists in the background. */
	public static void refreshPlaylists() {
		load();
		if (!signedIn()) return;
		busy = true;
		WORKER.submit(() -> {
			try {
				if (!ensureToken()) return;
				if (userId.isBlank()) {
					JsonObject me = get("https://api.spotify.com/v1/me");
					if (me != null) userId = string(me, "id");
				}
				List<Playlist> list = new ArrayList<>();
				JsonObject liked = get("https://api.spotify.com/v1/me/tracks?limit=1");
				int likedCount = liked != null && liked.has("total") ? liked.get("total").getAsInt() : 0;
				list.add(new Playlist(LIKED, "Liked Songs", "spotify:user:" + userId + ":collection", "You", likedCount, null));
				String next = "https://api.spotify.com/v1/me/playlists?limit=50";
				for (int page = 0; next != null && page < 4; page++) {
					JsonObject json = get(next);
					if (json == null) break;
					for (JsonElement element : array(json, "items")) {
						if (!element.isJsonObject()) continue;
						JsonObject p = element.getAsJsonObject();
						JsonObject owner = p.has("owner") && p.get("owner").isJsonObject() ? p.getAsJsonObject("owner") : new JsonObject();
						JsonObject tracks = p.has("tracks") && p.get("tracks").isJsonObject() ? p.getAsJsonObject("tracks")
							: p.has("items") && p.get("items").isJsonObject() ? p.getAsJsonObject("items") : new JsonObject();
						list.add(new Playlist(string(p, "id"), string(p, "name"), string(p, "uri"), string(owner, "display_name"),
							tracks.has("total") ? tracks.get("total").getAsInt() : 0, image(p)));
					}
					next = json.has("next") && !json.get("next").isJsonNull() ? json.get("next").getAsString() : null;
				}
				playlists = list;
			} finally {
				busy = false;
			}
		});
	}

	/** Loads a playlist's songs in the background (the first 100). */
	public static void loadSongs(Playlist playlist) {
		if (SONGS.containsKey(playlist.id())) return;
		WORKER.submit(() -> {
			if (!ensureToken() || SONGS.containsKey(playlist.id())) return;
			List<Song> songs = new ArrayList<>();
			boolean liked = playlist.id().equals(LIKED);
			// Newer apps read a playlist's songs from /items; older ones from /tracks
			String[] urls = liked ? new String[]{"https://api.spotify.com/v1/me/tracks?limit=50"}
				: new String[]{"https://api.spotify.com/v1/playlists/" + playlist.id() + "/items?limit=50", "https://api.spotify.com/v1/playlists/" + playlist.id() + "/tracks?limit=50"};
			for (String first : urls) {
				String next = first;
				for (int page = 0; next != null && page < 2; page++) {
					JsonObject json = get(next);
					if (json == null) break;
					for (JsonElement element : array(json, "items")) {
						if (!element.isJsonObject()) continue;
						JsonObject entry = element.getAsJsonObject();
						JsonElement inner = entry.has("track") ? entry.get("track") : entry.get("item");
						if (inner == null || !inner.isJsonObject()) continue;
						JsonObject track = inner.getAsJsonObject();
						if (string(track, "uri").isBlank()) continue;
						List<String> names = new ArrayList<>();
						for (JsonElement artist : array(track, "artists")) if (artist.isJsonObject()) names.add(string(artist.getAsJsonObject(), "name"));
						JsonObject album = track.has("album") && track.get("album").isJsonObject() ? track.getAsJsonObject("album") : new JsonObject();
						songs.add(new Song(string(track, "name"), String.join(", ", names), string(track, "uri"),
							track.has("duration_ms") ? track.get("duration_ms").getAsInt() : 0, smallImage(album)));
					}
					next = json.has("next") && !json.get("next").isJsonNull() ? json.get("next").getAsString() : null;
				}
				if (!songs.isEmpty()) break;
			}
			SONGS.put(playlist.id(), songs);
		});
	}

	/**
	 * Plays a playlist on this PC's Spotify, from {@code songUri} if given. Starting playback from
	 * outside the app needs Premium; without it the playlist opens in the Spotify app instead.
	 */
	public static void play(Playlist playlist, String songUri) {
		WORKER.submit(() -> {
			if (!ensureToken()) return;
			JsonObject body = new JsonObject();
			body.addProperty("context_uri", playlist.uri());
			if (songUri != null) {
				JsonObject offset = new JsonObject();
				offset.addProperty("uri", songUri);
				body.add("offset", offset);
			}
			int code = put("https://api.spotify.com/v1/me/player/play", body.toString());
			if (code == 404) {
				// No active device: pick this computer's Spotify and try again there
				String device = computerDevice();
				if (device != null) code = put("https://api.spotify.com/v1/me/player/play?device_id=" + device, body.toString());
				else {
					status = "Open Spotify on this PC first";
					Spotify.launch();
					return;
				}
			}
			if (code == 403) {
				status = "Playing from the phone needs Premium; opened it in Spotify";
				openUri(playlist.id().equals(LIKED) ? "spotify:collection:tracks" : playlist.uri());
			} else if (code >= 300) status = "Spotify couldn't play that (" + code + ")";
		});
	}

	private static String computerDevice() {
		JsonObject json = get("https://api.spotify.com/v1/me/player/devices");
		if (json == null) return null;
		String fallback = null;
		for (JsonElement element : array(json, "devices")) {
			if (!element.isJsonObject()) continue;
			JsonObject device = element.getAsJsonObject();
			if ("Computer".equalsIgnoreCase(string(device, "type"))) return string(device, "id");
			if (fallback == null) fallback = string(device, "id");
		}
		return fallback;
	}

	/** Opens a spotify: link in the Spotify app. */
	private static void openUri(String uri) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (os.startsWith("mac")) new ProcessBuilder("open", uri).start();
			else if (os.startsWith("windows")) new ProcessBuilder("cmd", "/c", "start", "", uri).start();
		} catch (IOException e) {
			FemboyWare.LOGGER.debug("Couldn't open {}", uri, e);
		}
	}

	/** Opens Spotify's developer dashboard, where the app (and its Client ID) is made. */
	public static void openDashboard() {
		try {
			openInBrowser("https://developer.spotify.com/dashboard");
		} catch (IOException e) {
			status = "Go to developer.spotify.com/dashboard";
		}
	}

	private static void openInBrowser(String url) throws IOException {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.startsWith("mac")) new ProcessBuilder("open", url).start();
		else if (os.startsWith("windows")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
		else new ProcessBuilder("xdg-open", url).start();
	}

	// ---------------------------------------------------------------- covers

	private record Cover(Identifier id, int size) {}

	private static final Map<String, Object> COVERS = new ConcurrentHashMap<>();
	private static int nextCover;

	/**
	 * A cover image ready to draw, or null while it loads (or if there isn't one). Render thread
	 * only; the download happens in the background.
	 */
	public static Identifier cover(String url) {
		if (url == null || url.isBlank()) return null;
		Object state = COVERS.get(url);
		if (state instanceof Cover cover) return cover.id;
		if (state instanceof WindowsMedia.Art art) {
			// Downloaded: put it on the GPU now we're on the render thread
			try {
				NativeImage image = new NativeImage(art.width(), art.height(), false);
				for (int y = 0; y < art.height(); y++) for (int x = 0; x < art.width(); x++) image.setPixel(x, y, art.pixels()[y * art.width() + x]);
				Identifier id = Identifier.fromNamespaceAndPath("femboyware", "spotify_cover_" + nextCover++);
				DynamicTexture texture = new DynamicTexture(() -> "femboyware spotify cover", image);
				Minecraft.getInstance().getTextureManager().register(id, texture);
				texture.upload();
				COVERS.put(url, new Cover(id, art.width()));
				return id;
			} catch (Exception e) {
				COVERS.put(url, Boolean.FALSE);
				return null;
			}
		}
		if (state == null) {
			COVERS.put(url, Boolean.TRUE);
			Thread.ofVirtual().start(() -> {
				try {
					HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
					WindowsMedia.Art art = response.statusCode() == 200 ? WindowsMedia.decode(response.body(), 96) : null;
					COVERS.put(url, art != null ? art : Boolean.FALSE);
				} catch (Exception e) {
					COVERS.put(url, Boolean.FALSE);
				}
			});
		}
		return null;
	}

	/** The pixel size of a cover that's ready, for drawing it. */
	public static int coverSize(String url) {
		return COVERS.get(url) instanceof Cover cover ? cover.size : 96;
	}

	// ---------------------------------------------------------------- HTTP

	private static JsonObject get(String url) {
		try {
			HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 401 && refreshAgain()) return get(url);
			if (response.statusCode() != 200) {
				status = "Spotify said " + response.statusCode();
				return null;
			}
			JsonElement json = JsonParser.parseString(response.body());
			return json.isJsonObject() ? json.getAsJsonObject() : null;
		} catch (Exception e) {
			status = "Couldn't reach Spotify";
			return null;
		}
	}

	private static int put(String url, String body) {
		try {
			HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 401 && refreshAgain()) return put(url, body);
			return response.statusCode();
		} catch (Exception e) {
			status = "Couldn't reach Spotify";
			return 599;
		}
	}

	/** After a 401: the token's gone stale early, so get a fresh one (once). */
	private static boolean refreshAgain() {
		if (expiresAt == 0) return false;
		expiresAt = 0;
		accessToken = "";
		return ensureToken();
	}

	private static String enc(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> out = new java.util.HashMap<>();
		if (raw == null) return out;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
	}

	private static String string(JsonObject json, String key) {
		return json != null && json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
	}

	private static JsonArray array(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
	}

	/** A playlist's cover: the smallest one that's still big enough. */
	private static String image(JsonObject json) {
		JsonArray images = array(json, "images");
		String best = null;
		for (JsonElement element : images) {
			if (!element.isJsonObject()) continue;
			String url = string(element.getAsJsonObject(), "url");
			int width = element.getAsJsonObject().has("width") && !element.getAsJsonObject().get("width").isJsonNull() ? element.getAsJsonObject().get("width").getAsInt() : 0;
			if (best == null || width >= 100) best = url;
		}
		return best;
	}

	private static String smallImage(JsonObject album) {
		JsonArray images = array(album, "images");
		return images.isEmpty() || !images.get(images.size() - 1).isJsonObject() ? null : string(images.get(images.size() - 1).getAsJsonObject(), "url");
	}
}
