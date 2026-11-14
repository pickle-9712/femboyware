package dev.femboyware.module.larp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * TikTok on the TV with no browser: the For You feed (or one video from a link) fetched straight
 * from TikTok's video servers through tikwm.com's public API, each clip downloaded as an ordinary
 * MP4 (H.264 and AAC) a couple ahead of the one you're watching. Pure Java, so it works the same on
 * Windows, Mac and Linux, and since the TV just plays files, Client Sync shares them like any video.
 * Nothing about you is sent: only which feed or link to fetch.
 */
public final class TikTokFeed {
	/** A clip in the feed: who posted it, what it says, and where its video is. */
	public record Clip(String id, String author, String title, String video, int seconds) {
		public String label() {
			String text = title == null || title.isBlank() ? "" : " · " + title.strip();
			String line = "@" + author + text;
			return line.length() > 70 ? line.substring(0, 69) + "…" : line;
		}
	}

	private static final String API = "https://www.tikwm.com/api/";
	private static final String AGENT = "Mozilla/5.0 (femboyware TV)";
	/** Longer clips (live replays, hour-long uploads) and bigger files are skipped. */
	private static final int MAX_SECONDS = 600;
	private static final long MAX_BYTES = 80_000_000L;
	/** Cached clips kept on disk; the oldest go once there are more. */
	private static final int KEEP = 40;
	/** The API allows about a request a second. */
	private static final long API_GAP = 1100;

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private final String link;
	private final List<Clip> clips = java.util.Collections.synchronizedList(new ArrayList<>());
	private final Set<String> seen = ConcurrentHashMap.newKeySet();
	private final Map<String, Path> files = new ConcurrentHashMap<>();
	private final Set<String> downloading = ConcurrentHashMap.newKeySet();
	private final ExecutorService feedThread = Executors.newSingleThreadExecutor(r -> daemon(r, "femboyware tiktok feed"));
	private final ExecutorService downloadThread = Executors.newFixedThreadPool(2, r -> daemon(r, "femboyware tiktok download"));
	private volatile boolean fetching, closed;
	private volatile String problem;
	private volatile int index;
	private static volatile long lastApi;

	/** {@code link} is a TikTok video link to play just that, or null for the For You feed. */
	public TikTokFeed(String link) {
		this.link = link;
		fetchMore();
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}

	public static Path cache() {
		return TvModule.folder().resolve("tiktok");
	}

	/** Why it isn't working, or null. */
	public String problem() {
		return problem;
	}

	public int index() {
		return index;
	}

	/** The clip being watched, or null while the feed's still loading. */
	public Clip current() {
		synchronized (clips) {
			return index < clips.size() ? clips.get(index) : null;
		}
	}

	/** The current clip's file once it's downloaded, or null; also keeps the next ones coming. */
	public Path currentFile() {
		Clip clip = current();
		prefetch();
		return clip == null ? null : files.get(clip.id);
	}

	/** Swipes to the next (1) or previous (-1) clip; false if there's nowhere to go yet. */
	public boolean move(int direction) {
		synchronized (clips) {
			int next = index + direction;
			if (next < 0) return false;
			if (next >= clips.size()) {
				fetchMore();
				return false;
			}
			index = next;
		}
		prefetch();
		return true;
	}

	public void close() {
		closed = true;
		feedThread.shutdownNow();
		downloadThread.shutdownNow();
	}

	// ---------------------------------------------------------------- fetching

	private void prefetch() {
		synchronized (clips) {
			for (int i = index; i < Math.min(clips.size(), index + 3); i++) download(clips.get(i));
			if (link == null && clips.size() - index < 4) fetchMore();
		}
	}

	private void fetchMore() {
		if (fetching || closed) return;
		if (link != null && !clips.isEmpty()) return;
		fetching = true;
		feedThread.execute(() -> {
			try {
				List<Clip> got = link != null ? List.of(one(link)) : feed();
				int added = 0;
				for (Clip clip : got) {
					if (clip != null && seen.add(clip.id)) {
						clips.add(clip);
						added++;
					}
				}
				if (added == 0 && clips.isEmpty()) problem = "TikTok sent nothing to play, try again in a bit";
				else problem = null;
				prefetch();
			} catch (Exception e) {
				FemboyWare.LOGGER.warn("TikTok feed failed", e);
				problem = link != null ? "couldn't load that TikTok (" + reason(e) + ")" : "couldn't reach TikTok (" + reason(e) + ")";
			} finally {
				fetching = false;
			}
		});
	}

	private static String reason(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.length() > 60 ? message.substring(0, 60) : message;
	}

	/** Some of the For You feed. */
	private static List<Clip> feed() throws IOException, InterruptedException {
		JsonObject json = api("feed/list?region=US&count=12");
		List<Clip> out = new ArrayList<>();
		JsonElement data = json.get("data");
		if (data != null && data.isJsonArray()) for (JsonElement e : (JsonArray) data) {
			Clip clip = clip(e);
			if (clip != null) out.add(clip);
		}
		return out;
	}

