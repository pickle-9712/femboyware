package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.combat.AutoCrystalModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libc.LibCStdlib;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Plays a sound when you take damage and when you kill someone: your own sound files, or one of
 * Minecraft's cute built-in sounds. Only you hear them.
 *
 * <p>Put {@code .ogg} or {@code .wav} files in {@code .minecraft/femboyware/sounds/}: names
 * starting with "hurt" play when you're hurt, names starting with "kill" when you get a kill
 * (or put them in {@code hurt/} and {@code kill/} folders). A random one plays each time.
 */
public class HitSoundsModule extends Module {
	/** Minecraft's own sounds, for when you have no files of your own. */
	public enum BuiltIn {
		CAT_MEOW(() -> SoundEvents.ENTITY_CAT_AMBIENT),
		CAT_PURREOW(() -> SoundEvents.ENTITY_CAT_PURREOW),
		CAT_PURR(() -> SoundEvents.ENTITY_CAT_PURR),
		FOX(() -> SoundEvents.ENTITY_FOX_AMBIENT),
		FOX_SCREECH(() -> SoundEvents.ENTITY_FOX_SCREECH),
		AXOLOTL(() -> SoundEvents.ENTITY_AXOLOTL_IDLE_AIR),
		ORB(() -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP),
		PLING(() -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()),
		BELL(() -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value());

		private final Supplier<SoundEvent> sound;

		BuiltIn(Supplier<SoundEvent> sound) {
			this.sound = sound;
		}
	}

	public enum Source { CUSTOM_FILES, BUILT_IN }

	private static final Path FOLDER = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("sounds");
	/** How long after you hit someone their death still counts as your kill. */
	private static final long KILL_WINDOW_MILLIS = 10_000;
	/** At most this many of your sounds play at once. */
	private static final int MAX_PLAYING = 4;

	private final BoolSetting hurt = add(new BoolSetting("Hurt", "Play a sound when you take damage", true));
	private final BoolSetting kill = add(new BoolSetting("Kill", "Play a sound when you kill someone", true));
	private final EnumSetting<Source> source = add(new EnumSetting<>("Source", "Your own sound files, or Minecraft's built-in sounds. With no files, built-in sounds play", Source.CUSTOM_FILES));
	private final EnumSetting<BuiltIn> hurtSound = add(new EnumSetting<>("Hurt Sound", "Built-in sound when you're hurt", BuiltIn.CAT_MEOW));
	private final EnumSetting<BuiltIn> killSound = add(new EnumSetting<>("Kill Sound", "Built-in sound when you get a kill", BuiltIn.CAT_PURREOW));
	private final NumberSetting volume = add(new NumberSetting("Volume", "How loud, in percent", 100, 0, 200, 5));
	private final NumberSetting pitch = add(new NumberSetting("Pitch", "Higher or lower", 1, 0.5, 2, 0.05));
	private final NumberSetting variation = add(new NumberSetting("Pitch Variation", "A little random pitch each time, so repeats don't sound the same", 0.08, 0, 0.3, 0.01));
	private final BoolSetting openFolder = add(new BoolSetting("Open Folder", "Turn on to open the sounds folder", false));

	private final Random random = new Random();
	/** Decoded sound files, kept until the file changes. */
	private final Map<Path, Clip> cache = new HashMap<>();
	private final AtomicInteger playing = new AtomicInteger();
	/** Players you hit recently, by entity id, with when. */
	private final Map<Integer, Long> recentTargets = new HashMap<>();
	private final Set<Integer> celebrated = new HashSet<>();
	private int lastHurtTime;

	/** One decoded sound: 16-bit little-endian PCM. */
	private record Clip(byte[] data, int channels, float sampleRate, long modified) {}

	public HitSoundsModule() {
		super("Hit Sounds", "Plays your own sounds when you're hurt or get a kill", Category.MISC);
	}

	@Override
	protected void onEnable() {
		recentTargets.clear();
		celebrated.clear();
		try {
			Files.createDirectories(FOLDER);
		} catch (IOException ignored) {
		}
	}

	/** Noted before the hit lands, so a hit that kills on the spot still counts. */
	@Override
	public void onPreAttack(Entity target) {
		if (target instanceof PlayerEntity player && player != mc.player && !player.isDead()) recentTargets.put(player.getId(), System.currentTimeMillis());
	}

	@Override
	public void onTick() {
		if (openFolder.get()) {
			openFolder.set(false);
			try {
				Files.createDirectories(FOLDER);
				Util.getOperatingSystem().open(FOLDER);
			} catch (IOException e) {
				ChatUtil.info("Couldn't open the sounds folder: " + FOLDER);
			}
		}

		// Hurt: hurtTime jumps up the moment a hit lands
		int hurtTime = mc.player.hurtTime;
		if (hurt.get() && hurtTime > lastHurtTime && !mc.player.isDead()) play("hurt", hurtSound.get());
		lastHurtTime = hurtTime;

		// Kill: someone you hit, or Auto Crystal's target, dying soon after
		long now = System.currentTimeMillis();
		AutoCrystalModule crystal = FemboyWare.getModules().get(AutoCrystalModule.class);
		if (crystal.isEnabled() && crystal.getTarget() != null && !crystal.getTarget().isDead()) recentTargets.put(crystal.getTarget().getId(), now);
		celebrated.removeIf(id -> !(mc.world.getEntityById(id) instanceof PlayerEntity player) || !player.isDead());
		recentTargets.entrySet().removeIf(entry -> {
			if (now - entry.getValue() > KILL_WINDOW_MILLIS) return true;
			if (!(mc.world.getEntityById(entry.getKey()) instanceof PlayerEntity player) || !player.isDead()) return false;
			if (celebrated.add(entry.getKey()) && kill.get()) play("kill", killSound.get());
			return true;
		});
	}

