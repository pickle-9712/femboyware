package dev.femboyware.module.larp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FxRender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A big flatscreen TV you put down in the world and play videos on: MP4s (H.264 with AAC sound),
 * M4A music or pictures from your TV folder. With Client Sync on, everyone else with femboyware
 * nearby sees the same TV, playing the same thing at the same moment, and hears it from where it
 * stands; your file goes up to the relay once and they fetch it from there.
 */
public class TvModule extends Module {
	private final NumberSetting size = add(new NumberSetting("Size", "How wide the screen is, in blocks", 3, 1, 32, 0.5));
	private final BoolSetting stand = add(new BoolSetting("Stand", "Stand it on a pedestal; off, it hangs at eye height like it's on a wall", true));
	private final NumberSetting volume = add(new NumberSetting("Volume", "How loud TVs are, yours and everyone else's (louder carries further)", 1, 0, 4, 0.05));
	private final BoolSetting loop = add(new BoolSetting("Loop", "Start again from the beginning when it ends", true));
	private final BoolSetting ambient = add(new BoolSetting("Ambient Glow", "A glow round the TV in the picture's colours", true));
	private final ColorSetting frameColor = add(new ColorSetting("Frame", "The bezel and stand", 0xFF141418));
	private final BoolSetting shareCast = add(new BoolSetting("Share Cast", "When you mirror your browser, everyone with Client Sync sees it on your TV and hears it from there, a second or two behind", true));
	private final NumberSetting cropLeft = add(new NumberSetting("Crop Left", "While mirroring a window: how much of its left side to cut off, in percent", 0, 0, 45, 1)).visibleWhen(this::mirroring);
	private final NumberSetting cropRight = add(new NumberSetting("Crop Right", "While mirroring a window: how much of its right side to cut off, in percent", 0, 0, 45, 1)).visibleWhen(this::mirroring);
	private final NumberSetting cropTop = add(new NumberSetting("Crop Top", "While mirroring a window: how much of its top to cut off, in percent", 0, 0, 45, 1)).visibleWhen(this::mirroring);
	private final NumberSetting cropBottom = add(new NumberSetting("Crop Bottom", "While mirroring a window: how much of its bottom to cut off, in percent", 0, 0, 45, 1)).visibleWhen(this::mirroring);

	/** The files a TV plays, and the folder made for them. */
	private static final List<String> TYPES = List.of("mp4", "m4v", "mov", "m4a", "aac", "png", "jpg", "jpeg");

	/** Your TV: where it stands and which way it faces (the screen faces along {@code yaw}). */
	private Vec3 at;
	private float yaw;
	private net.minecraft.world.level.Level level;
	/** What's on: the file, its name and SHA-256 for sharing, and the clock (position at {@code anchorNanos}). */
	private TvMedia media;
	private String mediaName, mediaSha;
	private double anchorPos;
	private long anchorNanos;
	private boolean playing;

	/** The last thing said about the mirror not working, so it's only said once; and when you last swiped. */
	private String toldProblem;
	private long lastSwipe;

	/** TikTok straight from TikTok, no browser (null when it's not on), and the clip on the TV now. */
	private TikTokFeed tiktok;
	private String tiktokShowing;

	/** Everyone else's TVs here, by who owns them. */
	private final Map<UUID, Shown> peers = new HashMap<>();

	/** Someone's TV as Client Sync last heard it: p = x, y, z, yaw, size, stand; their file once it's here. */
	public record PeerTv(double[] p, String sha, String name, double position, boolean playing, boolean loop, double duration, Path file,
						 String live, int liveSeq) {
	}

