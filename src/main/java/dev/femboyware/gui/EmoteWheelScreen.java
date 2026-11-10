package dev.femboyware.gui;

import dev.femboyware.module.render.EmotesModule;
import dev.femboyware.module.render.EmotesModule.Emote;
import dev.femboyware.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A radial emote picker. Hold the wheel key, point the mouse at an emote and let go, or click one.
 * The middle stops whatever emote is playing.
 */
public class EmoteWheelScreen extends Screen {
	private static final int SLOTS = 8;
	private static final int RADIUS = 72, SLOT_SIZE = 44, CENTER_SIZE = 52, DEAD_ZONE = 24;
	/** Pointing at nothing, or at the middle. */
	private static final int NONE = -1, CENTER = -2;

	private final EmotesModule module;
	private int hovered = NONE;
	private long opened;

	public EmoteWheelScreen(EmotesModule module) {
		super(Component.literal("Emotes"));
		this.module = module;
	}

	@Override
	protected void init() {
		opened = System.currentTimeMillis();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, 0x50000000);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		float scale = UiScale.scale();
		context.pose().pushMatrix();
		context.pose().scale(scale, scale);
		UiScale.begin();
		renderScaled(context, (int) (width / scale), (int) (height / scale), mouseX / scale, mouseY / scale);
		UiScale.end();
		context.pose().popMatrix();
	}

	private static Emote emoteIn(int slot) {
		Emote[] emotes = Emote.values();
		return slot >= 0 && slot < emotes.length ? emotes[slot] : null;
	}

	private void renderScaled(GuiGraphics context, int canvasWidth, int canvasHeight, float mouseX, float mouseY) {
		Font font = this.font;
		int cx = canvasWidth / 2, cy = canvasHeight / 2;
		float grow = Math.min(1, (System.currentTimeMillis() - opened) / 120f);
		int radius = Math.round(RADIUS * (0.7f + 0.3f * grow));
		hovered = pick(mouseX - cx, mouseY - cy);

		// The ring the slots sit on
		Glass.ellipse(context, cx - radius - SLOT_SIZE / 2 - 6, cy - radius - SLOT_SIZE / 2 - 6, cx + radius + SLOT_SIZE / 2 + 6, cy + radius + SLOT_SIZE / 2 + 6, 0x40000000);

		for (int slot = 0; slot < SLOTS; slot++) {
			double angle = slotAngle(slot);
			int x = cx + (int) Math.round(Math.sin(angle) * radius), y = cy - (int) Math.round(Math.cos(angle) * radius);
			Emote emote = emoteIn(slot);
			boolean hot = slot == hovered && emote != null;
			boolean on = emote != null && emote == module.playing();
			int size = hot ? SLOT_SIZE + 6 : SLOT_SIZE;
			int tint = hot ? ColorUtil.withAlpha(Theme.accent(), 0xA0) : on ? ColorUtil.withAlpha(Theme.accent(), 0x60) : Theme.panel();
			Glass.draw(context, x - size / 2, y - size / 2, x + size / 2, y + size / 2, size / 2, tint, true);
			if (emote == null) {
				String soon = "soon";
				context.drawString(font, soon, x - font.width(soon) / 2, y - 4, Theme.muted());
			} else {
				icon(context, emote, x, y);
			}
		}

		boolean stopHot = hovered == CENTER && module.playing() != null;
		int center = stopHot ? CENTER_SIZE + 4 : CENTER_SIZE;
		Glass.draw(context, cx - center / 2, cy - center / 2, cx + center / 2, cy + center / 2, center / 2,
			stopHot ? ColorUtil.withAlpha(Theme.accent(), 0xA0) : Theme.panel(), true);
		String title;
		if (hovered == CENTER) title = module.playing() != null ? "Stop" : "Emotes";
		else if (emoteIn(hovered) != null) title = emoteIn(hovered).label;
		else title = "Emotes";
		drawCentered(context, font, title, cx, cy - 4, Theme.text());

		String help = "Let go over an emote, or click one";
		drawCentered(context, font, help, cx, cy + radius + SLOT_SIZE / 2 + 14, Theme.muted());
	}

	private static void drawCentered(GuiGraphics context, Font font, String text, int x, int y, int color) {
		context.drawString(font, text, x - font.width(text) / 2, y, color);
	}

	/** A little picture of the emote in its slot. */
	private static void icon(GuiGraphics context, Emote emote, int x, int y) {
		switch (emote) {
			case BAND_SPREAD -> {
				// Three bills fanned out from a point below the middle
				for (int i = -1; i <= 1; i++) {
					context.pose().pushMatrix();
					context.pose().translate(x, y + 9);
					context.pose().rotate(i * 0.45f - (float) Math.PI / 2);
					context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BILL, 0, -5, 0, 0, 24, 11, 64, 28, 64, 28);
					context.pose().popMatrix();
				}
			}
		}
	}

	/** Slot 0 is at the top, going clockwise. */
	private static double slotAngle(int slot) {
		return Math.PI * 2 * slot / SLOTS;
	}

	private static int pick(float dx, float dy) {
		if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) return CENTER;
		double angle = Math.atan2(dx, -dy);
		if (angle < 0) angle += Math.PI * 2;
		return (int) Math.round(angle / (Math.PI * 2 / SLOTS)) % SLOTS;
	}

	/** Plays what's pointed at. Returns false when nothing is, so the wheel stays open. */
	private boolean choose() {
		if (hovered == CENTER) {
			if (module.playing() == null) return false;
			module.stop();
		} else {
			Emote emote = emoteIn(hovered);
			if (emote == null) return false;
			module.play(emote);
		}
		onClose();
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && choose()) return true;
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		if (input.key() == module.wheelKey()) {
			// A quick tap leaves the wheel open to click on instead
			if (!choose() && System.currentTimeMillis() - opened > 250) onClose();
			return true;
		}
		return super.keyReleased(input);
	}
}
