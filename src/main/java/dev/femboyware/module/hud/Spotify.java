package dev.femboyware.module.hud;

import com.mojang.blaze3d.platform.NativeImage;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The real Spotify app on this PC, for the phone's Spotify app: what it's playing, with its album
 * art, and play, pause, skip and seek. On Windows it reads Spotify's own media session (so a video
 * in the browser doesn't take over); on a Mac it talks to Spotify through AppleScript. One
 * background thread does it while something's using it.
 */
public final class Spotify {
	public static final Identifier ART = Identifier.fromNamespaceAndPath("femboyware", "spotify_art");
	private static final int ART_SIZE = 192;

	private static final Set<Object> USERS = new HashSet<>();
	private static final ConcurrentLinkedQueue<Consumer<MediaPlayer>> COMMANDS = new ConcurrentLinkedQueue<>();
	private static volatile Thread reader;
	private static volatile WindowsMedia.Track track;
	private static volatile WindowsMedia.Art art;
	private static WindowsMedia.Art uploadedArt;
	private static boolean hasArt;
	/** Until when the play button shows what was just pressed, before Spotify says so itself. */
	private static volatile long pressedUntil;
	private static volatile boolean pressedPlaying;
	/** Spotify's own volume, 0 to 1, or -1 if unknown; and one waiting to be sent (NaN when none). */
	private static volatile float volume = -1, pendingVolume = Float.NaN;
	private static volatile long volumeSetAt;

	private Spotify() {
	}

	/** Whether this PC can be talked to: Windows or a Mac. */
	public static boolean supported() {
		return WindowsMedia.supported() || MacSpotify.supported();
	}

	public static synchronized void acquire(Object user) {
		USERS.add(user);
		if (reader != null || !supported()) return;
		Thread thread = new Thread(Spotify::read, "femboyware spotify");
		thread.setDaemon(true);
		reader = thread;
		thread.start();
	}

	public static synchronized void release(Object user) {
		USERS.remove(user);
		if (!USERS.isEmpty() || reader == null) return;
		reader.interrupt();
		reader = null;
		COMMANDS.clear();
		track = null;
		art = null;
		volume = -1;
	}

	/** Whether Spotify's open with something loaded. */
	public static boolean active() {
		return track != null;
	}

	public static String title() {
		WindowsMedia.Track t = track;
		return t == null ? "" : t.title();
	}

	public static String artist() {
		WindowsMedia.Track t = track;
		return t == null ? "" : t.artist();
	}

	public static String album() {
		WindowsMedia.Track t = track;
		return t == null ? "" : t.album();
	}

	public static boolean playing() {
		if (System.currentTimeMillis() < pressedUntil) return pressedPlaying;
		WindowsMedia.Track t = track;
		return t != null && t.playing();
	}

	/** Seconds into the track now, carried forward between looks while it's playing. */
	public static double position() {
		WindowsMedia.Track t = track;
		if (t == null) return 0;
		double position = t.position();
		if (t.playing()) position += (System.currentTimeMillis() - t.updated()) / 1000.0;
		return Mth.clamp(position, 0, t.length() > 0 ? t.length() : Double.MAX_VALUE);
	}

	public static double length() {
		WindowsMedia.Track t = track;
		return t == null ? 0 : t.length();
	}

	public static void playPause() {
		pressedPlaying = !playing();
		pressedUntil = System.currentTimeMillis() + 1500;
		COMMANDS.add(player -> player.control(MediaPlayer.TOGGLE_PLAY_PAUSE));
	}

	public static void next() {
		COMMANDS.add(player -> player.control(MediaPlayer.SKIP_NEXT));
	}

	public static void previous() {
		COMMANDS.add(player -> player.control(MediaPlayer.SKIP_PREVIOUS));
	}

	/** Jumps to {@code seconds} into the track (shown straight away, before Spotify catches up). */
	public static void seek(double seconds) {
		WindowsMedia.Track t = track;
		if (t == null) return;
		double to = Mth.clamp(seconds, 0, t.length());
		track = new WindowsMedia.Track(t.title(), t.artist(), t.album(), t.playing(), to, t.length(), System.currentTimeMillis());
		COMMANDS.add(player -> player.seek(to));
	}

	/** Spotify's own volume, 0 to 1, or -1 when it can't be read yet (on Windows, until it has played some sound). */
	public static float volume() {
		return volume;
	}

	/** Sets Spotify's own volume (not the PC's), shown straight away; while dragging, only the latest is sent. */
	public static void setVolume(float to) {
		float level = Mth.clamp(to, 0, 1);
		volume = level;
		volumeSetAt = System.currentTimeMillis();
		pendingVolume = level;
	}

