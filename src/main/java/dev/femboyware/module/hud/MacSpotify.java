package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Spotify on a Mac, through its AppleScript commands (with {@code osascript}, which every Mac has).
 * It only ever talks to Spotify when it's already running, so it never opens it by itself. The
 * first time, macOS asks whether Minecraft may control Spotify.
 */
final class MacSpotify implements MediaPlayer {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final String SEP = "\u001f";
	/** Reads everything in one go: state, track, album, length (ms), position (s) and the art's address. */
	private static final String READ = String.join("\n",
		"if application \"Spotify\" is running then",
		"  tell application \"Spotify\"",
		"    set s to player state as string",
		"    if s is \"stopped\" then return \"stopped\"",
		"    set t to current track",
		"    set d to ASCII character 31",
		"    return s & d & (name of t) & d & (artist of t) & d & (album of t) & d & (duration of t) & d & (player position) & d & (artwork url of t) & d & (sound volume)",
		"  end tell",
		"end if",
		"return \"off\"");

	private final int artSize;
	private String artUrl;
	private float volume = -1;
	private boolean warned;

	MacSpotify(int artSize) {
		this.artSize = artSize;
	}

	static boolean supported() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
	}

	@Override
	public boolean open() {
		return supported();
	}

	@Override
	public void close() {
	}

	@Override
	public int pollMillis() {
		// Each look starts a small process, so not as often as on Windows; positions are carried forward between
		return 900;
	}

	/** Whether Spotify's running, looked at from here so AppleScript never goes looking for it (or asks where it is). */
	static boolean running() {
		return ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c -> c.endsWith("/Spotify.app/Contents/MacOS/Spotify")).orElse(false));
	}

	@Override
	public WindowsMedia.Track track() {
		String out = running() ? run(READ) : null;
		if (out == null || !out.contains(SEP)) {
			artUrl = null;
			volume = -1;
			return null;
		}
		String[] parts = out.split(SEP, -1);
		if (parts.length < 7) return null;
		volume = parts.length > 7 ? (float) Math.max(0, Math.min(1, number(parts[7]) / 100)) : -1;
		artUrl = parts[6].isBlank() ? null : parts[6].trim();
		return new WindowsMedia.Track(parts[1], parts[2], parts[3], parts[0].equals("playing"), number(parts[5]), number(parts[4]) / 1000.0, System.currentTimeMillis());
	}

	/** A number from AppleScript, which writes decimals with a comma in some languages. */
	private static double number(String text) {
		try {
			return Double.parseDouble(text.trim().replace(',', '.'));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Override
	public WindowsMedia.Art art() {
		String url = artUrl;
		if (url == null || !url.startsWith("https://")) return null;
		try {
			HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
			return response.statusCode() == 200 ? WindowsMedia.decode(response.body(), artSize) : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public void control(int which) {
		String command = switch (which) {
			case TOGGLE_PLAY_PAUSE -> "playpause";
			case SKIP_NEXT -> "next track";
			case SKIP_PREVIOUS -> "previous track";
			default -> null;
		};
		if (command != null) tell(command);
	}

	@Override
	public float volume() {
		return volume;
	}

	@Override
	public void setVolume(float volume) {
		tell("set sound volume to " + Math.round(Math.max(0, Math.min(1, volume)) * 100));
	}

	@Override
	public void seek(double seconds) {
		tell("set player position to " + String.format(Locale.ROOT, "%.2f", Math.max(0, seconds)));
	}

	private void tell(String command) {
		if (running()) run("if application \"Spotify\" is running then tell application \"Spotify\" to " + command);
	}

	/** Runs some AppleScript and returns what it printed, or null if it failed. */
	private String run(String script) {
		Process process = null;
		try {
			process = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(false).start();
			process.getOutputStream().close();
			// What it prints is tiny, so it all fits in the pipe while we wait
			if (!process.waitFor(4, TimeUnit.SECONDS)) return null;
			byte[] out;
			try (InputStream in = process.getInputStream()) {
				out = in.readAllBytes();
			}
			if (process.exitValue() != 0) {
				if (!warned) {
					warned = true;
					String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
					if (!error.isEmpty()) FemboyWare.LOGGER.info("Couldn't talk to Spotify: {}", error);
				}
				return null;
			}
			return new String(out, StandardCharsets.UTF_8).strip();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			return null;
		} finally {
			if (process != null && process.isAlive()) process.destroyForcibly();
		}
	}
}
