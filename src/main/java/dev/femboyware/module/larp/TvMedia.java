package dev.femboyware.module.larp;

import com.mojang.blaze3d.platform.NativeImage;
import dev.femboyware.FemboyWare;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.AudioBuffer;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One file playing on a TV: an MP4 (H.264 video and AAC sound, decoded in pure Java on a thread of
 * its own), an M4A (sound only) or a picture. Whoever owns it tells it where playback should be
 * ({@link #sync}); the picture follows that clock, skipping ahead to a keyframe if decoding falls
 * behind, and the sound plays from the TV's spot in the world, restarted whenever the clock jumps.
 */
public final class TvMedia {
	public static final java.util.Set<String> VIDEO = java.util.Set.of("mp4", "m4v", "mov");
	public static final java.util.Set<String> AUDIO = java.util.Set.of("m4a", "aac");
	public static final java.util.Set<String> PICTURE = java.util.Set.of("png", "jpg", "jpeg");

	private static final SoundEvent SOUND = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("femboyware", "tv"));
	/** Bigger videos are averaged down by two as they're converted, so the texture stays small. */
	private static final int HALVE_ABOVE = 960, MAX_SIDE = 4096;
	/** Decoded frames kept ready ahead of the clock, and how many held back to put B-frames in order. */
	private static final int AHEAD = 4, REORDER = 4;
	/** How far the picture may lag before it jumps to the next keyframe instead of decoding through. */
	private static final double GIVE_UP_LAG = 1.2;
	private static final AtomicInteger IDS = new AtomicInteger();

	public enum Kind { VIDEO, AUDIO, PICTURE, LIVE, STREAM }

	public static Kind kindOf(String name) {
		String ext = extension(name);
		if (VIDEO.contains(ext)) return Kind.VIDEO;
		if (AUDIO.contains(ext)) return Kind.AUDIO;
		if (PICTURE.contains(ext)) return Kind.PICTURE;
		return null;
	}

	public static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** A picture ready to show, its pixels in the texture's own order (RGBA bytes, so ABGR ints). */
	private record Frame(double pts, int[] abgr) {
	}

	/** Spare pixel arrays, so playing doesn't churn out a new one per frame for the garbage collector. */
	private final java.util.concurrent.ConcurrentLinkedQueue<int[]> spare = new java.util.concurrent.ConcurrentLinkedQueue<>();

	private int[] pixels(int size) {
		for (int[] a; (a = spare.poll()) != null; ) if (a.length == size) return a;
		return new int[size];
	}

	private void recycle(int[] a) {
		if (a != null && spare.size() < AHEAD + REORDER + 4) spare.add(a);
	}

	/** ARGB (Java's order) to ABGR (the texture's). */
	private static int abgr(int argb) {
		return argb & 0xFF00FF00 | (argb >> 16) & 0xFF | (argb & 0xFF) << 16;
	}

	public final Path file;
	public final Kind kind;
	/** For {@link Kind#LIVE}: the window shown, and how much to crop off each side (left, right, top, bottom). */
	private final WindowMirror mirror;
	private final java.util.function.Supplier<float[]> crop;
	private volatile String liveProblem;
	/** For {@link Kind#STREAM}: the cast being watched. For {@link Kind#LIVE}: sending it to the others, while that's on. */
	public final LiveCast.Feed feed;
	private volatile LiveCast.Broadcaster cast;
	private final Identifier textureId = Identifier.fromNamespaceAndPath("femboyware", "tv/" + IDS.incrementAndGet());
	private volatile boolean closed;
	private volatile String error;
	private volatile boolean ready;
	/** Size of the picture as drawn, and how long it plays for (0 for a picture). */
	private volatile int width, height;
	private volatile double duration;
	private volatile boolean hasAudio;

	/** The clock: where playback was at {@code anchorNanos}, and whether it's moving. */
	private volatile double anchorPos;
	private volatile long anchorNanos = System.nanoTime();
	private volatile boolean playing;
	/** Bumped whenever the clock jumps, so the decoder seeks and the sound restarts. */
	private volatile int jumps;

	private final ArrayDeque<Frame> ready_frames = new ArrayDeque<>();
	private int[] still;
	private int stillW, stillH;
	private DynamicTexture texture;
	private NativeImage image;
	private double shownPts = Double.NaN;
	private boolean uploaded;
	/** The picture's average colour, for the glow round the TV. */
	private volatile int ambient;

	private TvSound sound;
	private int soundJump = -1;
	private boolean wasPaused;

	public TvMedia(Path file, String name) {
		this(file, kindOf(name), null, null);
	}

	/** A live mirror of another window. */
	public TvMedia(WindowMirror mirror, java.util.function.Supplier<float[]> crop) {
		this(null, Kind.LIVE, mirror, crop, null);
	}

	/** Someone else's live cast, watched. */
	public TvMedia(LiveCast.Feed feed) {
		this(null, Kind.STREAM, null, null, feed);
	}

	private TvMedia(Path file, Kind kind, WindowMirror mirror, java.util.function.Supplier<float[]> crop) {
		this(file, kind, mirror, crop, null);
	}

	private TvMedia(Path file, Kind kind, WindowMirror mirror, java.util.function.Supplier<float[]> crop, LiveCast.Feed feed) {
		this.file = file;
		this.kind = kind;
		this.mirror = mirror;
		this.crop = crop;
		this.feed = feed;
		Thread thread = new Thread(this::run, "femboyware tv");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		thread.start();
	}

	// ---------------------------------------------------------------- the clock

	/** Where playback is now, in seconds. */
	public double position() {
		if (kind == Kind.STREAM) return feed.playout() / 1000.0;
		double at = playing ? anchorPos + (System.nanoTime() - anchorNanos) / 1e9 : anchorPos;
		return duration > 0 ? Math.min(at, duration) : at;
	}

	public boolean playing() {
		return playing;
	}

	public boolean ready() {
		return ready;
	}

	public String error() {
		return error;
	}

	public double duration() {
		return duration;
	}

	public boolean hasPicture() {
		return kind != Kind.AUDIO && ready;
	}

	/** Width over height of the picture, or 16:9 until it's known. */
	public float aspect() {
		return width > 0 && height > 0 ? (float) width / height : 16 / 9f;
	}

	/** Where playback should be: followed exactly when it jumps more than a moment or starts or stops. */
	public void sync(double position, boolean play) {
		double drift = Math.abs(position() - position);
		if (play != playing || drift > 0.4) {
			anchorPos = position;
			anchorNanos = System.nanoTime();
			playing = play;
			if (drift > 0.4 || play) jumps++;
		}
	}

	// ---------------------------------------------------------------- decoding

	private void run() {
		try {
			switch (kind) {
				case PICTURE -> loadPicture();
				case VIDEO, AUDIO -> decodeVideo();
				case LIVE -> mirrorLoop();
				case STREAM -> watch();
			}
		} catch (Throwable t) {
			error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
			FemboyWare.LOGGER.warn("TV couldn't play {}", file != null ? file.getFileName() : kind, t);
		}
	}

	private void loadPicture() throws IOException {
		try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) throw new IOException("not a picture");
			ImageReader reader = readers.next();
			reader.setInput(in);
			// Checked before decoding, so a huge picture can't eat all the memory
			if (reader.getWidth(0) > MAX_SIDE || reader.getHeight(0) > MAX_SIDE) throw new IOException("picture too big (max " + MAX_SIDE + " px)");
			BufferedImage picture = reader.read(0);
			reader.dispose();
			int step = Math.max(1, Math.max(picture.getWidth(), picture.getHeight()) / 1024);
			int w = picture.getWidth() / step, h = picture.getHeight() / step;
			int[] argb = new int[w * h];
			for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) argb[y * w + x] = abgr(picture.getRGB(x * step, y * step) | 0xFF000000);
			width = w;
			height = h;
			synchronized (ready_frames) {
				still = argb;
				stillW = w;
				stillH = h;
			}
			ready = true;
		}
	}

	/** Copies the window about 30 times a second, while it's on. */
	private void mirrorLoop() {
		try {
			while (!closed) {
				long start = System.nanoTime();
				float[] c = crop.get();
				WindowMirror.Capture capture = mirror.capture(c[0], c[1], c[2], c[3]);
				if (!mirror.alive()) liveProblem = "the window was closed";
				else if (capture == null) liveProblem = mirror.whyNot();
				else if (mirror.unchanged() > 90) liveProblem = "the picture hasn't changed for a while: if it's behind Minecraft, your browser may have stopped drawing it (see .tv mirror help)";
				else liveProblem = null;
				if (capture != null) {
					int[] px = capture.argb();
					for (int i = 0; i < px.length; i++) px[i] = abgr(px[i]);
					width = capture.width();
					height = capture.height();
					synchronized (ready_frames) {
						recycle(still);
						still = capture.argb();
						stillW = capture.width();
						stillH = capture.height();
					}
					ready = true;
					LiveCast.Broadcaster sending = cast;
					if (sending != null) sending.offer(capture.argb(), capture.width(), capture.height());
				}
				sleep(Math.max(1, 33 - (System.nanoTime() - start) / 1_000_000));
			}
		} finally {
			mirror.release();
		}
	}

	/**
	 * Starts sending what's mirrored to everyone else watching through Client Sync, with the sound
	 * of the program that owns the window ({@code pid}). Your own sound stays your browser's, so
	 * there's no echo.
	 */
	public void startCast(java.util.function.Supplier<dev.femboyware.manager.PeerLink> link, int pid) {
		if (kind != Kind.LIVE || cast != null || closed) return;
		cast = new LiveCast.Broadcaster(link, pid);
	}

	/** What's being sent, or null. */
	public LiveCast.Broadcaster cast() {
		return cast;
	}

	/** Watching a cast: its pictures go up as they're due, its sound plays through {@link LiveSound}. */
	private void watch() {
		feed.run(picture -> {
			width = picture.w();
			height = picture.h();
			synchronized (ready_frames) {
				ready_frames.addLast(new Frame(picture.atMs() / 1000.0, picture.abgr()));
				while (ready_frames.size() > 60) ready_frames.pollFirst();
			}
			ready = true;
		});
		if (feed.ended()) error = "the cast ended";
	}

	/** Why the mirror isn't showing a moving picture, or null if it is. */
	public String liveProblem() {
		return liveProblem;
	}

	public WindowMirror mirror() {
		return mirror;
	}

	private void decodeVideo() throws IOException {
		try (SeekableByteChannel channel = NIOUtils.readableChannel(file.toFile())) {
			MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(channel);
			DemuxerTrack track = demuxer.getVideoTrack();
			DemuxerTrack audio = demuxer.getAudioTracks().isEmpty() ? null : demuxer.getAudioTracks().get(0);
			hasAudio = audio != null && audio.getMeta().getCodec() == Codec.AAC;
			if (track != null && track.getMeta().getCodec() != Codec.H264) throw new IOException("the video isn't H.264 (re-export it as H.264 MP4)");
			if (track == null && !hasAudio) throw new IOException("nothing playable in it (it needs H.264 video or AAC sound)");
			duration = Math.max(track != null ? track.getMeta().getTotalDuration() : 0, audio != null ? audio.getMeta().getTotalDuration() : 0);
			if (track == null || kind == Kind.AUDIO) {
				ready = true;
				return;
			}
			var size = track.getMeta().getVideoCodecMeta().getSize();
			int srcW = size.getWidth(), srcH = size.getHeight();
			if (srcW <= 0 || srcH <= 0 || srcW > MAX_SIDE || srcH > MAX_SIDE) throw new IOException("unsupported video size " + srcW + "x" + srcH);
			int shift = srcW > HALVE_ABOVE ? 1 : 0;
			width = srcW >> shift;
			height = srcH >> shift;
			ready = true;
			SeekableDemuxerTrack seekable = track instanceof SeekableDemuxerTrack s ? s : null;
			int[] keyframes = track.getMeta().getSeekFrames();
			byte[][] buffer = Picture.create((srcW + 15) & ~15, (srcH + 15) & ~15, ColorSpace.YUV420J).getData();
			H264Decoder decoder = H264Decoder.createH264DecoderFromCodecPrivate(track.getMeta().getCodecPrivate());
			TreeMap<Double, int[]> reorder = new TreeMap<>();
			int seenJumps = -1;
			double skipUntil = 0;
			long lastConverted = 0;
			boolean ended = false;
			while (!closed) {
				if (seenJumps != jumps) {
					// The clock jumped: back (or on) to the keyframe before it, and decode from there
					seenJumps = jumps;
					double target = position();
					if (seekable != null) {
						seekable.seek(Math.max(0, target));
						long frame = seekable.getCurFrame();
						long key = 0;
						if (keyframes != null) for (int k : keyframes) if (k <= frame) key = Math.max(key, k);
						seekable.gotoFrame(key);
					}
					decoder = H264Decoder.createH264DecoderFromCodecPrivate(track.getMeta().getCodecPrivate());
					for (int[] a : reorder.values()) recycle(a);
					reorder.clear();
					synchronized (ready_frames) {
						for (Frame f : ready_frames) recycle(f.abgr);
						ready_frames.clear();
					}
					skipUntil = target - 0.05;
					ended = false;
				}
				int queued;
				synchronized (ready_frames) {
					queued = ready_frames.size();
				}
				if (ended || queued >= AHEAD) {
					sleep(4);
					continue;
				}
				Packet packet = track.nextFrame();
				if (packet == null) {
					// Out of frames: let the last few out in order
					for (var e : reorder.entrySet()) if (e.getValue() != null) offer(e.getKey(), e.getValue());
					reorder.clear();
					ended = true;
					continue;
				}
				double pts = packet.getPts() / (double) packet.getTimescale();
				Picture picture = decoder.decodeFrame(packet.getData(), buffer);
				if (picture == null) continue;
				// Frames before where a seek landed are only decoded for their references; frames behind the
				// clock are skipped too, but one still goes up every 150 ms so a slow machine shows a slideshow, not a freeze
				long nowMs = System.nanoTime() / 1_000_000;
				boolean late = pts < skipUntil || playing && pts < position() - 0.15 && nowMs - lastConverted < 150;
				if (!late) lastConverted = nowMs;
				reorder.put(pts, late ? null : convert(picture, srcW, srcH, shift, pixels((srcW >> shift) * (srcH >> shift))));
				while (reorder.size() > REORDER) {
					var first = reorder.pollFirstEntry();
					if (first.getValue() != null) offer(first.getKey(), first.getValue());
				}
				// Hopelessly behind: skip to the next keyframe past the clock instead of grinding through
				if (playing && seekable != null && pts < position() - GIVE_UP_LAG) {
					long next = -1;
					long now = seekable.getCurFrame();
					double rate = track.getMeta().getTotalFrames() / Math.max(0.001, track.getMeta().getTotalDuration());
					long want = (long) ((position() + 0.5) * rate);
					// No keyframe table means every frame is one
					if (keyframes == null) next = Math.max(now + 1, want);
					else for (int k : keyframes) if (k > now && k >= want - rate * 0.5) {
						next = k;
						break;
					}
					if (next > 0) {
						seekable.gotoFrame(next);
						decoder = H264Decoder.createH264DecoderFromCodecPrivate(track.getMeta().getCodecPrivate());
						reorder.clear();
					}
				}
			}
		}
	}

	private void offer(double pts, int[] argb) {
		synchronized (ready_frames) {
			ready_frames.addLast(new Frame(pts, argb));
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The picture as colours (limited-range BT.601), averaging 2x2 blocks when {@code shift} is 1. */
	private static int[] convert(Picture picture, int srcW, int srcH, int shift, int[] out) {
		boolean full = picture.getColor() == ColorSpace.YUV420J;
		byte[] yp = picture.getPlaneData(0), up = picture.getPlaneData(1), vp = picture.getPlaneData(2);
		int stride = picture.getPlaneWidth(0), cstride = picture.getPlaneWidth(1);
		int w = srcW >> shift, h = srcH >> shift;
		for (int j = 0; j < h; j++) {
			int sy = j << shift;
			for (int i = 0; i < w; i++) {
				int sx = i << shift;
				int y;
				if (shift == 0) y = yp[sy * stride + sx] + 128;
				else y = (yp[sy * stride + sx] + yp[sy * stride + sx + 1] + yp[(sy + 1) * stride + sx] + yp[(sy + 1) * stride + sx + 1] + 512) >> 2;
				int c = (sy >> 1) * cstride + (sx >> 1);
				int u = up[c], v = vp[c];
				int r, g, b;
				if (full) {
					r = y + ((91881 * v) >> 16);
					g = y - ((22554 * u + 46802 * v) >> 16);
					b = y + ((116130 * u) >> 16);
				} else {
					int l = 76284 * (y - 16);
					r = (l + 104595 * v) >> 16;
					g = (l - 25624 * u - 53280 * v) >> 16;
					b = (l + 132252 * u) >> 16;
				}
				out[j * w + i] = 0xFF000000 | clamp(b) << 16 | clamp(g) << 8 | clamp(r);
			}
		}
		return out;
	}

	private static int clamp(int c) {
		return c < 0 ? 0 : Math.min(255, c);
	}

	// ---------------------------------------------------------------- picture

	/** The texture showing the picture at the clock, uploading a newer frame first; null until there is one. Render thread only. */
	public Identifier frame() {
		if (!ready || kind == Kind.AUDIO || closed) return null;
		int[] show = null;
		int showW = width, showH = height;
		synchronized (ready_frames) {
			if (still != null) {
				show = still;
				showW = stillW;
				showH = stillH;
				still = null;
			} else {
				double now = position();
				Frame pick = null;
				// The latest frame that's due, dropping the ones it replaces
				while (!ready_frames.isEmpty() && ready_frames.peekFirst().pts <= now + 0.01) {
					if (pick != null) recycle(pick.abgr);
					pick = ready_frames.pollFirst();
				}
				// Nothing shown yet (just loaded or seeked while paused): the first one there is
				if (pick == null && !uploaded && !ready_frames.isEmpty()) pick = ready_frames.pollFirst();
				if (pick == null && !playing && !ready_frames.isEmpty() && Math.abs(ready_frames.peekFirst().pts - now) < 0.1 && ready_frames.peekFirst().pts != shownPts)
					pick = ready_frames.pollFirst();
				if (pick != null) {
					show = pick.abgr;
					shownPts = pick.pts;
				}
			}
		}
		if (show != null) {
			upload(show, showW, showH);
			recycle(show);
		}
		return uploaded ? textureId : null;
	}

	private void upload(int[] abgr, int w, int h) {
		if (abgr.length < w * h) return;
		if (image == null || image.getWidth() != w || image.getHeight() != h) {
			if (texture != null) Minecraft.getInstance().getTextureManager().release(textureId);
			image = new NativeImage(w, h, false);
			texture = new DynamicTexture(() -> "femboyware tv", image);
			Minecraft.getInstance().getTextureManager().register(textureId, texture);
		}
		// One bulk copy into the image's memory, already in its byte order
		org.lwjgl.system.MemoryUtil.memIntBuffer(image.getPointer(), w * h).put(abgr, 0, w * h);
		texture.upload();
		uploaded = true;
		// Averaged from a sparse grid, brightened a little so the glow reads
		long r = 0, g = 0, b = 0;
		int n = 0;
		for (int y = h / 16; y < h; y += Math.max(1, h / 8)) {
			for (int x = w / 16; x < w; x += Math.max(1, w / 8)) {
				int c = abgr[y * w + x];
				b += (c >> 16) & 0xFF;
				g += (c >> 8) & 0xFF;
				r += c & 0xFF;
				n++;
			}
		}
		if (n > 0) {
			int max = (int) Math.max(1, Math.max(r, Math.max(g, b)) / n);
			float lift = Math.min(2.5f, 200f / max);
			ambient = 0xFF000000 | Math.min(255, (int) (r / n * lift)) << 16 | Math.min(255, (int) (g / n * lift)) << 8 | Math.min(255, (int) (b / n * lift));
		}
	}

	/** The picture's average colour, or 0 before there is one. */
	public int ambientColor() {
		return ambient;
	}

	// ---------------------------------------------------------------- sound

	/**
	 * Keeps the sound playing from {@code x, y, z} in step with the clock: started with playback,
	 * stopped with it, and restarted from the right spot after a jump or the game unpausing.
	 */
	public void tickSound(double x, double y, double z, float volume) {
		Minecraft mc = Minecraft.getInstance();
		if (kind == Kind.STREAM) {
			boolean wanted = !closed && volume > 0 && feed.hasSound() && !mc.isPaused();
			if (sound != null && (!wanted || sound.isStopped())) {
				mc.getSoundManager().stop(sound);
				sound = null;
			}
			if (sound == null && wanted) {
				sound = new TvSound(this, 0);
				mc.getSoundManager().play(sound);
			}
			if (sound != null) sound.place(x, y, z, volume);
			return;
		}
		if (!hasAudio || closed || !ready) return;
		boolean paused = mc.isPaused();
		boolean resumed = wasPaused && !paused;
		wasPaused = paused;
		boolean wanted = playing && volume > 0 && position() < duration - 0.05;
		if (sound != null && (!wanted || soundJump != jumps || resumed || sound.isStopped() && wanted)) {
			mc.getSoundManager().stop(sound);
			sound = null;
		}
		if (sound == null && wanted && !paused) {
			soundJump = jumps;
			sound = new TvSound(this, position() + 0.05);
			mc.getSoundManager().play(sound);
		}
		if (sound != null) sound.place(x, y, z, volume);
	}

	public void close() {
		closed = true;
		if (feed != null) feed.stop();
		if (cast != null) cast.stop();
		if (sound != null) Minecraft.getInstance().getSoundManager().stop(sound);
		sound = null;
		if (texture != null) Minecraft.getInstance().getTextureManager().release(textureId);
		texture = null;
		image = null;
	}

	/** The TV's sound: a stream decoded straight from the file, from where the clock was when it started. */
	private static final class TvSound extends AbstractTickableSoundInstance implements FabricSoundInstance {
		private final TvMedia media;
		private final double start;

		TvSound(TvMedia media, double start) {
			super(SOUND, SoundSource.RECORDS, RandomSource.create());
			this.media = media;
			this.start = start;
			this.looping = false;
			this.delay = 0;
			this.attenuation = SoundInstance.Attenuation.LINEAR;
		}

		void place(double x, double y, double z, float volume) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.volume = volume;
		}

		@Override
		public void tick() {
			if (media.closed) stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
			if (media.kind == Kind.STREAM) return CompletableFuture.completedFuture(new LiveSound(media.feed));
			return CompletableFuture.supplyAsync(() -> {
				try {
					return new Stream(media.file, start);
				} catch (IOException e) {
					throw new java.util.concurrent.CompletionException(e);
				}
			}, net.minecraft.util.Util.backgroundExecutor());
		}
	}

	/**
	 * A cast's sound for the speaker, in 50 ms pieces so it stays close behind live: the sound
	 * engine keeps about four queued, which is the {@code AHEAD} it's taken from in front of the
	 * picture, so the two line up.
	 */
	private static final class LiveSound implements AudioStream {
		private static final int PIECE = LiveCast.AUDIO_RATE / 20;
		private static final double AHEAD_MS = 200;
		private final LiveCast.Feed feed;
		private final javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(LiveCast.AUDIO_RATE, 16, 1, true, false);
		private ByteBuffer out;

		LiveSound(LiveCast.Feed feed) {
			this.feed = feed;
		}

		@Override
		public javax.sound.sampled.AudioFormat getFormat() {
			return format;
		}

		@Override
		public ByteBuffer read(int expectedSize) {
			short[] samples = feed.sound(PIECE, AHEAD_MS);
			if (out == null) out = ByteBuffer.allocateDirect(PIECE * 2).order(ByteOrder.LITTLE_ENDIAN);
			out.clear();
			for (short sample : samples) out.putShort(sample);
			out.flip();
			return out;
		}

		@Override
		public void close() {
		}
	}

	/** AAC from the file as 16-bit mono PCM (mono so it comes from the TV's spot), from {@code start} seconds. */
	private static final class Stream implements AudioStream {
		private final SeekableByteChannel channel;
		private final DemuxerTrack track;
		private final AACDecoder decoder;
		private final javax.sound.sampled.AudioFormat format;
		private final ByteBuffer scratch = ByteBuffer.allocate(1 << 16);
		private ByteBuffer out;
		/** Samples still to drop so the sound starts exactly at {@code start}. */
		private long skip;

		Stream(Path file, double start) throws IOException {
			channel = NIOUtils.readableChannel(file.toFile());
			try {
				MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(channel);
				if (demuxer.getAudioTracks().isEmpty()) throw new IOException("no sound");
				track = demuxer.getAudioTracks().get(0);
				decoder = new AACDecoder(track.getMeta().getCodecPrivate());
				int rate = track.getMeta().getAudioCodecMeta().getSampleRate();
				format = new javax.sound.sampled.AudioFormat(rate, 16, 1, true, false);
				if (start > 0 && track instanceof SeekableDemuxerTrack seekable) {
					seekable.seek(start);
					// The seek lands on the packet holding the start; skip into it
					skip = Math.max(0, (long) ((start - currentTime()) * rate));
				}
			} catch (IOException | RuntimeException e) {
				channel.close();
				throw e instanceof IOException io ? io : new IOException(e);
			}
		}

		private double currentTime() {
			if (track instanceof org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack mp4) {
				long frame = mp4.getCurFrame();
				return frame * 1024.0 / format.getSampleRate();
			}
			return 0;
		}

		@Override
		public javax.sound.sampled.AudioFormat getFormat() {
			return format;
		}

		@Override
		public ByteBuffer read(int expectedSize) throws IOException {
			int capacity = Math.max(expectedSize, 8192) + 16384;
			if (out == null || out.capacity() < capacity) out = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
			out.clear();
			while (out.position() < expectedSize) {
				Packet packet = track.nextFrame();
				if (packet == null) break;
				scratch.clear();
				AudioBuffer decoded;
				try {
					decoded = decoder.decodeFrame(packet.getData(), scratch);
				} catch (Exception e) {
					continue;
				}
				ByteBuffer pcm = decoded.getData();
				var f = decoded.getFormat();
				int channels = Math.max(1, f.getChannels());
				boolean big = f.isBigEndian();
				int frames = pcm.remaining() / (2 * channels);
				for (int i = 0; i < frames; i++) {
					int sum = 0;
					for (int c = 0; c < channels; c++) {
						int at = pcm.position() + (i * channels + c) * 2;
						int lo = pcm.get(at + (big ? 1 : 0)) & 0xFF, hi = pcm.get(at + (big ? 0 : 1));
						sum += hi << 8 | lo;
					}
					if (skip > 0) {
						skip--;
						continue;
					}
					if (out.remaining() < 2) break;
					out.putShort((short) (sum / channels));
				}
			}
			out.flip();
			return out;
		}

		@Override
		public void close() throws IOException {
			channel.close();
		}
	}

	public static boolean readable(Path file) {
		return Files.isRegularFile(file) && Files.isReadable(file);
	}
}
