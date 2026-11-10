package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * A song femboyware itself is playing (like an emote's), shown on the Island, Media Player and
 * Lyrics instead of whatever Windows says is playing, until it's cleared.
 */
public final class NowPlaying {
	private static volatile WindowsMedia.Track track;
	private static volatile WindowsMedia.Art art;

	private NowPlaying() {
	}

	/** Shows a song starting now. {@code artPath} is a PNG on the classpath, or null for none. */
	public static void set(String title, String artist, double length, String artPath) {
		art = artPath == null ? null : load(artPath);
		track = new WindowsMedia.Track(title, artist, true, 0, length, System.currentTimeMillis());
	}

	public static void clear() {
		track = null;
		art = null;
	}

	static WindowsMedia.Track track() {
		return track;
	}

	static WindowsMedia.Art art() {
		return art;
	}

	private static WindowsMedia.Art load(String path) {
		try (InputStream in = NowPlaying.class.getResourceAsStream(path)) {
			if (in == null) return null;
			BufferedImage image = ImageIO.read(in);
			int width = image.getWidth(), height = image.getHeight();
			return new WindowsMedia.Art(width, height, image.getRGB(0, 0, width, height, null, 0, width));
		} catch (Exception e) {
			FemboyWare.LOGGER.debug("Couldn't load song art {}", path, e);
			return null;
		}
	}
}
