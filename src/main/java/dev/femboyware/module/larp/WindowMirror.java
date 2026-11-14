package dev.femboyware.module.larp;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows another window on your PC (your browser on TikTok or YouTube) on the TV, or a whole screen.
 * A window (Windows only) draws itself into a bitmap here, so it works behind Minecraft as long as
 * it isn't minimised, and scrolling sends it the arrow keys, which TikTok uses for next and previous
 * video. A whole screen is copied straight off the monitor on Windows, and with macOS's own
 * {@code screencapture} on a Mac (which needs Screen Recording allowed for Minecraft).
 */
public final class WindowMirror {
	private static final int PW_CLIENTONLY = 1, PW_RENDERFULLCONTENT = 2;
	private static final int WM_KEYDOWN = 0x0100, WM_KEYUP = 0x0101, VK_UP = 0x26, VK_DOWN = 0x28, VK_SPACE = 0x20;
	/** Captures wider than this are sampled down as they're copied out. */
	private static final int MAX_WIDTH = 1280;

	/** Whether windows can be mirrored: Windows only. */
	public static boolean supported() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	private static boolean mac() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
	}

	/** Whether a whole screen can be mirrored: Windows or a Mac. */
	public static boolean screenSupported() {
		return supported() || mac();
	}

	/**
	 * A whole screen: number {@code which} (from 1), or with 0 the one Minecraft isn't on if
	 * there's more than one (so the TV doesn't show itself forever), else the main one.
	 */
	public static WindowMirror screen(int which) {
		return new WindowMirror(null, Math.max(0, which));
	}

	/** How many screens there are, on Windows; 1 elsewhere. */
	public static int screens() {
		return supported() ? monitors().size() : 1;
	}

	/** Every monitor's rectangle, the main one first. */
	private static List<WinDef.RECT> monitors() {
		List<WinDef.RECT> out = new ArrayList<>();
		User32.INSTANCE.EnumDisplayMonitors(null, null, (monitor, dc, rect, data) -> {
			WinUser.MONITORINFO info = new WinUser.MONITORINFO();
			if (User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue()) {
				if ((info.dwFlags & WinUser.MONITORINFOF_PRIMARY) != 0) out.addFirst(info.rcMonitor);
				else out.add(info.rcMonitor);
			}
			return 1;
		}, new WinDef.LPARAM(0));
		return out;
	}

	public record Found(WinDef.HWND hwnd, String title) {
	}

	/** Visible, titled top-level windows other than Minecraft's own, browsers first. */
	public static List<Found> windows() {
		List<Found> out = new ArrayList<>();
		int self = (int) ProcessHandle.current().pid();
		User32.INSTANCE.EnumWindows((hwnd, data) -> {
			if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;
			IntByReference pid = new IntByReference();
			User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
			if (pid.getValue() == self) return true;
			String title = titleOf(hwnd);
			if (title.isEmpty()) return true;
			if (title.equals("Program Manager") || title.equals("Settings") || title.startsWith("Windows Input Experience")) return true;
			out.add(new Found(hwnd, title));
			return true;
		}, null);
		out.sort((a, b) -> Boolean.compare(!browser(a.title), !browser(b.title)));
		return out;
	}

	private static boolean browser(String title) {
		String t = title.toLowerCase(Locale.ROOT);
		return t.contains("chrome") || t.contains("edge") || t.contains("firefox") || t.contains("opera") || t.contains("brave") || t.contains("tiktok") || t.contains("youtube");
	}

	/** The first window whose title has {@code query} in it. */
	public static Found find(String query) {
		String q = query.toLowerCase(Locale.ROOT);
		for (Found found : windows()) if (found.title.toLowerCase(Locale.ROOT).contains(q)) return found;
		return null;
	}

	private final WinDef.HWND hwnd;
	/** For a whole screen: which one (0 picks); -1 for a window. */
	private final int screen;
	private java.nio.file.Path macShot;
	private WinDef.HDC memory;
	private WinDef.HBITMAP bitmap;
	private WinNT.HANDLE previous;
	private Pointer bits;
	private int bitmapW, bitmapH;
	/** The capture as drawn last time, to tell when the window stopped painting. */
	private int lastHash, sameFrames;

	public WindowMirror(WinDef.HWND hwnd) {
		this(hwnd, -1);
	}

	private WindowMirror(WinDef.HWND hwnd, int screen) {
		this.hwnd = hwnd;
		this.screen = screen;
	}

	public boolean wholeScreen() {
		return screen >= 0;
	}

	/** Why a capture came back empty, to tell you. */
	public String whyNot() {
		if (!wholeScreen()) return minimised() ? "the window's minimised (restore it; it can sit behind Minecraft)" : "the window can't be copied";
		return mac() ? "your screen can't be copied: allow Screen Recording for Minecraft in System Settings, Privacy & Security" : "your screen can't be copied";
	}

	/** The program that owns the window (for a browser, its main process; its sound comes from a child of it). */
	public int pid() {
		if (wholeScreen()) return 0;
		IntByReference pid = new IntByReference();
		User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
		return pid.getValue();
	}

	public String title() {
		if (wholeScreen()) return screen == 0 ? "your screen" : "screen " + screen;
		return titleOf(hwnd);
	}

	/**
	 * A window's title, read straight from Windows rather than asked of its program (as
	 * GetWindowText does), so a program that's stopped responding can't freeze the game.
	 */
	private static String titleOf(WinDef.HWND hwnd) {
		char[] text = new char[512];
		int length = User32Extra.INSTANCE.InternalGetWindowText(hwnd, text, text.length);
		return length > 0 ? new String(text, 0, length) : "";
	}

	public boolean alive() {
		return wholeScreen() || User32.INSTANCE.IsWindow(hwnd);
	}

	public boolean minimised() {
		return !wholeScreen() && User32Extra.INSTANCE.IsIconic(hwnd);
	}

	/** How many captures in a row came out exactly the same (the window stopped drawing, or it's a still page). */
	public int unchanged() {
		return sameFrames;
	}

	public record Capture(int width, int height, int[] argb) {
	}

	/**
	 * The window as it looks now, cropped by the fractions given (from each side), or null if it
	 * can't be drawn (closed, minimised or zero size).
	 */
	public Capture capture(float cropLeft, float cropRight, float cropTop, float cropBottom) {
		if (wholeScreen() && mac()) return macCapture(cropLeft, cropRight, cropTop, cropBottom);
		if (!alive() || minimised()) return null;
		WinDef.RECT rect = new WinDef.RECT();
		if (wholeScreen()) {
			WinDef.RECT monitor = pickMonitor();
			if (monitor == null) return null;
			rect = monitor;
		} else if (!User32.INSTANCE.GetClientRect(hwnd, rect)) return null;
		int w = rect.right - rect.left, h = rect.bottom - rect.top;
		if (w < 16 || h < 16) return null;
		ensureBitmap(w, h);
		if (wholeScreen()) {
			// Straight off the monitor: what's on screen right now
			WinDef.HDC desktop = User32.INSTANCE.GetDC(null);
			boolean copied = GDI32.INSTANCE.BitBlt(memory, 0, 0, w, h, desktop, rect.left, rect.top, GDI32.SRCCOPY | 0x40000000);
			User32.INSTANCE.ReleaseDC(null, desktop);
			if (!copied) return null;
		} else if (!User32.INSTANCE.PrintWindow(hwnd, memory, PW_CLIENTONLY | PW_RENDERFULLCONTENT)) return null;
		int x0 = (int) (w * cropLeft), x1 = (int) (w * (1 - cropRight)), y0 = (int) (h * cropTop), y1 = (int) (h * (1 - cropBottom));
		if (x1 - x0 < 8 || y1 - y0 < 8) {
			x0 = 0;
			x1 = w;
			y0 = 0;
			y1 = h;
		}
		int step = Math.max(1, (x1 - x0 + MAX_WIDTH - 1) / MAX_WIDTH);
		int ow = (x1 - x0) / step, oh = (y1 - y0) / step;
		int[] out = new int[ow * oh];
		int[] row = new int[x1 - x0];
		int hash = 1;
		for (int y = 0; y < oh; y++) {
			// The DIB is top-down 32-bit BGRA, which reads as ARGB in little-endian ints
			bits.read(((long) (y0 + y * step) * bitmapW + x0) * 4, row, 0, row.length);
			for (int x = 0; x < ow; x++) {
				int c = row[x * step] | 0xFF000000;
				out[y * ow + x] = c;
				if ((x & 15) == 0) hash = hash * 31 + c;
			}
		}
		// A still screen is fine; only a window that stopped drawing is worth mentioning
		sameFrames = !wholeScreen() && hash == lastHash ? sameFrames + 1 : 0;
		lastHash = hash;
		return new Capture(ow, oh, out);
	}

	private List<WinDef.RECT> monitorList;
	private long monitorsAt;

	/** The monitor to copy: the one asked for, or the first Minecraft isn't on. Looked up again every few seconds. */
	private WinDef.RECT pickMonitor() {
		long now = System.currentTimeMillis();
		if (monitorList == null || now - monitorsAt > 3000) {
			monitorList = monitors();
			monitorsAt = now;
		}
		if (monitorList.isEmpty()) return null;
		if (screen > 0) return monitorList.get(Math.min(screen, monitorList.size()) - 1);
		var window = net.minecraft.client.Minecraft.getInstance().getWindow();
		int cx = window.getX() + window.getWidth() / 2, cy = window.getY() + window.getHeight() / 2;
		for (WinDef.RECT r : monitorList) if (cx < r.left || cx >= r.right || cy < r.top || cy >= r.bottom) return r;
		return monitorList.getFirst();
	}

	/** A Mac's screen, with screencapture: a JPEG each time, read back and cropped. Main display unless one was asked for. */
	private Capture macCapture(float cropLeft, float cropRight, float cropTop, float cropBottom) {
		try {
			if (macShot == null) {
				macShot = java.nio.file.Files.createTempFile("femboyware-mirror", ".jpg");
				macShot.toFile().deleteOnExit();
			}
			Process process = new ProcessBuilder("screencapture", "-x", "-C", "-t", "jpg", "-D", String.valueOf(Math.max(1, screen)), macShot.toString())
				.redirectErrorStream(true).start();
			process.getInputStream().close();
			if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return null;
			}
			if (process.exitValue() != 0) return null;
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(macShot.toFile());
			if (image == null) return null;
			int w = image.getWidth(), h = image.getHeight();
			int x0 = (int) (w * cropLeft), x1 = (int) (w * (1 - cropRight)), y0 = (int) (h * cropTop), y1 = (int) (h * (1 - cropBottom));
			if (x1 - x0 < 8 || y1 - y0 < 8) {
				x0 = 0;
				x1 = w;
				y0 = 0;
				y1 = h;
			}
			int step = Math.max(1, (x1 - x0 + MAX_WIDTH - 1) / MAX_WIDTH);
			int ow = (x1 - x0) / step, oh = (y1 - y0) / step;
			int[] out = new int[ow * oh];
			for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) out[y * ow + x] = image.getRGB(x0 + x * step, y0 + y * step) | 0xFF000000;
			return new Capture(ow, oh, out);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private void ensureBitmap(int w, int h) {
		if (bitmap != null && w == bitmapW && h == bitmapH) return;
		release();
		WinDef.HDC screen = User32.INSTANCE.GetDC(null);
		memory = GDI32.INSTANCE.CreateCompatibleDC(screen);
		WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
		info.bmiHeader.biWidth = w;
		info.bmiHeader.biHeight = -h;
		info.bmiHeader.biPlanes = 1;
		info.bmiHeader.biBitCount = 32;
		info.bmiHeader.biCompression = WinGDI.BI_RGB;
		PointerByReference pixels = new PointerByReference();
		bitmap = GDI32.INSTANCE.CreateDIBSection(screen, info, WinGDI.DIB_RGB_COLORS, pixels, null, 0);
		User32.INSTANCE.ReleaseDC(null, screen);
		previous = GDI32.INSTANCE.SelectObject(memory, bitmap);
		bits = pixels.getValue();
		bitmapW = w;
		bitmapH = h;
	}

	/** Next ({@code +1}) or previous ({@code -1}) video: the arrow keys, sent to the page itself. */
	public void scroll(int direction) {
		if (wholeScreen()) return;
		key(direction > 0 ? VK_DOWN : VK_UP);
	}

	/** Space: pause and play on TikTok and YouTube. */
	public void togglePause() {
		if (wholeScreen()) return;
		key(VK_SPACE);
	}

	private void key(int vk) {
		WinDef.HWND target = page();
		int scan = User32Extra.INSTANCE.MapVirtualKeyW(vk, 0);
		boolean extended = vk == VK_UP || vk == VK_DOWN;
		long down = 1 | (long) scan << 16 | (extended ? 1L << 24 : 0);
		long up = down | 1L << 30 | 1L << 31;
		User32.INSTANCE.PostMessage(target, WM_KEYDOWN, new WinDef.WPARAM(vk), new WinDef.LPARAM(down));
		User32.INSTANCE.PostMessage(target, WM_KEYUP, new WinDef.WPARAM(vk), new WinDef.LPARAM(up));
	}

	/** The window inside the browser that the page draws into and reads keys from, or the window itself. */
	private WinDef.HWND page() {
		WinDef.HWND[] found = new WinDef.HWND[1];
		User32.INSTANCE.EnumChildWindows(hwnd, (child, data) -> {
			char[] name = new char[128];
			int length = User32.INSTANCE.GetClassName(child, name, name.length);
			String cls = new String(name, 0, Math.max(0, length));
			// Chrome, Edge, Brave and Opera; Firefox takes them on its main window
			if (cls.equals("Chrome_RenderWidgetHostHWND")) {
				found[0] = child;
				return false;
			}
			return true;
		}, null);
		return found[0] != null ? found[0] : hwnd;
	}

	public void release() {
		if (macShot != null) {
			try {
				java.nio.file.Files.deleteIfExists(macShot);
			} catch (java.io.IOException ignored) {
			}
			macShot = null;
		}
		if (memory != null && previous != null) GDI32.INSTANCE.SelectObject(memory, previous);
		if (bitmap != null) GDI32.INSTANCE.DeleteObject(bitmap);
		if (memory != null) GDI32.INSTANCE.DeleteDC(memory);
		memory = null;
		bitmap = null;
		previous = null;
		bits = null;
	}

	/** The couple of calls jna-platform's User32 doesn't have. */
	private interface User32Extra extends com.sun.jna.Library {
		User32Extra INSTANCE = Native.load("user32", User32Extra.class, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

		boolean IsIconic(WinDef.HWND hwnd);

		int MapVirtualKeyW(int code, int mapType);

		int InternalGetWindowText(WinDef.HWND hwnd, char[] text, int max);
	}
}
