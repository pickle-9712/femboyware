package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import dev.femboyware.manager.PeerLink;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A TV mirroring someone's browser, shared live: the caster sends one-second segments (JPEG
 * pictures about 12 times a second, and the browser's own sound as 24 kHz mono ADPCM) through the
 * relay, and everyone watching plays them back a little behind, by however late segments turn up,
 * with the pictures and sound kept together since they come from the same moments.
 */
public final class LiveCast {
	public static final int SEGMENT_MS = 1000, FPS = 12, MAX_SIDE = 560, AUDIO_RATE = 24000;
	private static final int MAGIC = 0x46574C31;
	private static final float JPEG_QUALITY = 0.6f;

	private LiveCast() {
	}

	public static String newId() {
		byte[] id = new byte[16];
		new SecureRandom().nextBytes(id);
		return HexFormat.of().formatHex(id);
	}

	// ---------------------------------------------------------------- IMA ADPCM, 4 bits a sample

	private static final int[] STEPS = {7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
		107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282,
		1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
		12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};
	private static final int[] INDEX = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};

	static byte[] adpcmEncode(short[] samples, int count) {
		byte[] out = new byte[(count + 1) / 2];
		int predicted = 0, index = 0;
		for (int i = 0; i < count; i++) {
			int step = STEPS[index], diff = samples[i] - predicted, code = 0;
			if (diff < 0) {
				code = 8;
				diff = -diff;
			}
			int delta = step >> 3;
			if (diff >= step) {
				code |= 4;
				diff -= step;
				delta += step;
			}
			if (diff >= step >> 1) {
				code |= 2;
				diff -= step >> 1;
				delta += step >> 1;
			}
			if (diff >= step >> 2) {
				code |= 1;
				delta += step >> 2;
			}
			predicted = Math.max(-32768, Math.min(32767, (code & 8) != 0 ? predicted - delta : predicted + delta));
			index = Math.max(0, Math.min(88, index + INDEX[code]));
			if ((i & 1) == 0) out[i >> 1] = (byte) code;
			else out[i >> 1] |= (byte) (code << 4);
		}
		return out;
	}

	static short[] adpcmDecode(byte[] data, int count) {
		short[] out = new short[count];
		int predicted = 0, index = 0;
		for (int i = 0; i < count && (i >> 1) < data.length; i++) {
			int code = (i & 1) == 0 ? data[i >> 1] & 0xF : (data[i >> 1] >> 4) & 0xF;
			int step = STEPS[index], delta = step >> 3;
			if ((code & 4) != 0) delta += step;
			if ((code & 2) != 0) delta += step >> 1;
			if ((code & 1) != 0) delta += step >> 2;
			predicted = Math.max(-32768, Math.min(32767, (code & 8) != 0 ? predicted - delta : predicted + delta));
			index = Math.max(0, Math.min(88, index + INDEX[code]));
			out[i] = (short) predicted;
		}
		return out;
	}

	// ---------------------------------------------------------------- casting

	/** Sends what your TV mirrors: fed its pictures by the mirror and its sound by the browser. */
	public static final class Broadcaster {
		public final String id = newId();
		private final Supplier<PeerLink> link;
		private volatile boolean running = true;
		private volatile int latest = -1;
		private volatile String problem;
		private final ProcessAudio audio;
		private final Object lock = new Object();
		private int[] frame;
		private int frameW, frameH;
		private long frameAt;
		private boolean fresh;
		private short[] sound = new short[AUDIO_RATE * 4];
		private int soundLength;
		private final BlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(3);

		/** Starts casting; {@code pid} is the browser's, whose sound is sent (0 for none). */
		public Broadcaster(Supplier<PeerLink> link, int pid) {
			this.link = link;
			this.audio = pid > 0 ? new ProcessAudio(pid, this::hear) : null;
			Thread cast = new Thread(this::run, "femboyware cast");
			cast.setDaemon(true);
			cast.start();
			Thread upload = new Thread(this::upload, "femboyware cast upload");
			upload.setDaemon(true);
			upload.start();
		}

		/** The newest segment sent, for Client Sync to tell the others where to start. */
		public int latest() {
			return latest;
		}

		/** Why it isn't sending, or why there's no sound; null if all's well. */
		public String problem() {
			String p = problem;
			if (p == null && audio != null && audio.problem() != null) p = "no sound for the others: " + audio.problem();
			return p;
		}

		/** From the mirror: the window as it is now (ABGR, as the TV keeps it). */
		public void offer(int[] abgr, int w, int h) {
			synchronized (lock) {
				frame = abgr;
				frameW = w;
				frameH = h;
				frameAt = System.currentTimeMillis();
				fresh = true;
			}
		}

		/** From the recording: 48 kHz stereo, kept as 24 kHz mono. */
		private void hear(short[] chunk) {
			synchronized (lock) {
				int n = chunk.length / 4;
				if (soundLength + n > sound.length) sound = java.util.Arrays.copyOf(sound, Math.max(sound.length * 2, soundLength + n));
				for (int i = 0; i < n; i++) sound[soundLength++] = (short) ((chunk[i * 4] + chunk[i * 4 + 1] + chunk[i * 4 + 2] + chunk[i * 4 + 3]) / 4);
				// Never more than a few seconds held if the segments stall
				if (soundLength > AUDIO_RATE * 3) {
					System.arraycopy(sound, soundLength - AUDIO_RATE, sound, 0, AUDIO_RATE);
					soundLength = AUDIO_RATE;
				}
			}
		}

		private void run() {
			try {
				ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
				ImageWriteParam param = writer.getDefaultWriteParam();
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(JPEG_QUALITY);
				long segmentStart = System.currentTimeMillis();
				int seq = 0;
				List<long[]> times = new ArrayList<>();
				List<byte[]> pictures = new ArrayList<>();
				while (running) {
					long now = System.currentTimeMillis();
					int[] grab = null;
					int w = 0, h = 0;
					long at = 0;
					synchronized (lock) {
						if (fresh) {
							grab = frame;
							w = frameW;
							h = frameH;
							at = frameAt;
							fresh = false;
						}
					}
					if (grab != null) {
						pictures.add(jpeg(writer, param, grab, w, h));
						times.add(new long[]{Math.max(0, at - segmentStart)});
					}
					if (now >= segmentStart + SEGMENT_MS) {
						short[] heard;
						int count;
						synchronized (lock) {
							heard = java.util.Arrays.copyOf(sound, soundLength);
							count = soundLength;
							soundLength = 0;
						}
						byte[] packed = pack(seq, segmentStart, SEGMENT_MS, times, pictures, heard, count);
						while (!outgoing.offer(packed)) outgoing.poll();
						latest = seq;
						seq++;
						times.clear();
						pictures.clear();
						segmentStart += SEGMENT_MS;
						// Fell well behind (the game froze): start afresh from now
						if (now - segmentStart > SEGMENT_MS * 2) segmentStart = now;
					}
					Thread.sleep(1000 / FPS);
				}
				writer.dispose();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Throwable t) {
				problem = "casting stopped: " + t.getMessage();
				FemboyWare.LOGGER.warn("Live cast stopped", t);
			}
		}

		private int seqOf(byte[] packed) {
			return (packed[4] & 0xFF) << 24 | (packed[5] & 0xFF) << 16 | (packed[6] & 0xFF) << 8 | packed[7] & 0xFF;
		}

		private void upload() {
			while (running) {
				try {
					byte[] packed = outgoing.poll(500, TimeUnit.MILLISECONDS);
					if (packed == null) continue;
					PeerLink l = link.get();
					if (l == null) {
						problem = "Client Sync isn't connected, so nobody else can see it";
						continue;
					}
					try {
						l.putLive(id, seqOf(packed), packed);
						if (problem != null && problem.startsWith("couldn't send") || problem != null && problem.startsWith("Client Sync")) problem = null;
					} catch (IOException e) {
						problem = "couldn't send it: " + e.getMessage();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}

		public void stop() {
			running = false;
			if (audio != null) audio.stop();
		}
	}

	/** The window, scaled to fit {@link #MAX_SIDE}, as a JPEG. */
	private static byte[] jpeg(ImageWriter writer, ImageWriteParam param, int[] abgr, int w, int h) throws IOException {
		int step = Math.max(1, (Math.max(w, h) + MAX_SIDE - 1) / MAX_SIDE);
		int ow = Math.max(1, w / step), oh = Math.max(1, h / step);
		BufferedImage image = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
		int[] row = new int[ow];
		for (int y = 0; y < oh; y++) {
			for (int x = 0; x < ow; x++) {
				int c = abgr[(y * step) * w + x * step];
				row[x] = (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16) & 0xFF;
			}
			image.setRGB(0, y, ow, 1, row, 0, ow);
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
		try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		return bytes.toByteArray();
	}

	private static byte[] pack(int seq, long start, int duration, List<long[]> times, List<byte[]> pictures, short[] sound, int count) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(MAGIC);
		out.writeInt(seq);
		out.writeLong(start);
		out.writeInt(duration);
		out.writeShort(pictures.size());
		for (int i = 0; i < pictures.size(); i++) {
			out.writeInt((int) times.get(i)[0]);
			out.writeInt(pictures.get(i).length);
			out.write(pictures.get(i));
		}
		byte[] adpcm = adpcmEncode(sound, count);
		out.writeInt(AUDIO_RATE);
		out.writeInt(count);
		out.writeInt(adpcm.length);
		out.write(adpcm);
		return bytes.toByteArray();
	}

	// ---------------------------------------------------------------- watching

	/** A picture from a cast, at its moment on the caster's clock. */
	public record Picture(long atMs, int[] abgr, int w, int h) {
	}

	/** Someone else's cast as you watch it: fetched, unpacked and played back a little behind. */
	public static final class Feed {
		public final String id;
		private final Supplier<PeerLink> link;
		private volatile boolean running = true, ended;
		/** How far behind the caster's clock playback runs, in ms: set by how late segments arrive. */
		private volatile double delay = 1800;
		private final ArrayDeque<Long> lags = new ArrayDeque<>();
		/** Sound by the moment each segment's starts, 24 kHz mono. */
		private final TreeMap<Long, short[]> sound = new TreeMap<>();
		private volatile boolean heard;
		private int seq;
		/** Where the sound stream's up to, on the caster's clock. */
		private double cursor = Double.NaN;

		public Feed(Supplier<PeerLink> link, String id, int startSeq) {
			this.link = link;
			this.id = id;
			this.seq = Math.max(0, startSeq);
		}

		/** The moment being played, on the caster's clock (ms). */
		public double playout() {
			return System.currentTimeMillis() - delay;
		}

		public boolean ended() {
			return ended;
		}

		public boolean hasSound() {
			return heard;
		}

		public double delay() {
			return delay;
		}

		public void stop() {
			running = false;
		}

		/** Fetches segments for as long as it plays, handing each picture over. Runs on the TV's thread. */
		void run(java.util.function.Consumer<Picture> pictures) {
			int missing = 0;
			while (running) {
				PeerLink l = link.get();
				if (l == null) {
					sleep(500);
					continue;
				}
				try {
					PeerLink.LiveSegment segment = l.getLive(id, seq);
					if (segment.data() == null) {
						// Not there: catch up to the newest, or give up if it's gone for good
						if (segment.latest() >= 0 && (segment.latest() > seq + 1 || segment.latest() < seq - 1)) seq = segment.latest();
						if (segment.gone() && segment.latest() < 0 && ++missing > 10) {
							ended = true;
							return;
						}
						if (segment.gone()) sleep(300);
						continue;
					}
					missing = 0;
					unpack(segment.data(), pictures);
					seq++;
					// Fallen behind (a slow connection): skip to what's new
					if (segment.latest() > seq + 2) seq = segment.latest() - 1;
				} catch (IOException e) {
					sleep(1000);
				}
			}
		}

		private void unpack(byte[] data, java.util.function.Consumer<Picture> pictures) throws IOException {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
			if (in.readInt() != MAGIC) throw new IOException("not a cast");
			in.readInt();
			long start = in.readLong();
			int duration = in.readInt();
			// How late this came (on our clock against theirs, so it includes any difference between the two)
			long lag = System.currentTimeMillis() - (start + duration);
			lags.addLast(lag);
			while (lags.size() > 8) lags.removeFirst();
			long worst = lags.stream().mapToLong(Long::longValue).max().orElse(lag);
			double want = duration + worst + 250;
			// Further behind at once if segments are late; back towards live slowly when they're quick
			delay = want > delay ? want : delay + (want - delay) * 0.05;
			int count = in.readUnsignedShort();
			for (int i = 0; i < count; i++) {
				int offset = in.readInt(), length = in.readInt();
				if (length < 0 || length > 2_000_000) throw new IOException("bad picture");
				byte[] jpeg = in.readNBytes(length);
				BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
				if (image == null || image.getWidth() > 4096 || image.getHeight() > 4096) continue;
				int w = image.getWidth(), h = image.getHeight();
				int[] px = image.getRGB(0, 0, w, h, null, 0, w);
				for (int k = 0; k < px.length; k++) {
					int c = px[k];
					px[k] = 0xFF000000 | (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16) & 0xFF;
				}
				pictures.accept(new Picture(start + offset, px, w, h));
			}
			int rate = in.readInt(), samples = in.readInt(), length = in.readInt();
			if (rate != AUDIO_RATE || samples < 0 || samples > AUDIO_RATE * 4 || length > samples / 2 + 1) return;
			short[] pcm = adpcmDecode(in.readNBytes(length), samples);
			synchronized (sound) {
				sound.put(start, pcm);
				while (!sound.isEmpty() && sound.firstKey() < playout() - 5000) sound.pollFirstEntry();
			}
			if (samples > 0) heard = true;
		}

		/**
		 * The next {@code count} samples of sound for the speaker, from the moment {@code ahead} ms
		 * after what's showing (the sound engine plays them about that much later).
		 */
		short[] sound(int count, double ahead) {
			short[] out = new short[count];
			double want = playout() + ahead;
			if (Double.isNaN(cursor) || Math.abs(cursor - want) > 150) cursor = want;
			double perSample = 1000.0 / AUDIO_RATE;
			synchronized (sound) {
				for (int i = 0; i < count; i++) {
					double t = cursor + i * perSample;
					Map.Entry<Long, short[]> entry = sound.floorEntry((long) Math.floor(t));
					if (entry == null) continue;
					int index = (int) ((t - entry.getKey()) / perSample);
					if (index >= 0 && index < entry.getValue().length) out[i] = entry.getValue()[index];
				}
			}
			cursor += count * perSample;
			return out;
		}

		private static void sleep(long ms) {
			try {
				Thread.sleep(ms);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
