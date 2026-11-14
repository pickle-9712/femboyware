package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.util.ChatUtil;

/**
 * Hides the game window from screen capture (OBS, Discord streams, screenshots tools) while you
 * still see and play it normally, using Windows' SetWindowDisplayAffinity. Capture shows black
 * instead. Needs Windows 10 2004 or newer.
 */
public class StreamProofModule extends Module {
	/** WDA_EXCLUDEFROMCAPTURE: left out of capture entirely. */
	private static final int EXCLUDE = 0x11;
	private static final int NONE = 0;

	public StreamProofModule() {
		super("Stream Proof", "Hides the game from OBS and Discord capture while you still see and use it", Category.MISC);
	}

	/** Applied yet this time it was turned on; it's retried each tick until the game window is in front. */
	private boolean applied;
	/** The window it was applied to, to undo it on even while the game isn't in front. */
	private long appliedTo;

	@Override
	protected void onEnable() {
		applied = false;
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
			ChatUtil.info("Stream Proof only works on Windows");
			setEnabled(false);
			return;
		}
		tryApply();
	}

	@Override
	protected void onDisable() {
		if (applied) apply(appliedTo, NONE);
		applied = false;
	}

	@Override
	public void onTick() {
		if (!applied) tryApply();
	}

	private void tryApply() {
		long hwnd = window();
		if (hwnd == 0) return;
		applied = apply(hwnd, EXCLUDE);
		appliedTo = hwnd;
		if (!applied) {
			ChatUtil.info("Stream Proof needs Windows 10 2004 or newer");
			setEnabled(false);
		}
	}

	/**
	 * The game's window: the one in front, as long as it belongs to this process. Found through
	 * user32 rather than the windowing library, which differs between versions.
	 */
	private static long window() {
		try {
			com.sun.jna.Function foreground = com.sun.jna.Function.getFunction("user32", "GetForegroundWindow");
			com.sun.jna.Pointer hwnd = (com.sun.jna.Pointer) foreground.invoke(com.sun.jna.Pointer.class, new Object[0]);
			if (hwnd == null) return 0;
			com.sun.jna.Memory pid = new com.sun.jna.Memory(4);
			com.sun.jna.Function.getFunction("user32", "GetWindowThreadProcessId").invokeInt(new Object[]{hwnd, pid});
			if (pid.getInt(0) != ProcessHandle.current().pid()) return 0;
			return com.sun.jna.Pointer.nativeValue(hwnd);
		} catch (Throwable e) {
			FemboyWare.LOGGER.warn("Stream Proof couldn't find the game window", e);
			return 0;
		}
	}

	private static boolean apply(long hwnd, int affinity) {
		if (hwnd == 0) return false;
		try {
			com.sun.jna.Function function = com.sun.jna.Function.getFunction("user32", "SetWindowDisplayAffinity");
			return function.invokeInt(new Object[]{new com.sun.jna.Pointer(hwnd), affinity}) != 0;
		} catch (Throwable e) {
			FemboyWare.LOGGER.warn("Stream Proof couldn't change the window's capture setting", e);
			return false;
		}
	}
}
