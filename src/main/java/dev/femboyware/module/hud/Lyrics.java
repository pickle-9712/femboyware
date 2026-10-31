package dev.femboyware.module.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics for whatever's playing, from several places at once, since each has songs the others lack:
 * LRCLIB (a free public database), and NetEase and QQ Music (large music services whose apps use these
 * unofficial, keyless endpoints). They all give timed lyrics, and every result is checked against the
 * song's title, artist and length, as searches often return something else entirely. If none of them
 * has timed lyrics, plain lyrics (from LRCLIB or Genius) are spread across the song as a rough guess.
 *
 * <p>Songs found are saved to femboyware/lyrics, so they appear at once next time. A confident match
 * finishes straight away, and a slow or failing source holds nothing up. None of this blocks a frame.
 */
final class Lyrics {
	enum Status { LOADING, SYNCED, ESTIMATED, NOT_FOUND, FAILED }

	record Line(double time, String text) {}

	record Song(Status status, List<Line> lines, long retryAt) {}

	private static final String LRCLIB = "https://lrclib.net/api/";
	private static final String NETEASE = "https://music.163.com/";
	private static final String QQ = "https://y.qq.com/";
	private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)]");
	/** Things players add to titles that lyric databases don't: "- Remastered 2011", "(feat. Someone)", "[Official Video]". */
	private static final Pattern TITLE_EXTRAS = Pattern.compile("\\s+-\\s+[^-]*(remaster|version|edit|mix|live|mono|stereo|deluxe|slowed|sped up|reverb|nightcore)[^-]*$"
		+ "|\\s*[(\\[](feat|ft|with|prod|remaster|official|lyric|audio|video|hd|hq|4k|explicit|slowed|sped)[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE);
	/** Words marking a different recording, whose timing wouldn't match unless the song playing is one too. */
	private static final Pattern OTHER_VERSION = Pattern.compile("remix|live|slowed|sped|reverb|nightcore|karaoke|cover|acoustic|instrumental|translation|перевод|romanized", Pattern.CASE_INSENSITIVE);
	/** Credit lines the Chinese services put among the lyrics: "作词 : Someone". */
	private static final Pattern CREDIT = Pattern.compile("^\\s*(作词|作曲|编曲|制作人|词|曲|混音|母带|录音|监制|出品|Lyricist|Composer|Lyrics by|Written by|Producer|Arranger)\\s*[:：].*$", Pattern.CASE_INSENSITIVE);
	/** Something shaped like a credit, "Vocal:Drake" or "Synths：David Stewart". Only trusted at the very start and end, as real lyrics can have colons. */
	private static final Pattern CREDIT_SHAPE = Pattern.compile("^\\s*[\\p{L}&/ ]{1,24}\\s*[:：].*$");
	/** How many real lines at each end of a song to look among for credits. */
	private static final int CREDIT_LINES = 12;
	private static final Pattern GENIUS_SECTION = Pattern.compile("^\\[[^\\]]*]$");
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final Pattern HTML_CODE = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
	/** Match scores: at least this finishes the lookup without waiting for the other sources. */
	private static final int CONFIDENT = 30;
	/** Lyrics are for a different recording if its length is further off than this, in seconds. */
	private static final double MOST_GAP = 10;
	/** Song length to assume when spreading plain lyrics over a song whose player doesn't report one. */
	private static final double USUAL_LENGTH = 200;
	/** Whether to ask NetEase, QQ Music and Genius as well as LRCLIB; follows the Lyrics module's setting. */
	static volatile boolean extraSources = true;
	/** The first retry after a failed lookup, in milliseconds; each one after waits twice as long, up to the limit. */
	private static final long RETRY_FIRST = 2_000;
	private static final long RETRY_MOST = 30_000;
	/** After this many failed lookups, stop asking until the game restarts, in case one source is just down for a while. */
	private static final int MOST_RETRIES = 3;
	private static final Path SAVED = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("lyrics");
	private static final int REMEMBERED = 64;

	private static final Map<String, Song> SONGS = new LinkedHashMap<>(REMEMBERED, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Song> eldest) {
			return size() > REMEMBERED;
		}
	};
	/** Failed lookups in a row for each song, for spacing out retries. */
	private static final Map<String, Integer> FAILURES = new HashMap<>();
	private static final ExecutorService WORKER = Executors.newCachedThreadPool(task -> {
		Thread thread = new Thread(task, "femboyware lyrics");
		thread.setDaemon(true);
		return thread;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder().executor(WORKER).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();

	private Lyrics() {
	}

	/** The lyrics for {@code track} as far as they're known; starts looking them up if they aren't. */
	static synchronized Song get(WindowsMedia.Track track) {
		String key = track.title() + "|" + track.artist();
		Song song = SONGS.get(key);
		if (song != null && (song.status != Status.FAILED || System.currentTimeMillis() < song.retryAt)) return song;
		Song loading = new Song(Status.LOADING, List.of(), 0);
		SONGS.put(key, song != null ? new Song(Status.FAILED, List.of(), Long.MAX_VALUE) : loading);
		CompletableFuture.supplyAsync(() -> load(key), WORKER)
			.thenCompose(saved -> saved != null ? CompletableFuture.completedFuture(saved) : fetch(track))
			.exceptionally(error -> {
				FemboyWare.LOGGER.debug("Couldn't get lyrics", error);
				return new Song(Status.FAILED, List.of(), 0);
			})
			.thenAccept(found -> finish(key, found));
		return loading;
	}

	/** Which line is being sung at {@code time}, or -1 before the first. */
	static int indexAt(List<Line> lines, double time) {
		int low = 0, high = lines.size() - 1, best = -1;
		while (low <= high) {
			int middle = (low + high) >>> 1;
			if (lines.get(middle).time <= time) {
				best = middle;
				low = middle + 1;
			} else high = middle - 1;
		}
		return best;
	}

	/** When the line after {@code index} starts, or a few seconds on if it's the last. */
	static double lineEnd(List<Line> lines, int index) {
		return index + 1 < lines.size() ? lines.get(index + 1).time : lines.get(index).time + 5;
	}

	/**
	 * When each word of a line starts, plus when the last ends. Lyrics are only timed by the line, so
	 * this is an estimate: the line's time is shared out by word length, and a line followed by a long
	 * gap doesn't drag its words out across the gap.
	 */
	static double[] wordTimes(String[] words, double start, double end) {
		int letters = 0;
		for (String word : words) letters += word.length() + 1;
		double sung = Math.min(end - start, 0.3 + letters * 0.09);
		double[] times = new double[words.length + 1];
		double elapsed = 0;
		for (int i = 0; i < words.length; i++) {
			times[i] = start + elapsed;
			elapsed += sung * (words[i].length() + 1) / Math.max(1, letters);
		}
		times[words.length] = start + sung;
		return times;
	}

	static String[] words(String text) {
		String trimmed = text.trim();
		return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
	}

	private static synchronized void finish(String key, Song found) {
		if (found.status == Status.FAILED) {
			int failures = FAILURES.merge(key, 1, Integer::sum);
			long wait = Math.min(RETRY_MOST, RETRY_FIRST << Math.min(failures - 1, 8));
			found = failures >= MOST_RETRIES ? new Song(Status.NOT_FOUND, List.of(), 0) : new Song(Status.FAILED, List.of(), System.currentTimeMillis() + wait);
		} else FAILURES.remove(key);
		SONGS.put(key, found);
		if (found.status == Status.SYNCED) save(key, found.lines);
	}

	/** What's being looked for, tidied up. */
	private record Query(String title, String artist, double length, boolean otherVersion, boolean titleOnly) {
		String words() {
			return titleOnly ? title : (title + " " + artist).trim();
		}
	}

	private static Query query(WindowsMedia.Track track) {
		String title = TITLE_EXTRAS.matcher(track.title()).replaceAll("").trim();
		String artist = track.artist().replaceAll("(?i)\\s+-\\s+Topic$|VEVO$", "").split(",|&|;|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+")[0].trim();
		// Videos in a browser are often titled "Artist - Song", with the uploader as the artist
		int dash = title.indexOf(" - ");
		if (dash > 0) {
			artist = title.substring(0, dash).trim();
			title = title.substring(dash + 3).trim();
		}
		return new Query(title, artist, track.length(), OTHER_VERSION.matcher(track.title()).find(), false);
	}

	/** One source's answer: its best timed lines and how well they match, any untimed words, and whether it failed. */
	private record Found(List<Line> lines, int score, String plain, boolean failed, String source) {
		static final Found NOTHING = new Found(null, 0, null, false, "");
		static final Found FAILED = new Found(null, 0, null, true, "");
		/** The source wasn't asked, so its silence says nothing. */
		static final Found SKIPPED = new Found(null, 0, null, false, "");
	}

	private static CompletableFuture<Song> fetch(WindowsMedia.Track track) {
		CompletableFuture<Song> result = new CompletableFuture<>();
		search(query(track), track, null, false, false, result);
		return result;
	}

	/**
	 * Asks every source at once. A confident match finishes straight away; otherwise the best of all
	 * their answers wins. If nobody has it, searches again by title alone, as adding the artist can hide
	 * a song when it's spelled differently; the results still have to match the artist.
	 */
	private static void search(Query query, WindowsMedia.Track track, String plainSoFar, boolean failedSoFar, boolean answeredSoFar, CompletableFuture<Song> result) {
		List<CompletableFuture<Found>> lookups = new ArrayList<>();
		if (!query.titleOnly) lookups.add(lrclibExact(query));
		lookups.add(lrclibSearch(query));
		if (extraSources) {
			lookups.add(netease(query));
			lookups.add(qq(query));
		}
		for (CompletableFuture<Found> lookup : lookups) {
			lookup.thenAccept(found -> {
				if (found.lines != null && found.score >= CONFIDENT && result.complete(new Song(Status.SYNCED, found.lines, 0))) {
					FemboyWare.LOGGER.info("Lyrics for {} from {}", track.title(), found.source);
				}
			});
		}
		CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new)).thenRun(() -> {
			if (result.isDone()) return;
			Found best = Found.NOTHING;
			String plain = plainSoFar;
			boolean failed = failedSoFar;
			boolean answered = answeredSoFar;
			for (CompletableFuture<Found> lookup : lookups) {
				Found found = lookup.join();
				if (found.lines != null && (best.lines == null || found.score > best.score)) best = found;
				if (plain == null) plain = found.plain;
				failed |= found.failed;
				answered |= !found.failed && found != Found.SKIPPED;
			}
			if (best.lines != null) {
				FemboyWare.LOGGER.info("Lyrics for {} from {}", track.title(), best.source);
				result.complete(new Song(Status.SYNCED, best.lines, 0));
				return;
			}
			if (!query.titleOnly && !query.artist.isEmpty()) {
				search(new Query(query.title, query.artist, query.length, query.otherVersion, true), track, plain, failed, answered, result);
			} else finishPlain(query, plain, failed && !answered, result);
		});
	}

	/** No timed lyrics anywhere: spread plain ones over the song, fetching them from Genius if need be. {@code failed} means no source could be reached at all. */
	private static void finishPlain(Query query, String plain, boolean failed, CompletableFuture<Song> result) {
		CompletableFuture<Found> words = plain != null || !extraSources
			? CompletableFuture.completedFuture(new Found(null, 0, plain, false, "LRCLIB"))
			: genius(query);
		words.thenAccept(found -> {
			List<Line> lines = found.plain == null ? List.of() : spread(found.plain, query.length);
			if (believable(lines)) {
				FemboyWare.LOGGER.info("Plain lyrics for {} from {}, with guessed timing", query.title, found.source);
				result.complete(new Song(Status.ESTIMATED, lines, 0));
			} else result.complete(new Song(failed ? Status.FAILED : Status.NOT_FOUND, List.of(), 0));
		});
	}

	/** Shares a song's length out between its lines by how long they are, leaving a little for an intro and an ending. */
	private static List<Line> spread(String plain, double length) {
		double song = length > 0 ? length : USUAL_LENGTH;
		List<String> rows = new ArrayList<>();
		int weight = 0;
		for (String row : plain.split("\\R")) {
			String words = row.trim();
			if (words.isEmpty() || GENIUS_SECTION.matcher(words).matches()) continue;
			rows.add(words);
			weight += words.length() + 10;
		}
		List<Line> lines = new ArrayList<>();
		double time = Math.min(12, song * 0.06);
		double singing = song * 0.96 - time;
		for (String row : rows) {
			lines.add(new Line(time, row));
			time += singing * (row.length() + 10) / Math.max(1, weight);
		}
		return lines;
	}

	/** How well a result fits, higher is better; negative if it's the wrong song or a different recording. */
	private static int score(Query query, String title, List<String> artists, double length) {
		String wanted = tidy(query.title), got = tidy(title);
		if (got.isEmpty() || wanted.isEmpty()) return -1;
		boolean exact = wanted.equals(got);
		if (!exact && !(Math.min(wanted.length(), got.length()) >= 4 && (wanted.contains(got) || got.contains(wanted)))) return -1;
		double gap = query.length > 0 && length > 0 ? Math.abs(query.length - length) : -1;
		if (gap > MOST_GAP) return -1;
		String artist = tidy(query.artist);
		boolean sameArtist = artist.isEmpty();
		for (String name : artists) {
			String other = tidy(name);
			sameArtist |= !other.isEmpty() && (other.contains(artist) || artist.contains(other));
		}
		// Many songs share a title and even a length, so wrong words are far likelier than a respelled artist
		if (!sameArtist) return -1;
		int score = (exact ? 15 : 5) + 10 + (gap < 0 ? 8 : gap <= 2 ? 15 : gap <= 5 ? 8 : 0);
		if (!query.otherVersion && OTHER_VERSION.matcher(title).find()) score -= 12;
		return Math.max(score, 0);
	}

	/** Lower case with accents, anything in brackets and punctuation gone, so spellings compare equal. */
	private static String tidy(String text) {
		String plain = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
		return plain.toLowerCase(Locale.ROOT).replaceAll("[(\\[][^)\\]]*[)\\]]", " ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
	}

	/** Timed lyrics text as lines, if there are believably enough of them once credits are taken out. */
	private static List<Line> usable(String lrc, Query query) {
		List<Line> lines = new ArrayList<>();
		for (Line line : parse(lrc)) {
			if (CREDIT.matcher(line.text).matches()) continue;
			// QQ opens with "Song - Artist" at the very start
			if (line.time < 1 && lines.isEmpty() && line.text.contains(" - ") && tidy(line.text).contains(tidy(query.title))) continue;
			lines.add(line);
		}
		// Credits can run long and sit among a song's first and last few real lines, so only real lines count towards the limit
		int kept = 0;
		for (int i = 0; i < lines.size() && kept < CREDIT_LINES; ) {
			if (CREDIT_SHAPE.matcher(lines.get(i).text).matches()) lines.remove(i);
			else {
				i++;
				kept++;
			}
		}
		kept = 0;
		for (int i = lines.size() - 1; i >= 0 && kept < CREDIT_LINES; i--) {
			if (CREDIT_SHAPE.matcher(lines.get(i).text).matches()) lines.remove(i);
			else kept++;
		}
		return believable(lines) ? lines : null;
	}

	private static CompletableFuture<Found> lrclibExact(Query query) {
		if (query.length <= 0 || query.artist.isEmpty()) return CompletableFuture.completedFuture(Found.SKIPPED);
		return request(LRCLIB + "get?artist_name=" + encode(query.artist) + "&track_name=" + encode(query.title) + "&duration=" + Math.round(query.length), LRCLIB).thenApply(response -> {
			if (response.statusCode() == 404) return Found.NOTHING;
			if (response.statusCode() != 200) return Found.FAILED;
			JsonObject match = JsonParser.parseString(response.body()).getAsJsonObject();
			List<Line> lines = usable(text(match, "syncedLyrics"), query);
			return new Found(lines, CONFIDENT, text(match, "plainLyrics"), false, "LRCLIB");
		}).exceptionally(error -> Found.FAILED);
	}

	private static CompletableFuture<Found> lrclibSearch(Query query) {
		return request(LRCLIB + "search?q=" + encode(query.words()), LRCLIB).thenApply(response -> {
			if (response.statusCode() != 200) return Found.FAILED;
			List<Line> best = null;
			int bestScore = -1;
			String plain = null;
			for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
				JsonObject result = element.getAsJsonObject();
				double length = result.has("duration") && !result.get("duration").isJsonNull() ? result.get("duration").getAsDouble() : 0;
				int score = score(query, String.valueOf(text(result, "trackName")), List.of(String.valueOf(text(result, "artistName"))), length);
				if (score < 0) continue;
				if (plain == null) plain = text(result, "plainLyrics");
				List<Line> lines = usable(text(result, "syncedLyrics"), query);
				if (lines != null && score > bestScore) {
					bestScore = score;
					best = lines;
				}
			}
			return new Found(best, bestScore, plain, false, "LRCLIB");
		}).exceptionally(error -> Found.FAILED);
	}

	/** One song a search returned: how well it fits and how to ask for its lyrics. */
	private record Candidate(int score, String id) {}

	/** The best few of a search's results, as lyrics are sometimes missing from the one that fits best. */
	private static CompletableFuture<Found> firstLyrics(List<Candidate> candidates, int index, Function<String, CompletableFuture<String>> lyrics, Query query, String source) {
		if (index == 0) candidates.sort(Comparator.comparingInt(Candidate::score).reversed());
		if (index >= Math.min(3, candidates.size())) return CompletableFuture.completedFuture(Found.NOTHING);
		Candidate candidate = candidates.get(index);
		return lyrics.apply(candidate.id).thenCompose(lrc -> {
			List<Line> lines = usable(lrc, query);
			return lines != null ? CompletableFuture.completedFuture(new Found(lines, candidate.score, null, false, source))
				: firstLyrics(candidates, index + 1, lyrics, query, source);
		});
	}

	private static CompletableFuture<Found> netease(Query query) {
		return request(NETEASE + "api/search/get?type=1&limit=8&s=" + encode(query.words()), NETEASE).thenCompose(response -> {
			if (response.statusCode() != 200) return CompletableFuture.completedFuture(Found.FAILED);
			JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
			List<Candidate> candidates = new ArrayList<>();
			if (result != null && result.has("songs")) {
				for (JsonElement element : result.getAsJsonArray("songs")) {
					JsonObject song = element.getAsJsonObject();
					List<String> artists = new ArrayList<>();
					for (JsonElement artist : song.getAsJsonArray("artists")) {
						artists.add(String.valueOf(text(artist.getAsJsonObject(), "name")));
						// Their other names, like the English spelling of 米津玄師
						if (artist.getAsJsonObject().get("alias") instanceof JsonArray aliases) {
							for (JsonElement alias : aliases) artists.add(alias.getAsString());
						}
					}
					int score = score(query, String.valueOf(text(song, "name")), artists, song.get("duration").getAsDouble() / 1000);
					if (score >= 0) candidates.add(new Candidate(score, song.get("id").getAsString()));
				}
			}
			return firstLyrics(candidates, 0, id -> request(NETEASE + "api/song/lyric?lv=1&id=" + id, NETEASE).thenApply(lyric -> {
				if (lyric.statusCode() != 200) return null;
				JsonObject lrc = JsonParser.parseString(lyric.body()).getAsJsonObject().getAsJsonObject("lrc");
				return lrc == null ? null : text(lrc, "lyric");
			}), query, "NetEase");
		}).exceptionally(error -> Found.FAILED);
	}

	private static CompletableFuture<Found> qq(Query query) {
		return request("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=8&w=" + encode(query.words()), QQ).thenCompose(response -> {
			if (response.statusCode() != 200) return CompletableFuture.completedFuture(Found.FAILED);
			List<Candidate> candidates = new ArrayList<>();
			JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("data");
			for (JsonElement element : data.getAsJsonObject("song").getAsJsonArray("list")) {
				JsonObject song = element.getAsJsonObject();
				List<String> artists = new ArrayList<>();
				for (JsonElement artist : song.getAsJsonArray("singer")) artists.add(String.valueOf(text(artist.getAsJsonObject(), "name")));
				int score = score(query, String.valueOf(text(song, "songname")), artists, song.get("interval").getAsDouble());
				if (score >= 0) candidates.add(new Candidate(score, text(song, "songmid")));
			}
			return firstLyrics(candidates, 0, id -> request("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid=" + encode(id), QQ).thenApply(lyric -> {
				if (lyric.statusCode() != 200) return null;
				return unescape(text(JsonParser.parseString(lyric.body()).getAsJsonObject(), "lyric"));
			}), query, "QQ Music");
		}).exceptionally(error -> Found.FAILED);
	}

	/** Genius has words for nearly everything, untimed, and no lyrics in its data, so they're read from the song's page. */
	private static CompletableFuture<Found> genius(Query query) {
		return request("https://genius.com/api/search/song?per_page=5&q=" + encode(query.words()), "https://genius.com/").thenCompose(response -> {
			if (response.statusCode() != 200) return CompletableFuture.completedFuture(Found.FAILED);
			JsonObject first = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("response").getAsJsonArray("sections").get(0).getAsJsonObject();
			String url = null;
			int bestScore = -1;
			for (JsonElement element : first.getAsJsonArray("hits")) {
				JsonObject hit = element.getAsJsonObject().getAsJsonObject("result");
				int score = score(query, String.valueOf(text(hit, "title")), List.of(String.valueOf(text(hit.getAsJsonObject("primary_artist"), "name"))), 0);
				String page = text(hit, "url");
				if (score > bestScore && page != null && page.startsWith("https://genius.com/")) {
					bestScore = score;
					url = page;
				}
			}
			if (url == null) return CompletableFuture.completedFuture(Found.NOTHING);
			return request(url, "https://genius.com/").thenApply(page -> page.statusCode() == 200 ? new Found(null, 0, geniusWords(page.body()), false, "Genius") : Found.FAILED);
		}).exceptionally(error -> Found.FAILED);
	}

	/** The words from a Genius page: the text of its lyrics containers, without the page furniture inside them. */
	private static String geniusWords(String html) {
		StringBuilder words = new StringBuilder();
		int from = 0;
		while (true) {
			int mark = html.indexOf("data-lyrics-container=\"true\"", from);
			if (mark < 0) break;
			int open = html.lastIndexOf("<div", mark);
			int end = divEnd(html, open);
			String part = html.substring(html.indexOf('>', mark) + 1, Math.max(html.indexOf('>', mark) + 1, end - 6));
			int skip;
			while ((skip = part.indexOf("data-exclude-from-selection=\"true\"")) >= 0) {
				int start = part.lastIndexOf("<div", skip);
				part = part.substring(0, start) + part.substring(divEnd(part, start));
			}
			words.append(part.replaceAll("(?i)<br\\s*/?>", "\n")).append('\n');
			from = end;
		}
		String text = unescape(HTML_TAG.matcher(words).replaceAll(""));
		return text == null || text.isBlank() ? null : text;
	}

	/** Where the {@code <div>} opening at {@code start} closes, just past its end tag. */
	private static int divEnd(String html, int start) {
		int depth = 0;
		int at = start;
		while (at >= 0 && at < html.length()) {
			int open = html.indexOf("<div", at), close = html.indexOf("</div>", at);
			if (close < 0) return html.length();
			if (open >= 0 && open < close) {
				depth++;
				at = open + 4;
			} else {
				depth--;
				at = close + 6;
				if (depth == 0) return at;
			}
		}
		return html.length();
	}

	private static String unescape(String text) {
		if (text == null) return null;
		String named = text.replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
		Matcher codes = HTML_CODE.matcher(named);
		String decoded = codes.replaceAll(match -> Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(match.group(2), match.group(1).isEmpty() ? 10 : 16)))));
		return decoded.replace("&amp;", "&");
	}

	private static Path savedFile(String key) {
		return SAVED.resolve(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".txt");
	}

	/** A song saved earlier, or null. */
	private static Song load(String key) {
		try {
			Path file = savedFile(key);
			if (!Files.exists(file)) return null;
			List<Line> lines = new ArrayList<>();
			for (String row : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				int tab = row.indexOf('\t');
				if (tab > 0) lines.add(new Line(Double.parseDouble(row.substring(0, tab)), row.substring(tab + 1)));
			}
			return believable(lines) ? new Song(Status.SYNCED, lines, 0) : null;
		} catch (Exception e) {
			FemboyWare.LOGGER.debug("Couldn't read saved lyrics", e);
			return null;
		}
	}

	private static void save(String key, List<Line> lines) {
		WORKER.execute(() -> {
			try {
				Files.createDirectories(SAVED);
				List<String> rows = new ArrayList<>();
				for (Line line : lines) rows.add(line.time + "\t" + line.text);
				Files.write(savedFile(key), rows, StandardCharsets.UTF_8);
			} catch (Exception e) {
				FemboyWare.LOGGER.debug("Couldn't save lyrics", e);
			}
		});
	}

	/** Anyone can add to the database, and some entries are junk like a single test line; a real song has a few lines. */
	private static boolean believable(List<Line> lines) {
		return lines.stream().filter(line -> !line.text().isEmpty()).count() >= 4;
	}

	private static CompletableFuture<HttpResponse<String>> request(String url, String referer) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(6))
			.header("User-Agent", "Mozilla/5.0 femboyware/" + FemboyWare.VERSION)
			.header("Referer", referer)
			.GET().build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	private static String text(JsonObject object, String name) {
		JsonElement element = object.get(name);
		if (element == null || element.isJsonNull()) return null;
		String text = element.getAsString();
		return text.isBlank() ? null : text;
	}

	/** Reads LRC text: "[01:23.45] words". Empty lines stay, as gaps where nothing is sung. */
	private static List<Line> parse(String lrc) {
		List<Line> lines = new ArrayList<>();
		if (lrc == null) return lines;
		for (String row : lrc.split("\\R")) {
			Matcher matcher = TIME_TAG.matcher(row);
			int textStart = 0;
			List<Double> times = new ArrayList<>();
			while (matcher.find()) {
				times.add(Integer.parseInt(matcher.group(1)) * 60 + Double.parseDouble(matcher.group(2)));
				textStart = matcher.end();
			}
			String words = row.substring(textStart).trim();
			for (double time : times) lines.add(new Line(time, words));
		}
		lines.sort(Comparator.comparingDouble(Line::time));
		return lines;
	}
}