	/** Client Sync's connection, for live casts (null when it's off or not sharing). */
	private static dev.femboyware.manager.PeerLink syncLink() {
		return FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).link();
	}

	private static final class Shown {
		PeerTv state;
		TvMedia media;
	}

	/** Whose TV built-in screens are being drawn for right now (set by Client Sync round someone's props), or null for yours. */
	private UUID drawingFor;
	/** Where built-in screens showing each person's TV were last drawn (null key = yours), and when. */
	private final Map<UUID, List<Vec3>> builtInSpots = new HashMap<>();
	private final Map<UUID, Long> builtInSeen = new HashMap<>();
	private static final UUID SELF = new UUID(0, 0);

	public TvModule() {
		super("TV", "A flatscreen that plays your videos, music and pictures, in sync for everyone with Client Sync. Use " + CommandManager.PREFIX + "tv", Category.LARP);
	}

	@Override
	public String getInfo() {
		if (tiktok != null) return "TikTok";
		if (media == null) return at == null ? null : "Off";
		if (media.error() != null) return "Error";
		if (media.kind == TvMedia.Kind.LIVE) return "Mirroring";
		return (playing ? "" : "Paused ") + time(position()) + (media.duration() > 0 ? " / " + time(media.duration()) : "");
	}

	public static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("tv");
	}

	/** Your TV folder first, then your Videos (Movies on a Mac), Music, Pictures and Downloads folders. */
	private static List<Path> folders() {
		List<Path> list = new ArrayList<>();
		list.add(folder());
		Path home = Path.of(System.getProperty("user.home"));
		for (String name : List.of("Videos", "Movies", "Music", "Pictures", "Downloads")) list.add(home.resolve(name));
		return list;
	}

	private static boolean playable(Path file) {
		return TvMedia.kindOf(file.getFileName().toString()) != null && TvMedia.readable(file);
	}

	private static List<Path> files() {
		List<Path> out = new ArrayList<>();
		for (Path dir : folders()) {
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> stream = Files.list(dir)) {
				stream.filter(TvModule::playable).sorted().forEach(out::add);
			} catch (IOException ignored) {
			}
		}
		return out;
	}

	/** A file by its path, or by its name (exactly, then the start of it, then any part) in the folders. */
	private static Path find(String query) {
		try {
			Path direct = Path.of(query.replace("\"", ""));
			if (direct.isAbsolute() && playable(direct)) return direct;
		} catch (RuntimeException ignored) {
		}
		String q = query.toLowerCase(Locale.ROOT);
		List<Path> all = files();
		for (Path p : all) if (p.getFileName().toString().equalsIgnoreCase(query)) return p;
		for (Path p : all) {
			String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.startsWith(q) || name.substring(0, Math.max(0, name.lastIndexOf('.'))).equals(q)) return p;
		}
		for (Path p : all) if (p.getFileName().toString().toLowerCase(Locale.ROOT).contains(q)) return p;
		return null;
	}

	// ---------------------------------------------------------------- commands

	/** Handles {@code .tv <place|play|pause|resume|seek|stop|remove|list|folder>}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
		String rest = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : "";
		switch (action) {
			case "place", "spawn", "put", "here" -> {
				place();
				dev.femboyware.util.Mc.actionBar("TV's out. " + CommandManager.PREFIX + "tv play <file>");
			}
			case "play", "watch", "open" -> {
				if (rest.isEmpty()) {
					if (media == null) ChatUtil.info("Usage: " + CommandManager.PREFIX + "tv play <file>  (see " + CommandManager.PREFIX + "tv list)");
					else setPlaying(true);
					return;
				}
				Path file = find(rest);
				if (file == null) {
					ChatUtil.info("No video, song or picture called \"" + rest + "\". Put them in your TV folder (" + CommandManager.PREFIX + "tv folder) or see " + CommandManager.PREFIX + "tv list");
					return;
				}
				endTikTok();
				play(file);
			}
			case "tiktok", "tt", "fyp", "foryou" -> tiktok(rest);
			case "mirror", "cast", "screen", "browser" -> {
				// Plain .tv mirror shows your screen; TikTok (by name or a link) plays straight from TikTok
				String q = rest.trim().toLowerCase(Locale.ROOT);
				if (q.equals("tiktok") || q.contains("tiktok.com/")) tiktok(q.contains("tiktok.com/") ? rest.trim() : "");
				else mirror(rest);
			}
			case "next", "down", "swipe" -> swipe(1, true);
			case "prev", "previous", "back", "up" -> swipe(-1, true);
			case "pause", "p" -> {
				if (mirroring()) media.mirror().togglePause();
				else setPlaying(false);
			}
			case "resume", "unpause", "continue" -> {
				if (mirroring()) media.mirror().togglePause();
				else setPlaying(true);
			}
			case "toggle" -> setPlaying(!playing);
			case "seek", "skip", "goto" -> seek(rest);
			case "restart", "replay" -> seekTo(0);
			case "stop", "off" -> {
				endTikTok();
				stopMedia();
				ChatUtil.info("TV's off");
			}
			case "remove", "clear", "delete" -> {
				clearTv();
				ChatUtil.info("TV's gone");
			}
			case "list", "files" -> {
				List<Path> all = files();
				if (all.isEmpty()) ChatUtil.info("Nothing to play yet. Put MP4s, M4As or pictures in " + folder() + " (" + CommandManager.PREFIX + "tv folder opens it)");
				else {
					ChatUtil.info("You can play (" + all.size() + "):");
					for (Path p : all.subList(0, Math.min(30, all.size()))) ChatUtil.info("  " + p.getFileName() + "  (" + Math.max(1, sizeOf(p) / 1_000_000) + " MB)");
				}
			}
			case "folder", "dir" -> {
				try {
					Files.createDirectories(folder());
					//? if >=26.3 {
					/*com.mojang.blaze3d.Blaze3D.openPath(folder());
					*///?} else {
					net.minecraft.util.Util.getPlatform().openPath(folder());
					//?}
				} catch (IOException | RuntimeException e) {
					ChatUtil.info("Your TV folder: " + folder());
				}
			}
			default -> {
				ChatUtil.info("Usage: " + CommandManager.PREFIX + "tv <place|play <file>|tiktok [link]|mirror [window]|next|prev|pause|resume|seek <time>|restart|stop|remove|list|folder>");
				ChatUtil.info(CommandManager.PREFIX + "tv tiktok (or " + CommandManager.PREFIX + "mirror) puts TikTok's For You feed on the TV, no browser needed; look at it and scroll to swipe. Add a TikTok link to play that one");
				ChatUtil.info(CommandManager.PREFIX + "tv mirror shows your screen (Windows and Mac; " + CommandManager.PREFIX + "tv mirror screen 2 for another), " + CommandManager.PREFIX + "tv mirror <window> copies one window (Windows)");
				ChatUtil.info("Plays MP4 (H.264), M4A and PNG/JPG from " + folder() + ", Videos, Music, Pictures or Downloads. 480p or 720p plays smoothest");
			}
		}
	}

	public boolean mirroring() {
		return media != null && media.kind == TvMedia.Kind.LIVE;
	}

	/**
	 * {@code .tv tiktok [link]}: TikTok's For You feed on your TV (or just the video linked), fetched
	 * straight from TikTok with no browser, on any computer. Scroll while looking at it to swipe.
	 */
	private void tiktok(String link) {
		String l = link.trim();
		if (!l.isEmpty() && !l.matches("https?://\\S*tiktok\\.com/\\S+")) {
			ChatUtil.info("That isn't a TikTok link. " + CommandManager.PREFIX + "tv tiktok on its own plays the For You feed");
			return;
		}
		if (!hasScreen()) place();
		if (!isEnabled()) setEnabled(true);
		endTikTok();
		stopMedia();
		tiktok = new TikTokFeed(l.isEmpty() ? null : l);
		tiktokShowing = null;
		toldProblem = null;
		dev.femboyware.util.Mc.actionBar("Loading TikTok... Look at the TV and scroll to swipe");
	}

	private void endTikTok() {
		if (tiktok != null) tiktok.close();
		tiktok = null;
		tiktokShowing = null;
	}

	public boolean watchingTikTok() {
		return tiktok != null;
	}

	/** Puts the clip you've swiped to on the TV once it's downloaded, and says what it is. */
	private void tickTikTok() {
		String problem = tiktok.problem();
		if (problem != null && !problem.equals(toldProblem)) ChatUtil.info("TikTok: " + problem);
		toldProblem = problem;
		if (problem != null && tiktok.current() == null) {
			endTikTok();
			return;
		}
		TikTokFeed.Clip clip = tiktok.current();
		Path file = tiktok.currentFile();
		if (clip == null || file == null || clip.id().equals(tiktokShowing)) return;
		play(file);
		tiktokShowing = clip.id();
		dev.femboyware.util.Mc.actionBar(clip.label());
	}

	/** {@code .tv mirror [part of a window's title|list|off|help]}: shows that window on your TV. */
	private void mirror(String query) {
		String q = query.toLowerCase(Locale.ROOT);
		if (q.equals("off") || q.equals("stop")) {
			if (mirroring() || tiktok != null) {
				endTikTok();
				stopMedia();
			}
			ChatUtil.info("Stopped mirroring");
			return;
		}
		// No window named (or "screen [n]"): the whole screen, on Windows or a Mac
		java.util.regex.Matcher screenAsked = java.util.regex.Pattern.compile("(?:screen|display|monitor|desktop)?\\s*(\\d*)").matcher(q);
		if (q.isEmpty() || screenAsked.matches() && !q.isEmpty() && (q.startsWith("screen") || q.startsWith("display") || q.startsWith("monitor") || q.startsWith("desktop"))) {
			int which = screenAsked.matches() && !screenAsked.group(1).isEmpty() ? Integer.parseInt(screenAsked.group(1)) : 0;
			mirrorScreen(which);
			return;
		}
		if (!WindowMirror.supported()) {
			ChatUtil.info("Copying one window only works on Windows. " + CommandManager.PREFIX + "tv mirror shows your whole screen, and " + CommandManager.PREFIX + "tv tiktok plays TikTok");
			return;
		}
		switch (q) {
			case "list" -> {
				ChatUtil.info("Windows you can mirror (" + CommandManager.PREFIX + "tv mirror <part of the name>):");
				for (WindowMirror.Found found : WindowMirror.windows().stream().limit(15).toList()) ChatUtil.info("  " + found.title());
				return;
			}
			case "help" -> {
				mirrorHelp();
				return;
			}
			default -> {
			}
		}
		WindowMirror.Found found = WindowMirror.find(q.isEmpty() ? "tiktok" : query);
		if (found == null && q.isEmpty()) found = WindowMirror.find("youtube");
		if (found == null) {
			ChatUtil.info("No window called \"" + (q.isEmpty() ? "TikTok" : query) + "\". Open it in your browser, or pick one from " + CommandManager.PREFIX + "tv mirror list");
			return;
		}
		if (!hasScreen()) place();
		if (!isEnabled()) setEnabled(true);
		endTikTok();
		stopMedia();
		media = new TvMedia(new WindowMirror(found.hwnd()), () -> new float[]{cropLeft.getFloat() / 100, cropRight.getFloat() / 100, cropTop.getFloat() / 100, cropBottom.getFloat() / 100});
		mediaName = found.title();
		playing = true;
		toldProblem = null;
		if (shareCast.get()) media.startCast(TvModule::syncLink, new WindowMirror(found.hwnd()).pid());
		dev.femboyware.util.Mc.actionBar("Mirroring " + found.title() + (shareCast.get() ? " (shared live)" : "") + ". Look at the TV and scroll to swipe");
	}

	/** {@code .tv mirror} on its own: a whole screen on the TV (the one Minecraft isn't on, if there are two). */
	private void mirrorScreen(int which) {
		if (!WindowMirror.screenSupported()) {
			ChatUtil.info("Mirroring your screen works on Windows and Mac. " + CommandManager.PREFIX + "tv tiktok works anywhere");
			return;
		}
		if (!hasScreen()) place();
		if (!isEnabled()) setEnabled(true);
		endTikTok();
		stopMedia();
		WindowMirror mirror = WindowMirror.screen(which);
		media = new TvMedia(mirror, () -> new float[]{cropLeft.getFloat() / 100, cropRight.getFloat() / 100, cropTop.getFloat() / 100, cropBottom.getFloat() / 100});
		mediaName = mirror.title();
		playing = true;
		toldProblem = null;
		if (shareCast.get()) media.startCast(TvModule::syncLink, 0);
		boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
		int screens = WindowMirror.screens();
		dev.femboyware.util.Mc.actionBar("Mirroring " + mirror.title() + (shareCast.get() ? " (shared live)" : "")
			+ (screens > 1 && which == 0 ? ". " + CommandManager.PREFIX + "tv mirror screen <1-" + screens + "> picks another" : "")
			+ (mac ? ". If it's only your wallpaper, allow Screen Recording for Minecraft" : ""));
	}

	private static void mirrorHelp() {
		ChatUtil.info("Mirroring copies a window on your PC onto your TV, about 30 times a second. Only you see it, and the sound comes from the browser");
		ChatUtil.info("It can't copy minimised windows. If the TV freezes while the browser is fully covered, the browser has stopped drawing it: keep a sliver showing, put it on another screen, or start Chrome/Edge with --disable-features=CalculateNativeWinOcclusion");
		ChatUtil.info("Swipe by scrolling while you look at the TV, or " + CommandManager.PREFIX + "tv next / prev; " + CommandManager.PREFIX + "tv pause sends space");
	}

	/** Sends the next or previous swipe to the mirrored window; true if it went. */
	private boolean swipe(int direction, boolean tell) {
		if (tiktok != null) {
			long now = System.currentTimeMillis();
			if (now - lastSwipe < 250) return true;
			lastSwipe = now;
			if (!tiktok.move(direction)) dev.femboyware.util.Mc.actionBar(direction > 0 ? "Loading more TikToks..." : "That's the first one");
			else if (tiktok.currentFile() == null) dev.femboyware.util.Mc.actionBar("Loading...");
			return true;
		}
		if (!mirroring()) {
			if (tell) ChatUtil.info("Nothing to swipe: mirror your browser first with " + CommandManager.PREFIX + "tv mirror");
			return false;
		}
		long now = System.currentTimeMillis();
		if (now - lastSwipe < 250) return true;
		lastSwipe = now;
		media.mirror().scroll(direction);
		return true;
	}

	/**
	 * From the mouse: scrolling while you look at your mirroring TV swipes the window instead of
	 * changing your hotbar slot. True if it was used.
	 */
	public boolean onScroll(double amount) {
		if (!isEnabled() || !mirroring() && tiktok == null || dev.femboyware.util.Mc.screen() != null || mc.player == null || amount == 0 || !lookingAtScreen()) return false;
		return swipe(amount < 0 ? 1 : -1, false);
	}

	/** Whether your crosshair is on your TV's screen. */
	private boolean lookingAtScreen() {
		if (at == null) return false;
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1);
		Quaternionf back = new Quaternionf().rotateY((float) Math.toRadians(yaw));
		Vector3f e = new Vector3f((float) (eye.x - at.x), (float) (eye.y - at.y), (float) (eye.z - at.z)).rotate(back);
		Vector3f d = new Vector3f((float) look.x, (float) look.y, (float) look.z).rotate(back);
		if (Math.abs(d.z) < 1e-4) return false;
		float t = -e.z / d.z;
		if (t <= 0 || t > 96) return false;
		float x = e.x + d.x * t, y = e.y + d.y * t;
		float half = screenWidth() / 2, y0 = screenBottom();
		return Math.abs(x) <= half + 0.1f && y >= y0 - 0.1f && y <= y0 + screenHeight() + 0.3f;
	}

	private static long sizeOf(Path p) {
		try {
			return Files.size(p);
		} catch (IOException e) {
			return 0;
		}
	}

	/** Puts the TV a few blocks in front of you, facing you. */
	private void place() {
		if (mc.player == null) return;
		float look = mc.player.getYRot();
		float distance = 2.5f + size.getFloat() * 0.6f;
		Vec3 forward = Vec3.directionFromRotation(0, look);
		at = mc.player.position().add(forward.x * distance, 0, forward.z * distance);
		yaw = Math.round((look + 180) / 15f) * 15f;
		level = mc.level;
		if (!isEnabled()) setEnabled(true);
	}

	/** Whether there's somewhere to show your TV: one you've put down, or a TV in your mansion, yacht or jet. */
	private boolean hasScreen() {
		if (at != null && level == mc.level) return true;
		Long seen = builtInSeen.get(SELF);
		List<Vec3> spots = builtInSpots.get(SELF);
		if (seen == null || spots == null || mc.player == null || System.currentTimeMillis() - seen > 1500) return false;
		for (Vec3 spot : spots) if (spot.distanceToSqr(mc.player.position()) < 40 * 40) return true;
		return false;
	}

	private void play(Path file) {
		if (!hasScreen()) place();
		if (!isEnabled()) setEnabled(true);
		stopMedia();
		mediaName = file.getFileName().toString();
		media = new TvMedia(file, mediaName);
		mediaSha = null;
		anchorPos = 0;
		anchorNanos = System.nanoTime();
		playing = true;
		long bytes = sizeOf(file);
		TvMedia started = media;
		// Hashed off the main thread, so it can be shared once it's known
		Thread hash = new Thread(() -> {
			String sha = sha256(file);
			mc.execute(() -> {
				if (media == started) mediaSha = sha;
			});
		}, "femboyware tv hash");
		hash.setDaemon(true);
		hash.start();
		dev.femboyware.util.Mc.actionBar("Now playing " + mediaName + (bytes > dev.femboyware.manager.PeerLink.MAX_FILE
			? " (over " + dev.femboyware.manager.PeerLink.MAX_FILE / 1_000_000 + " MB, so only you can see it)" : ""));
	}

	private static String sha256(Path file) {
		try (var in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
			return HexFormat.of().formatHex(digest.digest());
		} catch (Exception e) {
			return null;
		}
	}

	private void setPlaying(boolean play) {
		if (media == null) {
			ChatUtil.info("Nothing's on. Play something with " + CommandManager.PREFIX + "tv play <file>");
			return;
		}
		anchorPos = position();
		anchorNanos = System.nanoTime();
		if (play && media.duration() > 0 && anchorPos >= media.duration() - 0.1) anchorPos = 0;
		playing = play;
		dev.femboyware.util.Mc.actionBar((play ? "Playing " : "Paused ") + mediaName + " at " + time(anchorPos));
	}

	/** {@code 90}, {@code 1:30}, {@code +10} or {@code -10}. */
	private void seek(String text) {
		if (media == null || text.isEmpty()) {
			ChatUtil.info("Usage: " + CommandManager.PREFIX + "tv seek <1:30|90|+10|-10>");
			return;
		}
		try {
			boolean relative = text.startsWith("+") || text.startsWith("-");
			double sign = text.startsWith("-") ? -1 : 1;
			String body = relative ? text.substring(1) : text;
			double seconds = 0;
			for (String part : body.split(":")) seconds = seconds * 60 + Double.parseDouble(part);
			seekTo(relative ? position() + sign * seconds : seconds);
		} catch (NumberFormatException e) {
			ChatUtil.info("Usage: " + CommandManager.PREFIX + "tv seek <1:30|90|+10|-10>");
		}
	}

	private void seekTo(double seconds) {
		if (media == null) return;
		double end = media.duration() > 0 ? media.duration() : seconds;
		anchorPos = Math.max(0, Math.min(seconds, end));
		anchorNanos = System.nanoTime();
		dev.femboyware.util.Mc.actionBar("Skipped to " + time(anchorPos));
	}

	private static String time(double seconds) {
		int s = (int) Math.max(0, seconds);
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}

	private double position() {
		return playing ? anchorPos + (System.nanoTime() - anchorNanos) / 1e9 : anchorPos;
	}

	private void stopMedia() {
		if (media != null) media.close();
		media = null;
		mediaName = mediaSha = null;
		playing = false;
	}

	/** For LarpSave: where the TV stands (x, y, z, yaw), or null. */
	public double[] savePlace() {
		return at == null || level != mc.level ? null : new double[]{at.x, at.y, at.z, yaw};
	}

	/** For LarpSave: puts the TV back where it was saved (or takes it away), quietly. */
	public void restorePlace(double[] p) {
		if (p == null || p.length < 4) {
			if (at != null) clearTv();
			return;
		}
		at = new Vec3(p[0], p[1], p[2]);
		yaw = (float) p[3];
		level = mc.level;
		if (!isEnabled()) setEnabled(true);
	}

	private void clearTv() {
		endTikTok();
		stopMedia();
		at = null;
		level = null;
	}

	@Override
	protected void onDisable() {
		clearTv();
	}

	// ---------------------------------------------------------------- ticking

	@Override
	public void onTick() {
		if (at != null && level != mc.level) clearTv();
		if (tiktok != null) tickTikTok();
		if (media == null) return;
		if (media.error() != null && tiktok != null) {
			// A clip that won't play: on to the next one
			stopMedia();
			tiktok.move(1);
			return;
		}
		if (media.error() != null) {
			ChatUtil.info("The TV couldn't play " + mediaName + ": " + media.error());
			stopMedia();
			return;
		}
		if (media.kind == TvMedia.Kind.LIVE) {
			String problem = media.liveProblem();
			if (problem == null && media.cast() != null) problem = media.cast().problem();
			if (problem != null && !problem.equals(toldProblem)) ChatUtil.info("TV mirror: " + problem);
			toldProblem = problem;
			if (!media.mirror().alive()) stopMedia();
			return;
		}
		// The end: round again, or stop on the last frame
		double duration = media.duration();
		if (playing && duration > 0 && position() >= duration) {
			if (loop.get() || tiktok != null) {
				anchorPos = 0;
				anchorNanos = System.nanoTime();
			} else {
				anchorPos = duration;
				playing = false;
			}
		}
		media.sync(position(), playing);
		Vec3 sound = nearest(at != null ? world(0, screenBottom() + screenHeight() / 2, 0) : null, SELF);
		if (sound != null) media.tickSound(sound.x, sound.y, sound.z, volume.getFloat());
		else media.tickSound(0, -10_000, 0, 0);
	}

	/** Of a TV that's been put down and the built-in screens showing that person's TV, the one nearest you; null if none. */
	private Vec3 nearest(Vec3 placed, UUID who) {
		Vec3 best = placed;
		Long seen = builtInSeen.get(who);
		List<Vec3> spots = builtInSpots.get(who);
		if (seen != null && spots != null && System.currentTimeMillis() - seen < 1500 && mc.player != null) {
			for (Vec3 spot : spots) if (best == null || spot.distanceToSqr(mc.player.position()) < best.distanceToSqr(mc.player.position())) best = spot;
		}
		return best;
	}

	/** Your TV for Client Sync to send, or null: where it is, what's on, and where playback is right now. */
	public JsonObject shareState() {
		if (!isEnabled() || at == null && media == null) return null;
		JsonObject o = new JsonObject();
		if (at != null) {
			JsonArray p = new JsonArray();
			for (double v : new double[]{at.x, at.y, at.z, yaw, size.get(), stand.get() ? 1 : 0}) p.add(Math.round(v * 1000) / 1000.0);
			o.add("p", p);
		}
		if (mirroring() && media.cast() != null && media.cast().latest() >= 0) {
			o.addProperty("lv", media.cast().id);
			o.addProperty("ls", media.cast().latest());
			o.addProperty("n", mediaName.length() > 60 ? mediaName.substring(0, 60) : mediaName);
		}
		if (media != null && mediaSha != null && media.error() == null && sizeOf(media.file) <= dev.femboyware.manager.PeerLink.MAX_FILE) {
			o.addProperty("h", mediaSha);
			o.addProperty("n", mediaName);
			o.addProperty("t", Math.round(position() * 1000) / 1000.0);
			o.addProperty("on", playing);
			o.addProperty("lp", loop.get());
			o.addProperty("d", Math.round(media.duration() * 1000) / 1000.0);
		}
		return o;
	}

	/** The file to put on the relay for the others, once it's hashed: its SHA-256, path and name; or null. */
	public String[] shareFile() {
		if (media == null || mediaSha == null || sizeOf(media.file) > dev.femboyware.manager.PeerLink.MAX_FILE) return null;
		return new String[]{mediaSha, media.file.toString(), mediaName};
	}

	/** Reads someone's shared TV (see {@link #shareState}); null if it's not one. */
	public static PeerTv parse(JsonElement element, double secondsSince) {
		if (element == null || !element.isJsonObject()) return null;
		JsonObject o = element.getAsJsonObject();
		double[] p = null;
		if (o.has("p") && o.get("p").isJsonArray() && o.getAsJsonArray("p").size() >= 6) {
			p = new double[6];
			for (int i = 0; i < 6; i++) {
				p[i] = o.getAsJsonArray("p").get(i).getAsDouble();
				if (!Double.isFinite(p[i])) return null;
			}
			p[4] = Math.max(1, Math.min(32, p[4]));
		}
		String live = o.has("lv") ? o.get("lv").getAsString() : null;
		if (live != null && !live.matches("[0-9a-f]{32}")) live = null;
		int liveSeq = o.has("ls") ? Math.max(0, o.get("ls").getAsInt()) : 0;
		if (p == null && !o.has("h") && live == null) return null;
		String sha = o.has("h") ? o.get("h").getAsString() : null;
		if (sha != null && !sha.matches("[0-9a-f]{64}")) sha = null;
		String name = o.has("n") ? o.get("n").getAsString() : "video";
		if (name.length() > 80) name = name.substring(0, 80);
		boolean on = o.has("on") && o.get("on").getAsBoolean();
		boolean looping = o.has("lp") && o.get("lp").getAsBoolean();
		double duration = o.has("d") ? Math.max(0, o.get("d").getAsDouble()) : 0;
		double t = o.has("t") ? Math.max(0, o.get("t").getAsDouble()) : 0;
		// Moved on by however long ago they said it
		if (on) t += Math.max(0, secondsSince);
		if (duration > 0 && t > duration) t = looping ? t % duration : duration;
		if (sha == null || TvMedia.kindOf(name) == null) sha = null;
		return new PeerTv(p, sha, name, t, on, looping, duration, null, live, liveSeq);
	}

	/** The extension a shared file is saved with, from its name. */
	public static String extensionFor(String name) {
		String ext = TvMedia.extension(name);
		return TYPES.contains(ext) ? ext : "mp4";
	}

	/**
	 * From Client Sync, every tick: everyone's TVs here right now. Theirs start playing once their
	 * file's downloaded, follow their clock, and stop when they go.
	 */
	public void syncPeers(Map<UUID, PeerTv> tvs) {
		peers.entrySet().removeIf(entry -> {
			if (tvs.containsKey(entry.getKey())) return false;
			if (entry.getValue().media != null) entry.getValue().media.close();
			return true;
		});
		for (var entry : tvs.entrySet()) {
			Shown shown = peers.computeIfAbsent(entry.getKey(), k -> new Shown());
			PeerTv tv = entry.getValue();
			boolean same = shown.media != null && (tv.live() != null
				? shown.media.feed != null && shown.media.feed.id.equals(tv.live())
				: shown.media.file != null && tv.file() != null && shown.media.file.equals(tv.file()));
			if (!same && shown.media != null) {
				shown.media.close();
				shown.media = null;
			}
			if (shown.media == null && tv.live() != null) shown.media = new TvMedia(new LiveCast.Feed(TvModule::syncLink, tv.live(), tv.liveSeq()));
			else if (shown.media == null && tv.file() != null) shown.media = new TvMedia(tv.file(), tv.name());
			shown.state = tv;
			if (shown.media == null) continue;
			if (shown.media.error() != null) continue;
			if (tv.live() == null) shown.media.sync(tv.position(), tv.playing());
			Vec3 sound = nearest(tv.p() != null ? peerWorld(tv, 0, peerScreenBottom(tv) + peerScreenHeight(tv) / 2, 0) : null, entry.getKey());
			if (sound != null) shown.media.tickSound(sound.x, sound.y, sound.z, volume.getFloat());
			else shown.media.tickSound(0, -10_000, 0, 0);
		}
	}

	/** Everyone else's TVs go quiet and away (Client Sync turned off or you left). */
	public void clearPeers() {
		syncPeers(Map.of());
	}

	// ---------------------------------------------------------------- shape

	private float screenWidth() {
		return size.getFloat();
	}

	private float screenHeight() {
		return size.getFloat() * 9 / 16;
	}

	private float screenBottom() {
		return bottom(stand.get(), size.getFloat());
	}

	private static float bottom(boolean onStand, float width) {
		return onStand ? 0.12f + width * 0.08f : Math.max(0.6f, 1.75f - width * 9 / 32);
	}

	private static float peerScreenHeight(PeerTv tv) {
		return (float) tv.p()[4] * 9 / 16;
	}

	private static float peerScreenBottom(PeerTv tv) {
		return bottom(tv.p()[5] > 0.5, (float) tv.p()[4]);
	}

	private Vec3 world(float x, float y, float z) {
		Vector3f v = new Vector3f(x, y, z).rotate(new Quaternionf().rotateY((float) Math.toRadians(-yaw)));
		return at.add(v.x, v.y, v.z);
	}

	private static Vec3 peerWorld(PeerTv tv, float x, float y, float z) {
		Vector3f v = new Vector3f(x, y, z).rotate(new Quaternionf().rotateY((float) Math.toRadians(-tv.p()[3])));
		return new Vec3(tv.p()[0] + v.x, tv.p()[1] + v.y, tv.p()[2] + v.z);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void onRender3D(Render3DEvent event) {
		if (at == null || level != mc.level) return;
		draw(event, at, yaw, size.getFloat(), stand.get(), media);
	}

	/** Everyone else's TVs, from Client Sync's drawing (so they show with your own TV module off). */
	public void drawPeers(Render3DEvent event) {
		for (Shown shown : peers.values()) {
			PeerTv tv = shown.state;
			if (tv == null || tv.p() == null) continue;
			draw(event, new Vec3(tv.p()[0], tv.p()[1], tv.p()[2]), (float) tv.p()[3], (float) tv.p()[4], tv.p()[5] > 0.5, shown.media);
		}
	}

	private void draw(Render3DEvent event, Vec3 pos, float facing, float width, boolean onStand, TvMedia showing) {
		Vec3 cam = event.camera().pos;
		if (cam.distanceToSqr(pos) > 160 * 160) return;
		float ox = (float) (pos.x - cam.x), oy = (float) (pos.y - cam.y), oz = (float) (pos.z - cam.z);
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-facing));
		float height = width * 9 / 16, bottom = bottom(onStand, width);
		float bezel = Math.max(0.025f, width * 0.012f), depth = 0.04f + width * 0.012f;
		float half = width / 2;
		int frame = frameColor.get() | 0xFF000000;

		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn, ox, oy, oz);
		// The panel, slim with a thicker box behind for the electronics
		b.box(-half - bezel, bottom, -depth, half + bezel, bottom + height + bezel * 2, 0, frame);
		b.box(-half * 0.6f, bottom + height * 0.2f, -depth * 2.2f, half * 0.6f, bottom + height * 0.8f, -depth, ShapeBuilder.shade(frame, 0.9f));
		if (onStand) {
			float neck = 0.05f + width * 0.03f;
			b.box(-neck, 0.03f, -depth * 1.6f - neck * 0.5f, neck, bottom + height * 0.35f, -depth * 1.6f + neck * 0.5f, frame);
			float foot = Math.max(0.35f, width * 0.22f), footDepth = 0.2f + width * 0.06f;
			b.box(-foot, 0, -depth * 1.6f - footDepth, foot, 0.035f, -depth * 1.6f + footDepth, frame);
		} else {
			// A wall bracket behind it
			b.box(-half * 0.3f, bottom + height * 0.3f, -depth * 3, half * 0.3f, bottom + height * 0.7f, -depth * 2.2f, ShapeBuilder.shade(frame, 0.8f));
		}
		// The glass, black when there's nothing on; a playing song shows a slow pink wash
		float sx0 = -half, sx1 = half, sy0 = bottom + bezel, sy1 = sy0 + height;
		int glass = 0xFF050507;
		boolean song = showing != null && showing.ready() && showing.kind == TvMedia.Kind.AUDIO;
		if (song) {
			float t = (float) (showing.position() * 0.6);
			glass = FxRender.mix(0xFF2A0A24, 0xFFE0559A, 0.35f + 0.25f * (float) Math.sin(t));
		}
		quad(b, turn, ox, oy, oz, event, sx0, sy0, sx1, sy1, 0.003f, glass);

		Identifier texture = showing != null && showing.hasPicture() ? showing.frame() : null;
		if (texture != null) {
			// Letterboxed to the picture's shape
			float aspect = showing.aspect(), px0 = sx0, px1 = sx1, py0 = sy0, py1 = sy1;
			if (aspect > width / height) {
				float h = width / aspect;
				py0 = sy0 + (height - h) / 2;
				py1 = py0 + h;
			} else {
				float w = height * aspect;
				px0 = -w / 2;
				px1 = w / 2;
			}
			VertexConsumer screen = FxRender.solid(event, texture);
			PoseStack.Pose pose = event.matrices().last();
			Vector3f a = corner(turn, ox, oy, oz, px0, py1), c = corner(turn, ox, oy, oz, px1, py1),
				d = corner(turn, ox, oy, oz, px1, py0), e = corner(turn, ox, oy, oz, px0, py0);
			FxRender.textured(screen, pose, e.x, e.y, e.z, 0, 1, 0xFFFFFFFF);
			FxRender.textured(screen, pose, d.x, d.y, d.z, 1, 1, 0xFFFFFFFF);
			FxRender.textured(screen, pose, c.x, c.y, c.z, 1, 0, 0xFFFFFFFF);
			FxRender.textured(screen, pose, a.x, a.y, a.z, 0, 0, 0xFFFFFFFF);
			if (ambient.get() && showing.ambientColor() != 0) glow(event, turn, ox, oy, oz, width, height, bottom, depth, showing.ambientColor());
		} else if (song && ambient.get()) {
			glow(event, turn, ox, oy, oz, width, height, bottom, depth, glass);
		}
	}

	/** From Client Sync: whose props it's drawing next (null when done), so their built-in TVs show their TV. */
	public void drawingFor(UUID who) {
		drawingFor = who;
	}

	/** Whose props are being drawn right now (null: yours), for other props that show their owner's things. */
	public UUID drawingForOwner() {
		return drawingFor;
	}

	/**
	 * A TV built into a prop (the mansion, the yacht, the jet): shows what's on the TV of whoever owns
	 * the prop, letterboxed into a screen {@code width} by {@code height} centred on {@code center}
	 * in the prop's own space, facing along {@code right} x up. Call it last in the prop's drawing,
	 * since it switches to the screen's own layer. Draws nothing when nothing's on, so the prop's
	 * own dark glass shows.
	 */
	public void drawBuiltIn(Render3DEvent event, Quaternionf turn, float ox, float oy, float oz, Vector3f center, Vector3f right, float width, float height) {
		UUID who = drawingFor == null ? SELF : drawingFor;
		TvMedia showing;
		if (drawingFor == null) showing = isEnabled() ? media : null;
		else {
			Shown shown = peers.get(drawingFor);
			showing = shown == null ? null : shown.media;
		}
		// Remembered for where the sound comes from, even before there's a picture
		Vector3f mid = new Vector3f(center).rotate(turn);
		Vec3 cam = event.camera().pos;
		long now = System.currentTimeMillis();
		Long last = builtInSeen.get(who);
		List<Vec3> spots = builtInSpots.computeIfAbsent(who, k -> new ArrayList<>());
		if (last == null || now - last > 50) spots.clear();
		if (spots.size() < 8) spots.add(new Vec3(cam.x + ox + mid.x, cam.y + oy + mid.y, cam.z + oz + mid.z));
		builtInSeen.put(who, now);
		if (showing == null || !showing.hasPicture()) return;
		Identifier texture = showing.frame();
		if (texture == null) return;
		float aspect = showing.aspect(), w = width, h = height;
		if (aspect > width / height) h = width / aspect;
		else w = height * aspect;
		Vector3f up = new Vector3f(0, 1, 0);
		Vector3f normal = new Vector3f(right).cross(up).normalize(0.004f);
		Vector3f r = new Vector3f(right).normalize(w / 2), u = new Vector3f(0, h / 2, 0);
		Vector3f[] corners = {
			new Vector3f(center).sub(r).sub(u), new Vector3f(center).add(r).sub(u), new Vector3f(center).add(r).add(u), new Vector3f(center).sub(r).add(u)};
		float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
		VertexConsumer screen = FxRender.solid(event, texture);
		PoseStack.Pose pose = event.matrices().last();
		for (int i = 0; i < 4; i++) {
			Vector3f v = corners[i].add(normal).rotate(turn).add(ox, oy, oz);
			FxRender.textured(screen, pose, v.x, v.y, v.z, uv[i][0], uv[i][1], 0xFFFFFFFF);
		}
	}

	// ---------------------------------------------------------------- for the phone's TV app

	/** A line about what's on, for the remote. */
	public String status() {
		if (tiktok != null) {
			if (tiktok.problem() != null) return "TikTok: " + tiktok.problem();
			TikTokFeed.Clip clip = tiktok.current();
			if (clip == null || media == null || !clip.id().equals(tiktokShowing)) return "Loading TikTok...";
			return "TikTok " + time(position()) + " / " + time(media.duration());
		}
		if (media == null) return hasScreen() ? "Nothing on" : "No TV out";
		if (media.error() != null) return "Couldn't play it";
		if (mirroring()) {
			if (media.liveProblem() != null) return "Mirror: " + media.liveProblem();
			if (media.cast() != null && media.cast().problem() != null) return "Cast: " + media.cast().problem();
			return media.cast() != null ? "Mirroring, shared live" : "Mirroring";
		}
		if (!media.ready()) return "Loading...";
		return (playing ? "Playing " : "Paused ") + time(position()) + (media.duration() > 0 ? " / " + time(media.duration()) : "");
	}

	/** What's on, or null. */
	public String nowPlaying() {
		if (tiktok != null) return tiktok.current() == null ? "TikTok" : tiktok.current().label();
		return media == null ? null : mediaName;
	}

	public boolean isPlaying() {
		return media != null && (mirroring() || playing);
	}

	public boolean hasTvOut() {
		return at != null && level == mc.level;
	}

	/** How far through it is, 0 to 1, or -1 if that doesn't apply. */
	public float progress() {
		if (media == null || mirroring() || media.duration() <= 0) return -1;
		return (float) Math.max(0, Math.min(1, position() / media.duration()));
	}

	/** The files the remote lists. */
	public static List<Path> playableFiles() {
		return files();
	}

	public void remotePlay(Path file) {
		endTikTok();
		play(file);
	}

	/** Play or pause; while mirroring, space for the page. */
	public void remoteToggle() {
		if (mirroring()) media.mirror().togglePause();
		else if (media != null) {
			anchorPos = position();
			anchorNanos = System.nanoTime();
			if (!playing && media.duration() > 0 && anchorPos >= media.duration() - 0.1) anchorPos = 0;
			playing = !playing;
		}
	}

	/** Skips by {@code seconds}; while mirroring, swipes instead. */
	public void remoteSkip(double seconds) {
		if (mirroring() || tiktok != null) swipe(seconds > 0 ? 1 : -1, false);
		else if (media != null) {
			double end = media.duration() > 0 ? media.duration() : Double.MAX_VALUE;
			anchorPos = Math.max(0, Math.min(position() + seconds, end));
			anchorNanos = System.nanoTime();
		}
	}

	/** TikTok's For You feed on the TV, no browser needed (any computer); null if it's on, or why not. */
	public String remoteMirror() {
		tiktok("");
		return null;
	}

	/** The remote's Mirror button: your screen on the TV. Null if it went, or why not. */
	public String remoteMirrorScreen() {
		if (!WindowMirror.screenSupported()) return "Mirroring works on Windows and Mac";
		mirrorScreen(0);
		return null;
	}

	public void remoteStop() {
		endTikTok();
		stopMedia();
	}

	public void remotePlace() {
		place();
	}

	public void remoteRemove() {
		clearTv();
	}

	public void remoteOpenFolder() {
		try {
			Files.createDirectories(folder());
			//? if >=26.3 {
			/*com.mojang.blaze3d.Blaze3D.openPath(folder());
			*///?} else {
			net.minecraft.util.Util.getPlatform().openPath(folder());
			//?}
		} catch (IOException | RuntimeException e) {
			ChatUtil.info("Your TV folder: " + folder());
		}
	}

	public float volume() {
		return volume.getFloat();
	}

	public void setVolume(float value) {
		volume.set((double) Math.max(0, Math.min(4, Math.round(value * 20) / 20f)));
	}

	private static Vector3f corner(Quaternionf turn, float ox, float oy, float oz, float x, float y) {
		Vector3f v = new Vector3f(x, y, 0.006f).rotate(turn);
		return v.add(ox, oy, oz);
	}

	/** A flat unshaded quad facing forward at depth {@code z}. */
	private static void quad(ShapeBuilder b, Quaternionf turn, float ox, float oy, float oz, Render3DEvent event, float x0, float y0, float x1, float y1, float z, int color) {
		b.box(x0, y0, z - 0.001f, x1, y1, z, color);
	}

	/** The picture's colours spilling onto whatever's behind and round the TV. */
	private static void glow(Render3DEvent event, Quaternionf turn, float ox, float oy, float oz, float width, float height, float bottom, float depth, int color) {
		VertexConsumer light = FxRender.textured(event, FxRender.GLOW, true, false);
		PoseStack.Pose pose = event.matrices().last();
		float cx = 0, cy = bottom + height / 2, w = width * 0.95f, h = height * 1.1f, z = -depth * 3.2f;
		int c = FxRender.fade(color | 0xFF000000, 0.55f);
		Vector3f[] p = new Vector3f[4];
		float[][] corners = {{cx - w, cy - h}, {cx + w, cy - h}, {cx + w, cy + h}, {cx - w, cy + h}};
		float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
		for (int i = 0; i < 4; i++) {
			p[i] = new Vector3f(corners[i][0], corners[i][1], z).rotate(turn).add(ox, oy, oz);
			FxRender.textured(light, pose, p[i].x, p[i].y, p[i].z, uv[i][0], uv[i][1], c);
		}
	}
}
