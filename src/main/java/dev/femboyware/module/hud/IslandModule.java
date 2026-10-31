package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A slim bar: the time, then a pill with the album art, the current lyric or song with the word being
 * sung picked out, and moving equaliser bars, then your ping. The bars are only an animation; the game
 * can't hear the music.
 */
public class IslandModule extends HudElement {
	private static final int HEIGHT = 20;
	private static final int GAP = 7;

	private final BoolSetting clock = add(new BoolSetting("Clock", "Show the time", true));
	private final BoolSetting twelveHour = add(new BoolSetting("12 Hour", "5:37 PM instead of 17:37", false)).visibleWhen(clock::get);
	private final BoolSetting seconds = add(new BoolSetting("Seconds", "Show seconds on the clock", false)).visibleWhen(clock::get);
	private final BoolSetting ping = add(new BoolSetting("Ping", "Show your ping", true));
	private final BoolSetting pingColors = add(new BoolSetting("Ping Colors", "Green, yellow or red by how good your ping is", true)).visibleWhen(ping::get);
	private final BoolSetting italic = add(new BoolSetting("Italic", "Slant the time and ping", true));
	private final BoolSetting art = add(new BoolSetting("Album Art", "Show the album art", true));
	private final BoolSetting bars = add(new BoolSetting("Equalizer", "Moving bars while music plays", true));
	private final BoolSetting lyrics = add(new BoolSetting("Lyrics", "Show the current lyric when there is one, instead of the song's name", true));
	private final BoolSetting highlight = add(new BoolSetting("Word Highlight", "Pick out the word being sung (estimated, as lyrics are only timed by the line)", true)).visibleWhen(lyrics::get);
	private final BoolSetting hideIdle = add(new BoolSetting("Hide Pill When Idle", "Only the time and ping when nothing's playing", true));
	private final NumberSetting pillWidth = add(new NumberSetting("Width", "How wide the pill is", 170, 80, 360, 5));
	private final EnumSetting<LyricFont> font = add(new EnumSetting<>("Font", "Which font to use", LyricFont.INTER));
	private final NumberSetting offset = add(new NumberSetting("Lyric Offset", "Nudge lyric timing, in seconds; higher shows them sooner", 0, -5, 5, 0.05)).visibleWhen(lyrics::get);
	private final ColorSetting pillColor = add(new ColorSetting("Pill Color", "Tint of the pill", 0xB0141028));
	private final ColorSetting chipColor = add(new ColorSetting("Highlight Color", "Colour behind the word being sung", 0xFF7A4BD8));
	private final ColorSetting textColor = add(new ColorSetting("Text Color", "Colour of the words in the pill", 0xFFF2EEFF));
	private final ColorSetting barColor = add(new ColorSetting("Equalizer Color", "Colour of the moving bars", 0xFFC9A6FF));
	private final ColorSetting infoColor = add(new ColorSetting("Time Color", "Colour of the time and ping", 0xFFFFFFFF));

	private long lastFrame;
	private String shownLine = "";
	private long lineChanged;
	private float chipX, chipWidth, marquee;
	private float barsLevel;

