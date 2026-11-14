package dev.femboyware.module.hud;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import dev.femboyware.FemboyWare;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One app's own volume on Windows: its slider in the Volume mixer, through Core Audio's audio
 * sessions (ISimpleAudioVolume), by hand over COM. An app shows up there once it has played
 * sound since it started; before then there's nothing to read or set.
 */
final class WinAppVolume {
	private static final int CLSCTX_ALL = 0x17, COINIT_MULTITHREADED = 0, E_RENDER = 0, E_MULTIMEDIA = 1;
	private static final Guid.GUID CLSID_ENUMERATOR = new Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
	private static final Guid.GUID IID_ENUMERATOR = new Guid.GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
	private static final Guid.GUID IID_SESSION_MANAGER2 = new Guid.GUID("{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}");
	private static final Guid.GUID IID_SESSION_CONTROL2 = new Guid.GUID("{BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}");
	private static final Guid.GUID IID_SIMPLE_VOLUME = new Guid.GUID("{87CE5498-68D6-44E5-9215-6DA47EF883D8}");
	private static boolean warned;

	private WinAppVolume() {
	}

	/** The volume (0 to 1) of the app whose program name contains {@code app}, or -1 if it has no sound session. */
	static float get(String app) {
		List<Pointer> volumes = volumes(app);
		try {
			if (volumes.isEmpty()) return -1;
			FloatByReference level = new FloatByReference();
			return call(volumes.get(0), 4, level.getPointer()) == 0 ? level.getValue() : -1; // GetMasterVolume
		} finally {
			volumes.forEach(WinAppVolume::release);
		}
	}

	/** Sets the volume of every sound session of the app whose program name contains {@code app}. */
	static void set(String app, float volume) {
		float level = Math.max(0, Math.min(1, volume));
		List<Pointer> volumes = volumes(app);
		try {
			for (Pointer v : volumes) call(v, 3, level, Pointer.NULL); // SetMasterVolume
		} finally {
			volumes.forEach(WinAppVolume::release);
		}
	}

	/** Every ISimpleAudioVolume (on the default speakers) belonging to the app; the caller releases them. */
	private static List<Pointer> volumes(String app) {
		List<Pointer> found = new ArrayList<>();
		String want = app.toLowerCase(Locale.ROOT);
		// Fine if this thread's already in COM (it returns S_FALSE, or RPC_E_CHANGED_MODE when it's STA)
		Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, COINIT_MULTITHREADED);
		Pointer enumerator = null, device = null, manager = null, sessions = null;
		try {
			PointerByReference out = new PointerByReference();
			if (Ole32.INSTANCE.CoCreateInstance(CLSID_ENUMERATOR, Pointer.NULL, CLSCTX_ALL, IID_ENUMERATOR, out).intValue() != 0) return found;
			enumerator = out.getValue();
			if (call(enumerator, 4, E_RENDER, E_MULTIMEDIA, out.getPointer()) != 0) return found; // GetDefaultAudioEndpoint
			device = out.getValue();
			if (call(device, 3, guid(IID_SESSION_MANAGER2), CLSCTX_ALL, Pointer.NULL, out.getPointer()) != 0) return found; // Activate
			manager = out.getValue();
			if (call(manager, 5, out.getPointer()) != 0) return found; // GetSessionEnumerator
			sessions = out.getValue();
			IntByReference count = new IntByReference();
			if (call(sessions, 3, count.getPointer()) != 0) return found; // GetCount
			for (int i = 0; i < count.getValue(); i++) {
				if (call(sessions, 4, i, out.getPointer()) != 0) continue; // GetSession
				Pointer control = out.getValue();
				try {
					if (call(control, 0, guid(IID_SESSION_CONTROL2), out.getPointer()) != 0) continue;
					Pointer control2 = out.getValue();
					IntByReference pid = new IntByReference();
					int hr = call(control2, 14, pid.getPointer()); // GetProcessId
					release(control2);
					if (hr != 0 || pid.getValue() == 0 || !isApp(pid.getValue(), want)) continue;
					if (call(control, 0, guid(IID_SIMPLE_VOLUME), out.getPointer()) == 0) found.add(out.getValue());
				} finally {
					release(control);
				}
			}
		} catch (Throwable e) {
			if (!warned) {
				warned = true;
				FemboyWare.LOGGER.info("Couldn't reach Windows' app volumes", e);
			}
		} finally {
			release(sessions);
			release(manager);
			release(device);
			release(enumerator);
		}
		return found;
	}

	private static boolean isApp(int pid, String want) {
		return ProcessHandle.of(pid).flatMap(p -> p.info().command())
			.map(c -> c.substring(Math.max(c.lastIndexOf('\\'), c.lastIndexOf('/')) + 1).toLowerCase(Locale.ROOT).contains(want))
			.orElse(false);
	}

	/** The GUID as COM wants it in memory (toByteArray is in written-out order, which COM doesn't take). */
	private static Pointer guid(Guid.GUID id) {
		id.write();
		return id.getPointer();
	}

	private static int call(Pointer object, int index, Object... args) {
		Pointer vtable = object.getPointer(0);
		Function function = Function.getFunction(vtable.getPointer((long) index * Native.POINTER_SIZE));
		Object[] all = new Object[args.length + 1];
		all[0] = object;
		System.arraycopy(args, 0, all, 1, args.length);
		return function.invokeInt(all);
	}

	private static void release(Pointer object) {
		if (object != null) call(object, 2);
	}
}