	/** Opens the Spotify app, for when it isn't running. */
	public static void launch() {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (os.startsWith("mac")) new ProcessBuilder("open", "-a", "Spotify").start();
			else if (os.startsWith("windows")) new ProcessBuilder("cmd", "/c", "start", "", "spotify:").start();
		} catch (Exception e) {
			FemboyWare.LOGGER.info("Couldn't open Spotify", e);
		}
	}

	/** Puts the album art on the GPU, once per track; true if there's art to draw. Render thread only. */
	public static boolean prepareArt() {
		WindowsMedia.Art latest = art;
		if (latest == uploadedArt) return hasArt;
		uploadedArt = latest;
		hasArt = false;
		if (latest == null) return false;
		try {
			NativeImage image = new NativeImage(latest.width(), latest.height(), false);
			for (int y = 0; y < latest.height(); y++) {
				for (int x = 0; x < latest.width(); x++) image.setPixel(x, y, latest.pixels()[y * latest.width() + x]);
			}
			DynamicTexture texture = new DynamicTexture(() -> "femboyware spotify art", image);
			Minecraft.getInstance().getTextureManager().register(ART, texture);
			texture.upload();
			hasArt = true;
		} catch (Exception e) {
			FemboyWare.LOGGER.debug("Couldn't load Spotify's album art", e);
		}
		return hasArt;
	}

	/** The art's size in pixels, for drawing it. */
	public static int artSize() {
		WindowsMedia.Art a = uploadedArt;
		return a == null ? ART_SIZE : a.width();
	}

	/** The art's average colour, for the background behind it; dark grey without art. */
	public static int artColor() {
		WindowsMedia.Art a = art;
		if (a == null) return 0xFF282828;
		long r = 0, g = 0, b = 0;
		int[] pixels = a.pixels();
		for (int i = 0; i < pixels.length; i += 7) {
			r += (pixels[i] >> 16) & 0xFF;
			g += (pixels[i] >> 8) & 0xFF;
			b += pixels[i] & 0xFF;
		}
		int n = Math.max(1, (pixels.length + 6) / 7);
		// Darkened, like Spotify does, so white text stays readable
		return 0xFF000000 | (int) (r / n * 0.6) << 16 | (int) (g / n * 0.6) << 8 | (int) (b / n * 0.6);
	}

	private static void read() {
		Thread self = Thread.currentThread();
		MediaPlayer player = WindowsMedia.supported() ? new WindowsMedia("spotify", ART_SIZE) : new MacSpotify(ART_SIZE);
		try {
			if (!player.open()) {
				FemboyWare.LOGGER.warn("Couldn't reach Spotify");
				return;
			}
			String lastKey = null;
			int artTries = 0;
			long nextPoll = 0, nextVolume = 0;
			while (reader == self) {
				if (BodyguardModule.lowPower()) {
					track = null;
					art = null;
					lastKey = null;
					COMMANDS.clear();
					Thread.sleep(1000);
					continue;
				}
				boolean commanded = false;
				for (Consumer<MediaPlayer> command; (command = COMMANDS.poll()) != null; ) {
					command.accept(player);
					commanded = true;
				}
				float wanted = pendingVolume;
				if (!Float.isNaN(wanted)) {
					pendingVolume = Float.NaN;
					player.setVolume(wanted);
					nextVolume = System.currentTimeMillis() + 1200;
				}
				if (commanded) nextPoll = Math.min(nextPoll, System.currentTimeMillis() + 200);
				if (System.currentTimeMillis() >= nextPoll) {
					WindowsMedia.Track current = player.track();
					track = current;
					String key = current == null ? null : current.title() + "|" + current.artist();
					if (!Objects.equals(key, lastKey)) {
						lastKey = key;
						art = null;
						artTries = 0;
					}
					if (current != null && art == null && artTries < 5) {
						artTries++;
						art = player.art();
					}
					long now = System.currentTimeMillis();
					// Not while it's being dragged, or Spotify's slightly older value would make it jump back
					if (current != null && now >= nextVolume && now - volumeSetAt > 1500) {
						volume = player.volume();
						nextVolume = now + 1000;
					}
					nextPoll = now + player.pollMillis();
				}
				Thread.sleep(30);
			}
		} catch (InterruptedException ignored) {
			// Nothing's using it any more
		} catch (Throwable e) {
			FemboyWare.LOGGER.warn("Stopped reading Spotify", e);
		} finally {
			player.close();
		}
	}
}