	public IslandModule() {
		super("Island", "The time, what's playing and your ping in a slim bar", 1, 5, false);
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

	private String time() {
		String pattern = twelveHour.get() ? (seconds.get() ? "h:mm:ss a" : "h:mm a") : (seconds.get() ? "HH:mm:ss" : "HH:mm");
		return LocalTime.now().format(DateTimeFormatter.ofPattern(pattern));
	}

	private int latency() {
		if (mc.player == null || mc.getNetworkHandler() == null) return 0;
		PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
		return entry == null ? 0 : entry.getLatency();
	}

	private boolean pillShown(boolean editing) {
		return editing || MediaSource.track() != null || !hideIdle.get();
	}

	private Text info(String text) {
		return font.get().text(text, 1, italic.get());
	}

	private int clockWidth() {
		return clock.get() ? mc.textRenderer.getWidth(info(time())) + GAP : 0;
	}

	private int pingWidth() {
		return ping.get() ? mc.textRenderer.getWidth(info(Integer.toString(latency()))) + 13 + GAP : 0;
	}

	@Override
	public int width(boolean editing) {
		return clockWidth() + (pillShown(editing) ? pillWidth.getInt() : 0) + pingWidth();
	}

	@Override
	public int height(boolean editing) {
		return HEIGHT;
	}

	@Override
	protected void draw(DrawContext context, int left, int top, boolean editing) {
		long now = System.nanoTime();
		float frame = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		int x = left;
		int textY = top + (HEIGHT - 8) / 2;
		if (clock.get()) {
			context.drawText(mc.textRenderer, info(time()), x, textY, infoColor.get(), false);
			x += clockWidth();
		}
		if (pillShown(editing)) {
			drawPill(context, x, top, editing, frame);
			x += pillWidth.getInt() + GAP;
		}
		if (ping.get()) {
			int latency = latency();
			int color = !pingColors.get() ? infoColor.get() : latency < 80 ? 0xFF7CF0A0 : latency < 180 ? 0xFFFFD36B : 0xFFFF6B7A;
			signal(context, x, top + HEIGHT / 2 + 4, color);
			context.drawText(mc.textRenderer, info(Integer.toString(latency)), x + 13, textY, infoColor.get(), false);
		}
	}

	private void drawPill(DrawContext context, int left, int top, boolean editing, float frame) {
		int width = pillWidth.getInt();
		Glass.draw(context, left, top, left + width, top + HEIGHT, HEIGHT / 2, pillColor.get(), true);
		WindowsMedia.Track track = MediaSource.track();

		int textLeft = left + 8;
		if (art.get()) {
			int side = HEIGHT - 6;
			if (track != null && MediaSource.prepareArt()) {
				context.drawTexture(RenderPipelines.GUI_TEXTURED, MediaSource.ROUND_ART, left + 3, top + 3, 0, 0, side, side, side, side);
			} else Glass.rounded(context, left + 3, top + 3, left + 3 + side, top + 3 + side, side / 2, 0x40FFFFFF);
			textLeft = left + 3 + side + 5;
		}
		int textRight = left + width - 9;
		boolean playing = track != null && track.playing();
		if (bars.get()) {
			barsLevel += ((playing ? 1 : 0.18f) - barsLevel) * (1 - (float) Math.exp(-7 * frame));
			equalizer(context, left + width - 21, top + HEIGHT / 2);
			textRight = left + width - 25;
		}

		String line;
		String[] words = null;
		double[] times = null;
		double position = 0;
		boolean isLyric = false;
		if (track == null) line = editing ? "Nothing playing" : "";
		else {
			line = track.artist().isEmpty() ? track.title() : track.title() + " · " + track.artist();
			if (lyrics.get()) {
				List<Lyrics.Line> rows = Lyrics.get(track).lines();
				position = MediaSource.position(track) + offset.get();
				int index = Lyrics.indexAt(rows, position);
				// Between lines, and in long gaps, go back to showing the song
				if (index >= 0 && !rows.get(index).text().isEmpty() && position < rows.get(index).time() + 12) {
					line = rows.get(index).text();
					isLyric = true;
					words = Lyrics.words(line);
					times = Lyrics.wordTimes(words, rows.get(index).time(), Lyrics.lineEnd(rows, index));
				}
			}
		}
		if (!line.equals(shownLine)) {
			shownLine = line;
			lineChanged = System.currentTimeMillis();
			chipWidth = 0;
			marquee = 0;
		}
		float reveal = MathHelper.clamp((System.currentTimeMillis() - lineChanged) / 180f, 0, 1);

		int area = textRight - textLeft;
		Text whole = font.get().text(line, 1, false);
		int total = mc.textRenderer.getWidth(whole);
		float wanted = 0;
		int current = -1;
		float wordX = 0, wordWidth = 0;
		if (isLyric) {
			for (int i = 0; i < words.length; i++) if (position >= times[i]) current = i;
			if (current >= 0) {
				StringBuilder before = new StringBuilder();
				for (int i = 0; i < current; i++) before.append(words[i]).append(' ');
				wordX = mc.textRenderer.getWidth(font.get().text(before.toString(), 1, false));
				wordWidth = mc.textRenderer.getWidth(font.get().text(words[current], 1, false));
				// Keep the word being sung in view on lines too long for the pill
				wanted = wordX + wordWidth / 2 - area / 2f;
			}
		} else if (total > area) {
			// Slide slowly back and forth over a song name too long for the pill
			wanted = (float) (0.5 - 0.5 * Math.cos(System.currentTimeMillis() / 4000.0 * Math.PI)) * (total - area);
		}
		wanted = MathHelper.clamp(wanted, 0, Math.max(0, total - area));
		float ease = 1 - (float) Math.exp(-12 * frame);
		marquee += (wanted - marquee) * ease;

		context.enableScissor(textLeft, top, textRight, top + HEIGHT);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(LyricFont.snap(textLeft - marquee), LyricFont.snap(top + (HEIGHT - 8) / 2f + (1 - reveal) * 4));
		if (isLyric && highlight.get() && current >= 0) {
			chipX = chipWidth == 0 ? wordX : chipX + (wordX - chipX) * ease;
			chipWidth = chipWidth == 0 ? wordWidth : chipWidth + (wordWidth - chipWidth) * ease;
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(chipX - 3, -3.5f);
			Glass.rounded(context, 0, 0, Math.round(chipWidth) + 6, 14, 4, LyricFont.fade(chipColor.get(), reveal));
			context.getMatrices().popMatrix();
		}
		context.drawText(mc.textRenderer, whole, 0, 0, LyricFont.fade(textColor.get(), reveal), false);
		context.getMatrices().popMatrix();
		context.disableScissor();
	}

	/** Four bars bobbing out of step, settling low while paused. */
	private void equalizer(DrawContext context, int left, int middle) {
		double clock = System.currentTimeMillis() / 1000.0;
		for (int i = 0; i < 4; i++) {
			float wave = (float) (0.55 + 0.45 * Math.sin(clock * (5.1 + i * 1.7) + i * 1.9));
			float half = Math.max(0.75f, 5 * barsLevel * wave);
			bar(context, left + i * 3, middle - half, 2, half * 2, barColor.get());
		}
	}

	/** Three rising bars. */
	private void signal(DrawContext context, int left, int bottom, int color) {
		for (int i = 0; i < 3; i++) {
			float tall = 3 + i * 2.5f;
			bar(context, left + i * 3, bottom - tall, 2, tall, color);
		}
	}

	/** A bar that can land between GUI units, so it moves smoothly. */
	private void bar(DrawContext context, float x, float y, float width, float height, int color) {
		int density = dev.femboyware.gui.UiScale.density();
		context.getMatrices().pushMatrix();
		context.getMatrices().scale(1f / density, 1f / density);
		context.fill(Math.round(x * density), Math.round(y * density), Math.round((x + width) * density), Math.round((y + height) * density), color);
		context.getMatrices().popMatrix();
	}
}