	/** Plays a random file for {@code kind}, or the built-in sound if there are none or built-in is chosen. */
	private void play(String kind, BuiltIn fallback) {
		float chosenPitch = (float) Math.max(0.3, pitch.get() + (random.nextDouble() * 2 - 1) * variation.get());
		float chosenVolume = (float) (volume.get() / 100);
		if (source.is(Source.CUSTOM_FILES)) {
			List<Path> files = files(kind);
			if (!files.isEmpty()) {
				playFile(files.get(random.nextInt(files.size())), chosenVolume, chosenPitch);
				return;
			}
		}
		mc.getSoundManager().play(PositionedSoundInstance.ui(fallback.sound.get(), chosenPitch, Math.min(1, chosenVolume)));
	}

	/** Sound files for {@code kind}: named "kind..." in the folder, or anything in a "kind" subfolder. */
	private static List<Path> files(String kind) {
		List<Path> files = new ArrayList<>();
		collect(FOLDER, kind, false, files);
		collect(FOLDER.resolve(kind), kind, true, files);
		return files;
	}

	private static void collect(Path folder, String kind, boolean any, List<Path> into) {
		if (!Files.isDirectory(folder)) return;
		try (Stream<Path> list = Files.list(folder)) {
			list.filter(Files::isRegularFile).filter(path -> {
				String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
				return (name.endsWith(".ogg") || name.endsWith(".wav")) && (any || name.startsWith(kind));
			}).forEach(into::add);
		} catch (IOException ignored) {
		}
	}

	/** Decodes the file (once) and plays it on its own thread, so the game never waits on it. */
	private void playFile(Path file, float gain, float speed) {
		if (playing.get() >= MAX_PLAYING) return;
		// Follow the game's own master and player volume sliders too
		float game = mc.options.getSoundVolume(SoundCategory.MASTER) * mc.options.getSoundVolume(SoundCategory.PLAYERS);
		float loudness = gain * game;
		if (loudness <= 0) return;
		playing.incrementAndGet();
		Thread thread = new Thread(() -> {
			try {
				Clip clip = load(file);
				if (clip == null) return;
				AudioFormat format = new AudioFormat(clip.sampleRate * speed, 16, clip.channels, true, false);
				try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
					line.open(format);
					if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
						FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
						float decibels = (float) (20 * Math.log10(loudness));
						control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), decibels)));
					}
					line.start();
					line.write(clip.data, 0, clip.data.length);
					line.drain();
				}
			} catch (Exception e) {
				FemboyWare.LOGGER.warn("Hit Sounds couldn't play {}", file.getFileName(), e);
			} finally {
				playing.decrementAndGet();
			}
		}, "femboyware hit sound");
		thread.setDaemon(true);
		thread.start();
	}

	private Clip load(Path file) throws IOException {
		long modified = Files.getLastModifiedTime(file).toMillis();
		synchronized (cache) {
			Clip cached = cache.get(file);
			if (cached != null && cached.modified == modified) return cached;
		}
		Clip clip = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg") ? decodeOgg(file, modified) : decodeWav(file, modified);
		if (clip != null) {
			synchronized (cache) {
				cache.put(file, clip);
			}
		}
		return clip;
	}

	/** Decodes an Ogg Vorbis file with the same decoder Minecraft uses for its own sounds. */
	private static Clip decodeOgg(Path file, long modified) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer channels = stack.mallocInt(1);
			IntBuffer rate = stack.mallocInt(1);
			ShortBuffer samples = STBVorbis.stb_vorbis_decode_filename(file.toAbsolutePath().toString(), channels, rate);
			if (samples == null) return null;
			try {
				byte[] data = new byte[samples.remaining() * 2];
				for (int i = 0; i < samples.remaining(); i++) {
					short sample = samples.get(i);
					data[i * 2] = (byte) sample;
					data[i * 2 + 1] = (byte) (sample >> 8);
				}
				return new Clip(data, channels.get(0), rate.get(0), modified);
			} finally {
				LibCStdlib.free(samples);
			}
		}
	}

	/** Reads a WAV file, converting it to 16-bit PCM if it isn't already. */
	private static Clip decodeWav(Path file, long modified) {
		try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
			AudioFormat source = in.getFormat();
			AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.getSampleRate(), 16, source.getChannels(),
				source.getChannels() * 2, source.getSampleRate(), false);
			try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, in)) {
				return new Clip(converted.readAllBytes(), source.getChannels(), source.getSampleRate(), modified);
			}
		} catch (Exception e) {
			FemboyWare.LOGGER.warn("Hit Sounds couldn't read {}", file.getFileName(), e);
			return null;
		}
	}
}
