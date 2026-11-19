package dev.femboyware.module.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Tells you when DonutSMP staff go live on YouTube or TikTok (staff streaming usually means
 * they're busy, or watching), with a watch link in chat, a chime and a panel while they're live.
 * It only asks youtube.com and tiktok.com whether each handle is live: nothing about you is sent.
 * Ported from the Anubis client's Staff Live (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public class StaffLiveModule extends HudElement {
	private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
	private static final Pattern CHANNEL = Pattern.compile("^(yt|tt):@?([A-Za-z0-9._-]+)$", Pattern.CASE_INSENSITIVE);

	private final StringSetting channels = add(new StringSetting("Channels", "yt:handle or tt:handle, comma separated", "yt:wizoxlol, yt:pastagamer08, tt:pastagamer08, tt:theauzzi, tt:u_vv7_, yt:DrDonutt"));
	private final NumberSetting interval = add(new NumberSetting("Check Every", "Seconds between checks", 60, 15, 600, 5));
	private final BoolSetting chime = add(new BoolSetting("Chime", "Play a chime when someone goes live", true));
	private final BoolSetting background = add(new BoolSetting("Background", "A glass box behind it", true));

	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
	private final Map<String, Boolean> live = new ConcurrentHashMap<>();
	private final Map<String, Live> shown = new ConcurrentHashMap<>();
	private volatile Thread worker;

	private record Channel(boolean youtube, String handle) {
		String key() {
			return (youtube ? "yt:" : "tt:") + handle.toLowerCase(Locale.ROOT);
		}
	}

	private record Live(String who, String platform, String title, long since, long shownAt) {
	}

	private record Stream(boolean live, String title, String link, String nick, long started) {
		static final Stream OFFLINE = new Stream(false, null, null, null, 0);
	}

	public StaffLiveModule() {
		super("Staff Live", "Tells you when DonutSMP staff go live on YouTube or TikTok", 1, 300);
	}

	@Override
	protected void onEnable() {
		live.clear();
		shown.clear();
		Thread thread = new Thread(this::run, "femboyware Staff Live");
		thread.setDaemon(true);
		worker = thread;
		thread.start();
	}

	@Override
	protected void onDisable() {
		Thread thread = worker;
		worker = null;
		if (thread != null) thread.interrupt();
		shown.clear();
	}

	private void run() {
		long next = 0;
		while (worker == Thread.currentThread()) {
			if (System.currentTimeMillis() >= next) {
				Set<String> keys = new HashSet<>();
				for (Channel channel : channels()) {
					if (worker != Thread.currentThread()) return;
					keys.add(channel.key());
					check(channel);
				}
				live.keySet().retainAll(keys);
				shown.keySet().retainAll(keys);
				next = System.currentTimeMillis() + (long) (interval.get() * 1000);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private List<Channel> channels() {
		List<Channel> out = new ArrayList<>();
		for (String entry : channels.get().split(",")) {
			Matcher m = CHANNEL.matcher(entry.trim());
			if (m.find()) out.add(new Channel(m.group(1).equalsIgnoreCase("yt"), m.group(2)));
		}
		return out;
	}

	private void check(Channel channel) {
		Stream stream;
		try {
			stream = channel.youtube() ? youtube(channel.handle()) : tiktok(channel.handle());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		} catch (Exception e) {
			return;
		}
		if (stream == null) return;
		Boolean was = live.put(channel.key(), stream.live());
		if (stream.live() && !Boolean.TRUE.equals(was)) announce(channel, stream);
		if (!stream.live()) shown.remove(channel.key());
	}

	private Stream youtube(String handle) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.youtube.com/@" + URLEncoder.encode(handle, StandardCharsets.UTF_8) + "/live"))
			.timeout(Duration.ofSeconds(20)).header("User-Agent", BROWSER).header("Accept-Language", "en-US,en;q=0.9")
			.header("Accept-Encoding", "gzip").header("Cookie", "SOCS=CAI; CONSENT=YES+").build();
		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream raw = response.body()) {
			if (response.statusCode() != 200) return null;
			boolean gzip = response.headers().firstValue("Content-Encoding").map(v -> v.equalsIgnoreCase("gzip")).orElse(false);
			Reader in = new InputStreamReader(gzip ? new GZIPInputStream(raw) : raw, StandardCharsets.UTF_8);
			char[] buffer = new char[16384];
			StringBuilder window = new StringBuilder();
			String watch = null, title = null;
			boolean liveNow = false;
			int n;
			while ((n = in.read(buffer)) > 0) {
				window.append(buffer, 0, n);
				if (watch == null) {
					String href = after(window, "<link rel=\"canonical\" href=\"");
					if (href != null) {
						if (!href.contains("/watch?v=")) return Stream.OFFLINE;
						watch = href;
					}
				}
				if (watch != null && title == null) title = after(window, "<meta name=\"title\" content=\"");
				if (watch != null && !liveNow) liveNow = window.indexOf("\"isLiveNow\":true") >= 0;
				if (liveNow) {
					String start = after(window, "\"startTimestamp\":\"");
					if (start != null || window.length() > 4096) return new Stream(true, clean(title), watch, null, parseStart(start));
				} else if (window.length() > 4096) window.delete(0, window.length() - 512);
			}
			return liveNow ? new Stream(true, clean(title), watch, null, 0) : Stream.OFFLINE;
		}
	}

	private Stream tiktok(String user) throws Exception {
		String url = "https://www.tiktok.com/api-live/user/room/?aid=1988&app_language=en&app_name=tiktok_web&sourceType=54&uniqueId=" + URLEncoder.encode(user, StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", BROWSER).header("Accept", "application/json").build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) return null;
		JsonElement body = JsonParser.parseString(response.body());
		if (!body.isJsonObject()) return null;
		JsonObject data = object(body.getAsJsonObject(), "data");
		JsonObject room = object(data, "liveRoom");
		if (room == null || !room.has("status")) return null;
		boolean on = room.get("status").getAsInt() == 2;
		JsonObject owner = object(data, "user");
		String nick = owner != null && owner.has("nickname") ? owner.get("nickname").getAsString() : null;
		String title = room.has("title") ? room.get("title").getAsString() : null;
		long started = room.has("startTime") ? room.get("startTime").getAsLong() * 1000 : 0;
		return new Stream(on, clean(title), "https://www.tiktok.com/@" + user + "/live", nick, started);
	}

	private void announce(Channel channel, Stream stream) {
		String url = stream.link() != null ? stream.link() : "https://www.youtube.com/@" + channel.handle() + "/live";
		String who = stream.nick() != null && !stream.nick().equalsIgnoreCase(channel.handle()) ? channel.handle() + " (" + stream.nick() + ")" : channel.handle();
		String platform = channel.youtube() ? "YouTube" : "TikTok";
		long now = System.currentTimeMillis();
		long since = stream.started() > 0 && stream.started() <= now ? stream.started() : now;
		shown.putIfAbsent(channel.key(), new Live(who, platform, stream.title(), since, now));
		mc.execute(() -> {
			if (!isEnabled() || mc.player == null) return;
			String text = who + " is LIVE on " + platform + (stream.title() != null && !stream.title().isBlank() ? ": " + stream.title() : "") + " ";
			Mc.addChat(Component.literal("[Staff Live] ").withStyle(s -> s.withColor(Theme.chatPrefix() & 0xFFFFFF)).append(Component.literal(text))
				.append(Component.literal("[watch]").withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))).withUnderlined(true).withColor(0x55AAFF))));
			if (chime.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME, 1.25f));
		});
	}

	private List<Live> rows(boolean editing) {
		List<Live> rows = new ArrayList<>(shown.values());
		rows.sort(Comparator.comparingLong(Live::shownAt));
		if (rows.isEmpty() && editing) rows.add(new Live("DrDonutt", "YouTube", "Giving away spawners", System.currentTimeMillis() - 600_000, 0));
		return rows;
	}

	@Override
	public int width(boolean editing) {
		int w = mc.font.width("LIVE (0)");
		long now = System.currentTimeMillis();
		for (Live l : rows(editing)) {
			w = Math.max(w, mc.font.width(l.who + "  " + l.platform + " · " + ago(now - l.since)));
			if (l.title != null) w = Math.max(w, mc.font.width(shorten(l.title)));
		}
		return w + (background.get() ? 12 : 0);
	}

	@Override
	public int height(boolean editing) {
		List<Live> rows = rows(editing);
		if (rows.isEmpty()) return 0;
		int lines = 1;
		for (Live l : rows) lines += l.title != null && !l.title.isBlank() ? 2 : 1;
		return lines * 10 + (background.get() ? 8 : 0);
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		List<Live> rows = rows(editing);
		if (rows.isEmpty()) return;
		if (background.get()) Glass.draw(context, left, top, left + width(editing), top + height(editing), 4, backgroundColor(Theme.list()), false);
		int pad = background.get() ? 6 : 0, y = top + (background.get() ? 4 : 0);
		context.drawString(mc.font, "LIVE (" + rows.size() + ")", left + pad, y, 0xFFFF4566);
		y += 10;
		long now = System.currentTimeMillis();
		for (Live l : rows) {
			context.drawString(mc.font, l.who, left + pad, y, textColor());
			context.drawString(mc.font, "  " + l.platform + " · " + ago(now - l.since), left + pad + mc.font.width(l.who), y, 0xFFFF4566);
			y += 10;
			if (l.title != null && !l.title.isBlank()) {
				context.drawString(mc.font, shorten(l.title), left + pad, y, mutedColor());
				y += 10;
			}
		}
	}

	private static String shorten(String title) {
		return title.length() <= 48 ? title : title.substring(0, 47).stripTrailing() + "…";
	}

	private static String ago(long ms) {
		long m = ms / 60000;
		return m < 1 ? "now" : m < 60 ? m + "m" : m / 60 + "h " + m % 60 + "m";
	}

	private static String after(CharSequence s, String marker) {
		String text = s.toString();
		int at = text.indexOf(marker);
		int end = at < 0 ? -1 : text.indexOf('"', at + marker.length());
		return end < 0 ? null : text.substring(at + marker.length(), end);
	}

	private static String clean(String s) {
		if (s == null) return null;
		s = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
		StringBuilder out = new StringBuilder(s.length());
		s.codePoints().filter(cp -> cp >= 32 && cp < 8192 || cp >= 8208 && cp < 9728).forEach(out::appendCodePoint);
		return out.toString().replaceAll("\\s+", " ").trim();
	}

	private static long parseStart(String iso) {
		try {
			return iso == null ? 0 : OffsetDateTime.parse(iso).toInstant().toEpochMilli();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static JsonObject object(JsonObject parent, String key) {
		if (parent == null) return null;
		JsonElement element = parent.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}
}
