package dev.femboyware.module.hud;

import com.mojang.blaze3d.platform.NativeImage;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * What's playing on the PC, shared by every HUD element that shows it. One background thread reads
 * {@link WindowsMedia} a few times a second while any of them is on (or Spotify, on a Mac), and in
 * Bodyguard's low power mode it stops reading, so guards spend nothing on it.
 */
final class MediaSource {
	static final Identifier ART = Identifier.fromNamespaceAndPath("femboyware", "media_art");
	/** The same art cut to a circle. */
	static final Identifier ROUND_ART = Identifier.fromNamespaceAndPath("femboyware", "media_art_round");
	private static final int ROUND_SIZE = 48;
	/** How often to look at what's playing, in milliseconds, so lyrics start right as a song does. */
	private static final int POLL = 250;

	private static final Set<Object> USERS = new HashSet<>();
	/** Controls waiting for the reader thread, which owns every Windows call. */
	private static final ConcurrentLinkedQueue<Integer> CONTROLS = new ConcurrentLinkedQueue<>();
	private static volatile Thread reader;
	private static volatile WindowsMedia.Track track;
	private static volatile WindowsMedia.Art art;
	private static WindowsMedia.Art uploadedArt;
	private static boolean hasArt;

	private MediaSource() {
	}

	static void acquire(Object user) {
		USERS.add(user);
		if (reader != null || !(WindowsMedia.supported() || MacSpotify.supported())) return;
		Thread thread = new Thread(MediaSource::read, "femboyware media");
		thread.setDaemon(true);
		reader = thread;
		thread.start();
	}

	static void release(Object user) {
		USERS.remove(user);
		if (!USERS.isEmpty() || reader == null) return;
		reader.interrupt();
		reader = null;
		CONTROLS.clear();
		track = null;
		art = null;
	}

	/** The latest track, or null when nothing is playing. A song femboyware is playing comes first. */
	static WindowsMedia.Track track() {
		WindowsMedia.Track ours = NowPlaying.track();
		return ours != null ? ours : track;
	}

	static void control(int slot) {
		if (reader != null) CONTROLS.add(slot);
	}

	/** How far into {@code track} we are now, in seconds. Windows only updates it now and then, so it's carried forward while playing. */
	static double position(WindowsMedia.Track track) {
		double position = track.position();
		if (track.playing()) position += (System.currentTimeMillis() - track.updated()) / 1000.0;
		return Mth.clamp(position, 0, track.length() > 0 ? track.length() : Double.MAX_VALUE);
	}

	/** Puts the latest album art on the GPU, once per track; true if there's art to draw. Render thread only. */
	static boolean prepareArt() {
		WindowsMedia.Art latest = NowPlaying.track() != null ? NowPlaying.art() : art;
		if (latest == uploadedArt) return hasArt;
		uploadedArt = latest;
		hasArt = false;
		if (latest == null) return false;
		try {
			NativeImage image = new NativeImage(latest.width(), latest.height(), false);
			for (int y = 0; y < latest.height(); y++) {
				for (int x = 0; x < latest.width(); x++) image.setPixel(x, y, latest.pixels()[y * latest.width() + x]);
			}
			upload(ART, image);
			upload(ROUND_ART, round(latest));
			hasArt = true;
		} catch (Exception e) {
			FemboyWare.LOGGER.debug("Couldn't load album art", e);
		}
		return hasArt;
	}

	private static void upload(Identifier id, NativeImage image) {
		DynamicTexture texture = new DynamicTexture(() -> "femboyware media art", image);
		Minecraft.getInstance().getTextureManager().register(id, texture);
		texture.upload();
	}

	/** Averages the art down to a small size and cuts a smooth edged circle from it. */
	private static NativeImage round(WindowsMedia.Art source) {
		int factor = Math.max(1, source.width() / ROUND_SIZE);
		int size = source.width() / factor;
		NativeImage image = new NativeImage(size, size, false);
		float half = size / 2f;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int r = 0, g = 0, b = 0;
				for (int sy = 0; sy < factor; sy++) {
					for (int sx = 0; sx < factor; sx++) {
						int pixel = source.pixels()[(y * factor + sy) * source.width() + x * factor + sx];
						r += (pixel >> 16) & 0xFF;
						g += (pixel >> 8) & 0xFF;
						b += pixel & 0xFF;
					}
				}
				int samples = factor * factor;
				float distance = (float) Math.hypot(x + 0.5f - half, y + 0.5f - half);
				int alpha = (int) (Mth.clamp(half - distance + 0.5f, 0, 1) * 255);
				image.setPixel(x, y, alpha << 24 | (r / samples) << 16 | (g / samples) << 8 | b / samples);
			}
		}
		return image;
	}

	private static void read() {
		Thread self = Thread.currentThread();
		MediaPlayer media = MediaPlayer.forThisPc();
		if (media == null) return;
		try {
			if (!media.open()) {
				FemboyWare.LOGGER.warn("Couldn't reach the PC's media controls");
				return;
			}
			String lastKey = null;
			int artTries = 0;
			long nextPoll = 0;
			while (reader == self) {
				if (BodyguardModule.lowPower()) {
					track = null;
					art = null;
					lastKey = null;
					CONTROLS.clear();
					Thread.sleep(1000);
					continue;
				}
				Integer slot;
				boolean controlled = false;
				while ((slot = CONTROLS.poll()) != null) {
					media.control(slot);
					controlled = true;
				}
				// Look again soon after a control so the HUD catches up quickly
				if (controlled) nextPoll = Math.min(nextPoll, System.currentTimeMillis() + 150);
				if (System.currentTimeMillis() >= nextPoll) {
					WindowsMedia.Track current = media.track();
					track = current;
					String key = current == null ? null : current.title() + "|" + current.artist();
					if (!Objects.equals(key, lastKey)) {
						lastKey = key;
						art = null;
						artTries = 0;
					}
					// Some apps hand over the art a moment after the title, so try a few times
					if (current != null && art == null && artTries < 5) {
						artTries++;
						art = media.art();
					}
					nextPoll = System.currentTimeMillis() + Math.max(POLL, media.pollMillis());
				}
				Thread.sleep(30);
			}
		} catch (InterruptedException ignored) {
			// Turned off
		} catch (Throwable e) {
			FemboyWare.LOGGER.warn("Stopped reading the PC's media controls", e);
		} finally {
			media.close();
		}
	}
}
