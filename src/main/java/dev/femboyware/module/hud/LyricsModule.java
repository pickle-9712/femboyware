package dev.femboyware.module.hud;

import dev.femboyware.util.Mc;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The lyrics of whatever's playing, in time with the song, as real 3D text in the world. Each line
 * appears ahead of you, where a box you can drag and resize says on screen, turned at its own angle
 * with its words bouncing in one by one, and stays where it landed as you look around. As you walk it
 * drifts along with you, but less than you move, so you slowly close in on it. Lyrics come from {@link Lyrics}, the song from {@link MediaSource}.
 */
public class LyricsModule extends HudElement {
	public enum Palette { THEME, STATIC, PASTEL, RAINBOW }

	/** Lines each take a row of the box, in this order, so the ones still showing never overlap. */
	private static final int[] ROWS = {0, 2, 1};
	/** Seconds a line takes to fade out. */
	private static final float FADE = 0.3f;
	/** A line stays until the next one starts, but no longer than this after its last word, for long instrumental gaps. */
	private static final double LONGEST_STAY = 6;
	/** Smallest size of the box, in percent of the screen. */
	private static final double MIN_WIDTH = 15;
	private static final double MIN_HEIGHT = 10;
	/** How long a status message stays, in milliseconds. */
	private static final long STATUS_TIME = 4000;
	/** How far apart the layers of thick letters are, in text units. */
	private static final float LAYER_GAP = 0.35f;

	private final NumberSetting distance = add(new NumberSetting("Distance", "How many blocks away lines start", 6, 2, 30, 0.5));
	private final NumberSetting follow = add(new NumberSetting("Follow", "How much lines move with you, in percent of how far you move; 0 stays put, 100 keeps the same distance", 70, 0, 100, 1)).visibleWhen(() -> this.stayInWorld.get());
	private final BoolSetting stayInWorld = add(new BoolSetting("Stay In World", "Lines stay where they appeared as you move and look around; off, they stay in front of you", true));
	private final BoolSetting throughBlocks = add(new BoolSetting("Through Blocks", "Show lines even with blocks in the way", true));
	private final NumberSetting areaWidth = add(new NumberSetting("Width", "How wide the box is, in percent of the screen; or drag its corner while the ClickGUI is open", 45, MIN_WIDTH, 100, 0.5));
	private final NumberSetting areaHeight = add(new NumberSetting("Height", "How tall the box is, in percent of the screen; text grows and shrinks with it", 30, MIN_HEIGHT, 100, 0.5));
	private final NumberSetting textSize = add(new NumberSetting("Text Size", "How much of its row each line fills; long lines shrink to fit the box", 0.8, 0.3, 1, 0.05));
	private final EnumSetting<LyricFont> font = add(new EnumSetting<>("Font", "Which font to use", LyricFont.INTER_BOLD));
	private final BoolSetting uppercase = add(new BoolSetting("Uppercase", "All capitals", false));
	private final NumberSetting offset = add(new NumberSetting("Offset", "Nudge the timing, in seconds, if lyrics run early or late; higher shows them sooner", 0, -5, 5, 0.05));
	private final NumberSetting tilt = add(new NumberSetting("Tilt", "How far lines turn in 3D, in degrees", 6, 0, 30, 1));
	private final NumberSetting bounce = add(new NumberSetting("Bounce", "How hard words bounce as they land", 1, 0, 3, 0.05));
	private final NumberSetting hold = add(new NumberSetting("Hold", "How many seconds a line stays after its last word", 1.2, 0.2, 5, 0.1));
	private final NumberSetting animation = add(new NumberSetting("Animation Speed", "How quickly words pop in", 1, 0.25, 3, 0.05));
	private final BoolSetting hidePaused = add(new BoolSetting("Hide When Paused", "Show nothing while the song is paused", true));
	private final BoolSetting extraSources = add(new BoolSetting("Extra Sources", "Also look on NetEase, QQ Music and Genius, not just LRCLIB; finds far more songs, and sends them the song's name", true));
	private final BoolSetting guessTiming = add(new BoolSetting("Guess Timing", "When a song only has untimed lyrics, spread them across it; they'll drift out of time with the singing", true));
	private final BoolSetting status = add(new BoolSetting("Status Text", "Say when lyrics are loading or can't be found", true));

