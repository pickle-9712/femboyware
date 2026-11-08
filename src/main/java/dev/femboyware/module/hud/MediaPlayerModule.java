package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.KeySetting;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * What's playing on your PC (Spotify, a browser, anything Windows shows in its media controls):
 * album art, title, artist and a live progress bar, with keys to play, pause and skip.
 *
 * <p>The song comes from {@link MediaSource}. It only works on Windows; elsewhere it just shows nothing.
 */
public class MediaPlayerModule extends HudElement {
	private static final int WIDTH = 170;
	private static final int HEIGHT = 46;

	private final KeySetting playPause = add(new KeySetting("Play Pause Key", "Press to play or pause", -1));
	private final KeySetting next = add(new KeySetting("Next Key", "Press to skip to the next track", -1));
	private final KeySetting previous = add(new KeySetting("Previous Key", "Press to go back a track", -1));

	public MediaPlayerModule() {
		super("Media Player", "Shows what's playing on your PC, with play and skip keys", 50, 4);
	}

	@Override
	protected void onEnable() {
		MediaSource.acquire(this);
	}

	@Override
	protected void onDisable() {
		MediaSource.release(this);
	}

	@Override
	public void onKey(int key) {
		if (playPause.is(key)) MediaSource.control(WindowsMedia.TOGGLE_PLAY_PAUSE);
		else if (next.is(key)) MediaSource.control(WindowsMedia.SKIP_NEXT);
		else if (previous.is(key)) MediaSource.control(WindowsMedia.SKIP_PREVIOUS);
	}

	private boolean active() {
		return MediaSource.track() != null;
	}

	@Override
	public int width(boolean editing) {
		return editing || active() ? WIDTH : 0;
	}

	@Override
	public int height(boolean editing) {
		return HEIGHT;
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		WindowsMedia.Track current = MediaSource.track();
		boolean active = current != null;
		if (!active && !editing) return;
		Glass.draw(context, left, top, left + WIDTH, top + HEIGHT, 6, backgroundColor(Theme.panel()), true);

		String title = active ? current.title() : "Nothing playing";
		String artist = active ? current.artist() : "Play something on your PC";
		boolean playing = active && current.playing();

		// Album art, or a soft placeholder note
		int artSize = 34;
		if (active && MediaSource.prepareArt()) {
			context.blit(RenderPipelines.GUI_TEXTURED, MediaSource.ART, left + 6, top + 6, 0, 0, artSize, artSize, artSize, artSize);
		} else {
			Glass.rounded(context, left + 6, top + 6, left + 6 + artSize, top + 6 + artSize, 4, 0x40FFFFFF);
			context.drawString(mc.font, "♪", left + 6 + artSize / 2 - 3, top + 6 + artSize / 2 - 4, textColor());
		}

		int textLeft = left + 46;
		int textWidth = WIDTH - 52;
		context.drawString(mc.font, fit(title, textWidth - 10), textLeft, top + 6, textColor());
		context.drawString(mc.font, fit(artist, textWidth), textLeft, top + 17, mutedColor());
		// A little play or pause mark
		String mark = playing ? "▶" : "Ⅱ";
		context.drawString(mc.font, mark, left + WIDTH - 6 - mc.font.width(mark), top + 6, playing ? accentColor() : mutedColor());

		if (active) {
			double length = current.length();
			double position = MediaSource.position(current);
			int barTop = top + HEIGHT - 9;
			Glass.rounded(context, textLeft, barTop, left + WIDTH - 6, barTop + 3, 1, 0x40FFFFFF);
			if (length > 0) {
				int filled = textLeft + (int) Math.round((left + WIDTH - 6 - textLeft) * position / length);
				if (filled > textLeft) Glass.rounded(context, textLeft, barTop, filled, barTop + 3, 1, accentColor());
				String times = clock(position) + " / " + clock(length);
				context.pose().pushMatrix();
				context.pose().translate(textLeft, barTop - 7);
				context.pose().scale(0.7f, 0.7f);
				context.drawString(mc.font, times, 0, 0, mutedColor());
				context.pose().popMatrix();
			}
		}
	}

	/** {@code text}, cut short with an ellipsis if it's wider than {@code width}. */
	private String fit(String text, int width) {
		if (mc.font.width(text) <= width) return text;
		return mc.font.plainSubstrByWidth(text, width - mc.font.width("...")) + "...";
	}

	private static String clock(double seconds) {
		int total = (int) seconds;
		return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
	}
}