	/** One video from its link. */
	private static Clip one(String url) throws IOException, InterruptedException {
		Clip clip = clip(api("?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8) + "&hd=0").get("data"));
		if (clip == null) throw new IOException("it isn't a video");
		return clip;
	}

	private static JsonObject api(String path) throws IOException, InterruptedException {
		synchronized (TikTokFeed.class) {
			long wait = lastApi + API_GAP - System.currentTimeMillis();
			if (wait > 0) Thread.sleep(wait);
			lastApi = System.currentTimeMillis();
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(25))
			.header("User-Agent", AGENT).header("Accept", "application/json").GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
		JsonObject json;
		try {
			json = JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IOException("blocked for now");
		}
		if (!json.has("code") || json.get("code").getAsInt() != 0) {
			throw new IOException(json.has("msg") ? json.get("msg").getAsString() : "no answer");
		}
		return json;
	}

	/** A clip from the API's JSON, or null for photo posts, ads and anything too long. */
	private static Clip clip(JsonElement element) {
		if (element == null || !element.isJsonObject()) return null;
		JsonObject o = element.getAsJsonObject();
		if (o.has("images") && o.get("images").isJsonArray() && !o.getAsJsonArray("images").isEmpty()) return null;
		if (o.has("is_ad") && o.get("is_ad").isJsonPrimitive() && o.get("is_ad").getAsBoolean()) return null;
		String id = string(o, "video_id");
		if (id == null) id = string(o, "id");
		String play = string(o, "play");
		int seconds = o.has("duration") ? o.get("duration").getAsInt() : 0;
		long size = o.has("size") ? o.get("size").getAsLong() : 0;
		if (id == null || !id.matches("[0-9]{5,25}") || play == null || seconds <= 0 || seconds > MAX_SECONDS || size > MAX_BYTES) return null;
		if (play.startsWith("/")) play = "https://www.tikwm.com" + play;
		if (!play.startsWith("https://")) return null;
		String author = "tiktok";
		if (o.has("author") && o.get("author").isJsonObject()) {
			String name = string(o.getAsJsonObject("author"), "unique_id");
			if (name != null && name.matches("[A-Za-z0-9_.]{1,40}")) author = name;
		}
		String title = string(o, "title");
		if (title != null) title = dev.femboyware.manager.PhoneLink.clean(title);
		return new Clip(id, author, title, play, seconds);
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
	}

	private void download(Clip clip) {
		if (files.containsKey(clip.id) || closed) return;
		Path file = cache().resolve("@" + clip.author + " " + clip.id + ".mp4");
		if (Files.isRegularFile(file)) {
			files.put(clip.id, file);
			return;
		}
		if (!downloading.add(clip.id)) return;
		downloadThread.execute(() -> {
			Path part = cache().resolve(clip.id + ".part");
			try {
				Files.createDirectories(cache());
				HttpRequest request = HttpRequest.newBuilder(URI.create(clip.video)).timeout(Duration.ofSeconds(60))
					.header("User-Agent", "Mozilla/5.0").GET().build();
				HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(part));
				if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
				if (Files.size(part) > MAX_BYTES || Files.size(part) < 1000) throw new IOException("bad size");
				Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
				files.put(clip.id, file);
				tidy(file);
			} catch (Exception e) {
				if (!closed) FemboyWare.LOGGER.warn("Couldn't download TikTok {}", clip.id, e);
				try {
					Files.deleteIfExists(part);
				} catch (IOException ignored) {
				}
				// Skipped: the feed moves past it
				if (!closed) skip(clip);
			} finally {
				downloading.remove(clip.id);
			}
		});
	}

	/** A clip that wouldn't download comes out of the feed, so swiping doesn't stop on it. */
	private void skip(Clip clip) {
		synchronized (clips) {
			int at = clips.indexOf(clip);
			if (at < 0) return;
			clips.remove(at);
			if (at < index) index--;
			if (index >= clips.size() && index > 0 && link == null) fetchMore();
			if (clips.isEmpty() && link != null) problem = "couldn't download that TikTok";
		}
	}

	/** Keeps the cache to the newest few dozen clips. */
	private static void tidy(Path keep) {
		try (Stream<Path> stream = Files.list(cache())) {
			List<Path> all = new ArrayList<>(stream.filter(p -> p.getFileName().toString().endsWith(".mp4")).toList());
			if (all.size() <= KEEP) return;
			all.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
			Set<Path> spare = new HashSet<>(all.subList(0, all.size() - KEEP));
			spare.remove(keep);
			for (Path p : spare) Files.deleteIfExists(p);
		} catch (IOException ignored) {
		}
	}
}