	private final EnumSetting<Palette> palette = add(new EnumSetting<>("Colors", "Colour of the words", Palette.THEME));
	private final ColorSetting activeColor = add(new ColorSetting("Text Color", "Colour for Static", 0xFFFFFFFF)).visibleWhen(() -> palette.is(Palette.STATIC));
	private final ColorSetting statusColor = add(new ColorSetting("Status Color", "Colour of the status text", 0xCCFFFFFF)).visibleWhen(status::get);
	private final BoolSetting glow = add(new BoolSetting("Glow", "A soft glow around the words", true));
	private final ColorSetting glowColor = add(new ColorSetting("Glow Color", "Colour of the glow", 0xFFFF77C8)).visibleWhen(glow::get);
	private final NumberSetting glowStrength = add(new NumberSetting("Glow Strength", "How bright the glow is", 0.5, 0.1, 1, 0.05)).visibleWhen(glow::get);
	private final NumberSetting depth = add(new NumberSetting("Depth", "How thick the 3D letters are; 0 for flat", 3, 0, 8, 1));
	private final ColorSetting depthColor = add(new ColorSetting("Depth Color", "Colour of the sides of the letters", 0xCC3A1C33)).visibleWhen(() -> depth.get() > 0);

	/** One line: its words and when each starts, which row it lands in, and when it's gone. */
	private record Burst(double start, double gone, String[] words, double[] times, int row, long seed) {}

	/** Where a line appeared and which way it faces, and where the camera was then, to tell how far you've moved since. */
	private record Anchor(Vec3 pos, Quaternionf facing, Vec3 cameraThen) {}

	/** Lines for {@link #builtFor}, rebuilt if the song or the settings they depend on change. */
	private List<Burst> bursts = List.of();
	private String builtFor = "";
	private List<Lyrics.Line> builtLines;
	private String statusShown = "";
	private long statusSince;
	/** Where each showing line landed in the world, by its seed. */
	private final Map<Long, Anchor> anchors = new HashMap<>();

	public LyricsModule() {
		super("Lyrics", "The lyrics of what's playing on your PC, in time with the song", 50, 20, false);
		setEnabled(true);
	}

	@Override
	protected void onEnable() {
		MediaSource.acquire(this);
	}

	@Override
	protected void onDisable() {
		MediaSource.release(this);
	}

	private boolean showing() {
		WindowsMedia.Track track = MediaSource.track();
		return track != null && (track.playing() || !hidePaused.get());
	}

	@Override
	public int width(boolean editing) {
		if (!editing && !showing()) return 0;
		return Math.max(1, Math.round(mc.getWindow().getGuiScaledWidth() * areaWidth.getFloat() / 100));
	}

	@Override
	public int height(boolean editing) {
		return Math.max(1, Math.round(mc.getWindow().getGuiScaledHeight() * areaHeight.getFloat() / 100));
	}

	@Override
	public boolean resizable() {
		return true;
	}

	@Override
	public void resizeTo(int width, int height, int screenWidth, int screenHeight) {
		areaWidth.set(Math.round(Mth.clamp(width * 100.0 / screenWidth, MIN_WIDTH, 100) * 2) / 2.0);
		areaHeight.set(Math.round(Mth.clamp(height * 100.0 / screenHeight, MIN_HEIGHT, 100) * 2) / 2.0);
	}

