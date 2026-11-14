package dev.femboyware.module.larp;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Records what one program on your PC is playing (and its child processes: a browser's audio runs
 * in one), with Windows' process loopback capture (Windows 10 build 20348 or later, and 11).
 * Delivers 48 kHz 16-bit stereo, a chunk at a time, on a thread of its own.
 */
public final class ProcessAudio {
	public static final int RATE = 48000, CHANNELS = 2;
	private static final int S_OK = 0;
	private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000, AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000,
		AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000, AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
	private static final Guid.GUID IID_IAUDIOCLIENT = new Guid.GUID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}");
	private static final Guid.GUID IID_IAUDIOCAPTURECLIENT = new Guid.GUID("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}");

	private final int pid;
	private final Consumer<short[]> sink;
	private volatile boolean running = true;
	private volatile String problem;

	/** Starts recording {@code pid}'s sound (with its children), giving each chunk to {@code sink}. */
	public ProcessAudio(int pid, Consumer<short[]> sink) {
		this.pid = pid;
		this.sink = sink;
		Thread thread = new Thread(this::run, "femboyware cast audio");
		thread.setDaemon(true);
		thread.start();
	}

	/** Why it isn't recording, or null. */
	public String problem() {
		return problem;
	}

	public void stop() {
		running = false;
	}

	// ---------------------------------------------------------------- COM by hand

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

	interface QueryInterface extends Callback {
		int invoke(Pointer self, Pointer riid, Pointer out);
	}

	interface RefCount extends Callback {
		int invoke(Pointer self);
	}

	interface Completed extends Callback {
		int invoke(Pointer self, Pointer operation);
	}

	private void run() {
		Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
		Pointer client = null, capture = null;
		WinNT.HANDLE event = null;
		try {
			// The completion handler: a tiny COM object of our own (QueryInterface, AddRef, Release, ActivateCompleted)
			CountDownLatch done = new CountDownLatch(1);
			Pointer[] operation = new Pointer[1];
			Memory self = new Memory(Native.POINTER_SIZE);
			QueryInterface qi = (me, riid, out) -> {
				out.setPointer(0, me);
				return S_OK;
			};
			RefCount ref = me -> 1;
			Completed completed = (me, op) -> {
				operation[0] = op;
				done.countDown();
				return S_OK;
			};
			Memory vtable = new Memory(4L * Native.POINTER_SIZE);
			vtable.setPointer(0, CallbackReference.getFunctionPointer(qi));
			vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(ref));
			vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(ref));
			vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(completed));
			self.setPointer(0, vtable);

			// AUDIOCLIENT_ACTIVATION_PARAMS: process loopback, this process and its children, included
			Memory params = new Memory(12);
			params.setInt(0, 1);
			params.setInt(4, pid);
			params.setInt(8, 0);
			// A PROPVARIANT holding it as a blob
			Memory variant = new Memory(24);
			variant.clear();
			variant.setShort(0, (short) 65);
			variant.setInt(8, 12);
			variant.setPointer(16, params);
			IID_IAUDIOCLIENT.write();
			PointerByReference async = new PointerByReference();
			Function activate = NativeLibrary.getInstance("Mmdevapi").getFunction("ActivateAudioInterfaceAsync");
			int hr = activate.invokeInt(new Object[]{new WString("VAD\\Process_Loopback"), IID_IAUDIOCLIENT.getPointer(), variant, self, async});
			if (hr != S_OK) throw new IllegalStateException("this Windows can't record one app's sound (" + Integer.toHexString(hr) + ")");
			if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Windows didn't start recording");
			IntByReference result = new IntByReference();
			PointerByReference unknown = new PointerByReference();
			call(operation[0], 3, result, unknown);
			release(async.getValue());
			if (result.getValue() != S_OK || unknown.getValue() == null) throw new IllegalStateException("couldn't record the browser (" + Integer.toHexString(result.getValue()) + ")");
			client = unknown.getValue();
			// Keeps the callbacks alive as long as the client
			Object[] keep = {qi, ref, completed, vtable, self, params, variant};

			// WAVEFORMATEX: PCM, stereo, 48 kHz, 16 bit
			Memory format = new Memory(18);
			format.setShort(0, (short) 1);
			format.setShort(2, (short) CHANNELS);
			format.setInt(4, RATE);
			format.setInt(8, RATE * CHANNELS * 2);
			format.setShort(12, (short) (CHANNELS * 2));
			format.setShort(14, (short) 16);
			format.setShort(16, (short) 0);
			hr = call(client, 3, 0, AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
				200_000L, 0L, format, Pointer.NULL);
			if (hr != S_OK) throw new IllegalStateException("couldn't start recording (" + Integer.toHexString(hr) + ")");
			event = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
			call(client, 13, event);
			IID_IAUDIOCAPTURECLIENT.write();
			PointerByReference captureRef = new PointerByReference();
			hr = call(client, 14, IID_IAUDIOCAPTURECLIENT.getPointer(), captureRef);
			if (hr != S_OK) throw new IllegalStateException("couldn't record (" + Integer.toHexString(hr) + ")");
			capture = captureRef.getValue();
			call(client, 10);

			IntByReference packet = new IntByReference(), frames = new IntByReference(), flags = new IntByReference();
			PointerByReference data = new PointerByReference();
			while (running) {
				Kernel32.INSTANCE.WaitForSingleObject(event, 100);
				while (running && call(capture, 5, packet) == S_OK && packet.getValue() > 0) {
					if (call(capture, 3, data, frames, flags, Pointer.NULL, Pointer.NULL) != S_OK) break;
					int n = frames.getValue();
					short[] chunk = new short[n * CHANNELS];
					if ((flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) == 0 && data.getValue() != null) data.getValue().read(0, chunk, 0, chunk.length);
					call(capture, 4, n);
					if (n > 0) sink.accept(chunk);
				}
			}
			call(client, 11);
			if (keep.length == 0) throw new AssertionError();
		} catch (Throwable t) {
			problem = t.getMessage() == null ? t.toString() : t.getMessage();
		} finally {
			release(capture);
			release(client);
			if (event != null) Kernel32.INSTANCE.CloseHandle(event);
			Ole32.INSTANCE.CoUninitialize();
		}
	}
}
