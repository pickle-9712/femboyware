package dev.femboyware.module.hud;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Reads Windows' media session (what Spotify, a browser or anything else shows in the volume flyout)
 * straight through its WinRT API from Java, with JNA, which Minecraft already ships. Nothing is
 * launched outside the game.
 *
 * <p>WinRT objects are COM objects, so each call goes through the object's vtable. The slot numbers
 * below follow each interface's method order; slots 0-5 are IUnknown and IInspectable. Every call
 * here must be made on the one thread that called {@link #open()}.
 */
final class WindowsMedia implements MediaPlayer {
	/** One snapshot of the current track. Times are in seconds; {@code updated} is epoch millis. */
	record Track(String title, String artist, String album, boolean playing, double position, double length, long updated) {
		Track(String title, String artist, boolean playing, double position, double length, long updated) {
			this(title, artist, "", playing, position, length, updated);
		}
	}

	/** Album art, decoded and scaled down, as ARGB pixels. */
	record Art(int width, int height, int[] pixels) {}

	private static final String MANAGER_CLASS = "Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager";
	private static final Guid.IID MANAGER_STATICS = new Guid.IID("{2050C4EE-11A0-57DE-AED7-C97C70338245}");
	private static final Guid.IID ASYNC_INFO = new Guid.IID("{00000036-0000-0000-C000-000000000046}");
	private static final Guid.IID RANDOM_ACCESS_STREAM = new Guid.IID("{905A0FE1-BC53-11DF-8C49-001E4FC686DA}");
	private static final Guid.IID STREAM = new Guid.IID("{0000000C-0000-0000-C000-000000000046}");
	private static final int MAX_ART_BYTES = 8 << 20;
	/** 100ns ticks between 1601, where WinRT dates start, and 1970. */
	private static final long EPOCH_TICKS = 116444736000000000L;

	private static NativeLibrary combase;
	private static NativeLibrary shcore;

	private Pointer manager;
	/** Only a session from an app whose id has this in it (like "spotify"), or null for the current one. */
	private final String app;
	private final int artSize;

	WindowsMedia(String app, int artSize) {
		this.app = app == null ? null : app.toLowerCase();
		this.artSize = artSize;
	}

	@Override
	public int pollMillis() {
		return 250;
	}

	static boolean supported() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	}

	/** Joins WinRT on this thread and gets the session manager. False if Windows won't give it to us. */
	@Override
	public boolean open() {
		if (combase == null) {
			combase = NativeLibrary.getInstance("combase");
			shcore = NativeLibrary.getInstance("shcore");
		}
		// 1 = multithreaded; an already-joined thread just returns a harmless code
		combase.getFunction("RoInitialize").invokeInt(new Object[]{1});
		Pointer name = string(MANAGER_CLASS);
		PointerByReference statics = new PointerByReference();
		try {
			if (combase.getFunction("RoGetActivationFactory").invokeInt(new Object[]{name, MANAGER_STATICS, statics}) < 0) return false;
		} finally {
			combase.getFunction("WindowsDeleteString").invokeInt(new Object[]{name});
		}
		try {
			manager = await(get(statics.getValue(), 6)); // RequestAsync
		} finally {
			release(statics.getValue());
		}
		return manager != null;
	}

	@Override
	public void close() {
		release(manager);
		manager = null;
	}

	/** The current track, or null when nothing is playing. */
	@Override
	public Track track() {
		Pointer session = session();
		if (session == null) return null;
		Pointer props = null, timeline = null, playback = null;
		try {
			props = await(get(session, 7)); // TryGetMediaPropertiesAsync
			timeline = get(session, 8); // GetTimelineProperties
			playback = get(session, 9); // GetPlaybackInfo
			if (props == null) return null;
			String title = hstring(props, 6), artist = hstring(props, 9), album = hstring(props, 10);
			IntByReference status = new IntByReference();
			// 4 = Playing
			boolean playing = playback != null && call(playback, 7, status) >= 0 && status.getValue() == 4;
			double position = 0, length = 0;
			long updated = System.currentTimeMillis();
			if (timeline != null) {
				length = ticks(timeline, 7) / 1e7; // EndTime
				position = ticks(timeline, 10) / 1e7; // Position
				long changed = ticks(timeline, 11); // LastUpdatedTime
				if (changed > EPOCH_TICKS) updated = (changed - EPOCH_TICKS) / 10_000;
			}
			return new Track(title, artist, album, playing, position, length, updated);
		} finally {
			release(props);
			release(timeline);
			release(playback);
			release(session);
		}
	}

	/** The current track's album art, or null if it has none. */
	@Override
	public Art art() {
		Pointer session = session();
		if (session == null) return null;
		Pointer props = null, thumbnail = null, content = null, randomAccess = null, stream = null;
		try {
			props = await(get(session, 7));
			if (props == null) return null;
			thumbnail = get(props, 15); // Thumbnail
			if (thumbnail == null) return null;
			content = await(get(thumbnail, 6)); // OpenReadAsync
			if (content == null) return null;
			randomAccess = query(content, RANDOM_ACCESS_STREAM);
			if (randomAccess == null) return null;
			// Wrap it as a classic COM stream, whose Read is a plain blocking call
			PointerByReference out = new PointerByReference();
			if (shcore.getFunction("CreateStreamOverRandomAccessStream").invokeInt(new Object[]{randomAccess, STREAM, out}) < 0) return null;
			stream = out.getValue();
			return decode(readAll(stream), artSize);
		} catch (Exception e) {
			return null;
		} finally {
			release(stream);
			release(randomAccess);
			release(content);
			release(thumbnail);
			release(props);
			release(session);
		}
	}

	/** Runs one of the session's Try...Async controls, without waiting for it. */
	@Override
	public void control(int slot) {
		Pointer session = session();
		if (session == null) return;
		release(get(session, slot));
		release(session);
	}

	@Override
	public void seek(double seconds) {
		Pointer session = session();
		if (session == null) return;
		PointerByReference out = new PointerByReference();
		// TryChangePlaybackPositionAsync, in 100ns ticks
		if (call(session, 24, Math.max(0, Math.round(seconds * 1e7)), out) >= 0) release(out.getValue());
		release(session);
	}

	private Pointer session() {
		if (manager == null) return null;
		if (app == null) return get(manager, 6); // GetCurrentSession
		// The app's own session, wherever it is in the list
		Pointer sessions = get(manager, 7); // GetSessions
		if (sessions == null) return null;
		try {
			IntByReference size = new IntByReference();
			if (call(sessions, 7, size) < 0) return null; // get_Size
			for (int i = 0; i < size.getValue(); i++) {
				PointerByReference out = new PointerByReference();
				if (call(sessions, 6, i, out) < 0 || out.getValue() == null) continue; // GetAt
				Pointer session = out.getValue();
				if (hstring(session, 6).toLowerCase().contains(app)) return session; // SourceAppUserModelId
				release(session);
			}
			return null;
		} finally {
			release(sessions);
		}
	}

	private static byte[] readAll(Pointer stream) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Memory chunk = new Memory(64 * 1024);
		IntByReference read = new IntByReference();
		while (bytes.size() < MAX_ART_BYTES) {
			// IStream::Read
			if (call(stream, 3, chunk, (int) chunk.size(), read) < 0 || read.getValue() <= 0) break;
			bytes.write(chunk.getByteArray(0, read.getValue()), 0, read.getValue());
		}
		return bytes.toByteArray();
	}

	/** Decodes the art (usually a JPEG or PNG) and scales it down smoothly for the HUD. */
	static Art decode(byte[] data, int maxSize) throws Exception {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
		if (image == null) return null;
		// Crop to a centred square, as some apps send letterboxed video frames
		int side = Math.min(image.getWidth(), image.getHeight());
		int size = Math.min(maxSize, side);
		BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = scaled.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		int x = (image.getWidth() - side) / 2, y = (image.getHeight() - side) / 2;
		graphics.drawImage(image, 0, 0, size, size, x, y, x + side, y + side, null);
		graphics.dispose();
		return new Art(size, size, scaled.getRGB(0, 0, size, size, null, 0, size));
	}

	/** Waits for a WinRT async operation and returns its result, or null if it failed or took too long. */
	private static Pointer await(Pointer operation) {
		if (operation == null) return null;
		try {
			Pointer info = query(operation, ASYNC_INFO);
			if (info == null) return null;
			try {
				IntByReference status = new IntByReference();
				long deadline = System.currentTimeMillis() + 3000;
				while (true) {
					if (call(info, 7, status) < 0) return null; // get_Status
					if (status.getValue() == 1) break; // Completed
					if (status.getValue() != 0 || System.currentTimeMillis() > deadline) return null; // Canceled or Error
					Thread.sleep(2);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			} finally {
				release(info);
			}
			return get(operation, 8); // GetResults
		} finally {
			release(operation);
		}
	}

	/** Calls a method whose only argument is an out object pointer, returning it (or null). */
	private static Pointer get(Pointer object, int slot) {
		PointerByReference out = new PointerByReference();
		return call(object, slot, out) >= 0 ? out.getValue() : null;
	}

	private static String hstring(Pointer object, int slot) {
		PointerByReference out = new PointerByReference();
		if (call(object, slot, out) < 0 || out.getValue() == null) return "";
		try {
			IntByReference length = new IntByReference();
			Pointer chars = combase.getFunction("WindowsGetStringRawBuffer").invokePointer(new Object[]{out.getValue(), length});
			return chars == null ? "" : new String(chars.getCharArray(0, length.getValue()));
		} finally {
			combase.getFunction("WindowsDeleteString").invokeInt(new Object[]{out.getValue()});
		}
	}

	private static long ticks(Pointer object, int slot) {
		LongByReference out = new LongByReference();
		return call(object, slot, out) >= 0 ? out.getValue() : 0;
	}

	private static Pointer string(String text) {
		PointerByReference out = new PointerByReference();
		combase.getFunction("WindowsCreateString").invokeInt(new Object[]{new WString(text), text.length(), out});
		return out.getValue();
	}

	private static Pointer query(Pointer object, Guid.IID iid) {
		PointerByReference out = new PointerByReference();
		return call(object, 0, iid, out) >= 0 ? out.getValue() : null;
	}

	private static void release(Pointer object) {
		if (object != null) call(object, 2);
	}

	/** Calls vtable slot {@code slot} on a COM object, returning its HRESULT. */
	private static int call(Pointer object, int slot, Object... args) {
		Pointer method = object.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE);
		Object[] full = new Object[args.length + 1];
		full[0] = object;
		System.arraycopy(args, 0, full, 1, args.length);
		return Function.getFunction(method, Function.ALT_CONVENTION).invokeInt(full);
	}
}