	/** The lyrics themselves are in the world; the HUD only has words for the editor and status messages. */
	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		WindowsMedia.Track track = showing() ? MediaSource.track() : null;
		String message = null;
		if (track != null) {
			message = switch (Lyrics.get(track).status()) {
				case LOADING -> "Finding lyrics...";
				case NOT_FOUND -> "No lyrics found for this song";
				case FAILED -> "Couldn't reach the lyrics service";
				case SYNCED, ESTIMATED -> null;
			};
			if (!status.get()) message = null;
		}
		// A status is only news for a moment; it shouldn't sit over the game for the whole song
		String shown = message + "|" + (track == null ? "" : track.title());
		if (!shown.equals(statusShown)) {
			statusShown = shown;
			statusSince = System.currentTimeMillis();
		}
		if (System.currentTimeMillis() - statusSince > STATUS_TIME) message = null;
		if (message == null && editing) message = "Lyrics appear in this box";
		if (message == null) return;
		Component text = font.get().text(message, 1, false);
		context.drawString(mc.font, text, left + (width(true) - mc.font.width(text)) / 2, top + (height(true) - 8) / 2, statusColor.get(), false);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Lyrics.extraSources = extraSources.get();
		if (Mc.hudHidden() || dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).hidesHud()) return;
		WindowsMedia.Track track = showing() ? MediaSource.track() : null;
		if (track == null) return;
		Lyrics.Song song = Lyrics.get(track);
		List<Lyrics.Line> rows = song.lines();
		if (song.status() == Lyrics.Status.ESTIMATED && !guessTiming.get()) return;
		if (!rows.isEmpty()) drawLines(event, rows, MediaSource.position(track) + offset.get());
	}

	/** The colour words land in; {@code index} shifts it along for moving palettes. */
	private int active(int index) {
		return switch (palette.get()) {
			case THEME -> LyricFont.mix(Theme.accent() | 0xFF000000, 0xFFFFFFFF, 0.75f);
			case STATIC -> activeColor.get();
			case PASTEL -> LyricFont.mix(HudColors.PASTEL.at(index, 1, 0), 0xFFFFFFFF, 0.4f);
			case RAINBOW -> HudColors.RAINBOW.at(index, 1, 0);
		};
	}

	/** Works out a song's lines once. Each goes to the next row, and is gone by the time its row is needed again. */
	private void build(List<Lyrics.Line> rows) {
		String settings = uppercase.get() + "|" + hold.get();
		if (rows == builtLines && settings.equals(builtFor)) return;
		builtLines = rows;
		builtFor = settings;
		anchors.clear();
		List<Burst> built = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Lyrics.Line line = rows.get(i);
			if (line.text().isEmpty()) continue;
			String shown = uppercase.get() ? line.text().toUpperCase(Locale.ROOT) : line.text();
			String[] words = Lyrics.words(shown);
			double next = Lyrics.lineEnd(rows, i);
			double[] times = Lyrics.wordTimes(words, line.time(), next);
			double sungUntil = times[words.length];
			double gone = Math.max(sungUntil + hold.get(), Math.min(next, sungUntil + LONGEST_STAY)) + FADE;
			int reuse = i + ROWS.length;
			if (reuse < rows.size()) gone = Math.min(gone, rows.get(reuse).time());
			built.add(new Burst(line.time(), gone, words, times, ROWS[built.size() % ROWS.length], 31L * i + 7));
		}
		bursts = built;
	}

	private void drawLines(Render3DEvent event, List<Lyrics.Line> rows, double time) {
		build(rows);
		int screenW = mc.getWindow().getGuiScaledWidth(), screenH = mc.getWindow().getGuiScaledHeight();
		int left = left(screenW, false), top = top(screenH, false);
		int areaW = width(true);
		float rowHeight = height(true) / (float) ROWS.length;
		// How big a GUI unit looks at this distance, so the box on screen matches the lines in the world
		double unit = 2 * distance.get() * Math.tan(Math.toRadians(mc.options.fov().get()) / 2) / screenH;
		float speed = animation.getFloat();
		Quaternionf camera = event.camera().orientation;
		Vec3 cameraPos = event.camera().pos;
		PoseStack matrices = event.matrices();
		for (Burst burst : bursts) {
			if (time < burst.start) break;
			if (time >= burst.gone) {
				anchors.remove(burst.seed);
				continue;
			}
			float out = Mth.clamp((float) (burst.gone - time) / FADE, 0, 1);

			// Measured at a typical size; font copies differ by a hair at most
			int lineWidth = Math.max(1, mc.font.width(font.get().text(String.join(" ", burst.words), 2, false)));
			// As big as its row allows, times Text Size, shrinking only if the line is too wide for the box
			float scale = Math.max(0.5f, Math.min((rowHeight - 2) / 11 * textSize.getFloat(), (areaW - 12f) / lineWidth));
			// Long lines roll less, so their ends don't swing into the rows above and below
			double most = Math.asin(Math.min(1, rowHeight * 0.7 / (lineWidth * scale)));
			float roll = (random(burst.seed, 1) * 2 - 1) * (float) Math.min(Math.toRadians(tilt.get()), most);
			float yaw = (random(burst.seed, 3) * 2 - 1) * (float) Math.toRadians(tilt.get() * 3);
			float pitch = (random(burst.seed, 4) * 2 - 1) * (float) Math.toRadians(tilt.get() * 2);

			Anchor anchor = anchors.get(burst.seed);
			if (anchor == null || !stayInWorld.get()) {
				float halfWidth = lineWidth * scale / 2;
				float x = left + 4 + halfWidth + random(burst.seed, 2) * Math.max(0, areaW - 8 - halfWidth * 2);
				float y = top + rowHeight * (burst.row + 0.5f);
				Vector3f place = new Vector3f((float) ((x - screenW / 2f) * unit), (float) ((screenH / 2f - y) * unit), -distance.getFloat()).rotate(camera);
				anchor = new Anchor(cameraPos.add(place.x, place.y, place.z), new Quaternionf(camera).rotateXYZ(pitch, yaw, roll), cameraPos);
				anchors.put(burst.seed, anchor);
			}

			matrices.pushPose();
			Vec3 pos = anchor.pos.add(cameraPos.subtract(anchor.cameraThen).scale(follow.get() / 100));
			matrices.translate((float) (pos.x - cameraPos.x), (float) (pos.y - cameraPos.y), (float) (pos.z - cameraPos.z));
			matrices.mulPose(anchor.facing);
			matrices.scale((float) unit * scale, -(float) unit * scale, (float) unit * scale);
			float wordX = -lineWidth / 2f;
			int color = active((int) burst.seed);
			for (int w = 0; w < burst.words.length; w++) {
				String piece = w < burst.words.length - 1 ? burst.words[w] + " " : burst.words[w];
				// A little sharper than it looks from here, for walking up to it
				Component word = font.get().text(piece, scale * 1.5f, false);
				int wordWidth = mc.font.width(word);
				float age = (float) (time - burst.times[w]);
				if (age >= 0) {
					float pop = Mth.clamp(age * speed / 0.26f, 0, 1);
					float alpha = Math.min(1, pop * 4) * out;
					// Overshoots its final size and settles, harder with more Bounce, tipping upright as it lands
					float overshoot = 1.70158f * bounce.getFloat();
					float back = 1 + (overshoot + 1) * cube(pop - 1) + overshoot * (pop - 1) * (pop - 1);
					float grow = Math.max(0.05f, 0.5f + 0.5f * back);
					matrices.pushPose();
					matrices.translate(wordX + wordWidth / 2f, -(1 - pop) * 5 * bounce.getFloat(), (1 - pop) * 8 * bounce.getFloat());
					matrices.scale(grow, grow, grow);
					matrices.mulPose(Axis.XP.rotationDegrees((1 - pop) * 55 * Math.min(1, bounce.getFloat())));
					paint(event, word, -wordWidth / 2f, -4.5f, LyricFont.fade(color, alpha), alpha);
					matrices.popPose();
				}
				wordX += wordWidth;
			}
			matrices.popPose();
		}
	}

	/**
	 * A word with real thickness: copies stepping back into the distance behind the face, and a glow
	 * behind those. Each set is finished before the next starts, so they stack the right way round
	 * even when drawn through blocks, where nothing sorts them by distance.
	 */
	private void paint(Render3DEvent event, Component text, float x, float y, int color, float strength) {
		int layers = depth.getInt();
		if (glow.get() && strength > 0.02f) {
			int soft = LyricFont.fade(glowColor.get() | 0xFF000000, strength * glowStrength.getFloat() * 0.22f);
			for (int i = 0; i < 8; i++) {
				double angle = Math.PI / 4 * i;
				layer(event, text, x + (float) Math.cos(angle) * 0.7f, y + (float) Math.sin(angle) * 0.7f, -(layers + 1) * LAYER_GAP, soft);
			}
			finishLayer(event);
		}
		if (layers > 0 && strength > 0.02f) {
			int side = LyricFont.fade(depthColor.get(), strength);
			for (int i = layers; i >= 1; i--) layer(event, text, x, y, -i * LAYER_GAP, side);
			finishLayer(event);
		}
		layer(event, text, x, y, 0, color);
		finishLayer(event);
	}

	private void layer(Render3DEvent event, Component text, float x, float y, float z, int color) {
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate(0, 0, z);
		event.consumers().drawText(mc.font, text, x, y, color, false, matrices.last().pose(),
			throughBlocks.get() ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0, 0xF000F0);
		matrices.popPose();
	}

	private static void finishLayer(Render3DEvent event) {
		event.consumers().endBatch();
	}

	private static float cube(float value) {
		return value * value * value;
	}

	/** A steady number from 0 to 1 for this seed and purpose, so a line looks the same every frame. */
	private static float random(long seed, int salt) {
		long mixed = (seed * 0x9E3779B97F4A7C15L + salt) * 0xBF58476D1CE4E5B9L;
		mixed ^= mixed >>> 29;
		mixed *= 0x94D049BB133111EBL;
		mixed ^= mixed >>> 32;
		return (mixed & 0xFFFFFF) / (float) 0x1000000;
	}
}
