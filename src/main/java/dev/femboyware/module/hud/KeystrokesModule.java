package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.ColorUtil;
import dev.femboyware.util.KeyUtil;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;

/** W, A, S, D, space and both mouse buttons, lighting up as you press them, with clicks per second. */
public class KeystrokesModule extends HudElement {
	private static final int KEY = 20;
	private static final int GAP = 2;

	private final BoolSetting mouse = add(new BoolSetting("Mouse", "Show the mouse buttons with clicks per second", true));
	private final BoolSetting space = add(new BoolSetting("Space", "Show the space bar", true));

	private final ColorSetting pressedText = add(new ColorSetting("Pressed Text Color", "Text on a key while it's held", 0xFF1A0A18)).visibleWhen(this::usesCustomColors);

	/** When each recent click happened, for counting clicks per second. */
	private final Deque<Long> leftClicks = new ArrayDeque<>();
	private final Deque<Long> rightClicks = new ArrayDeque<>();
	private boolean leftWasDown;
	private boolean rightWasDown;

	public KeystrokesModule() {
		super("Keystrokes", "Shows the keys and mouse buttons you press", 1, 60);
	}

	@Override
	public int width(boolean editing) {
		return KEY * 3 + GAP * 2;
	}

	@Override
	public int height(boolean editing) {
		int rows = 2 + (mouse.get() ? 1 : 0);
		return rows * KEY + (rows - 1) * GAP + (space.get() ? GAP + 10 : 0);
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		// Clicks are caught on every frame, so fast clicking between ticks still counts
		long now = System.currentTimeMillis();
		boolean leftDown = mc.player != null && KeyUtil.isPhysicallyPressed(mc.options.keyAttack);
		boolean rightDown = mc.player != null && KeyUtil.isPhysicallyPressed(mc.options.keyUse);
		if (leftDown && !leftWasDown) leftClicks.add(now);
		if (rightDown && !rightWasDown) rightClicks.add(now);
		leftWasDown = leftDown;
		rightWasDown = rightDown;
		while (!leftClicks.isEmpty() && now - leftClicks.peekFirst() > 1000) leftClicks.pollFirst();
		while (!rightClicks.isEmpty() && now - rightClicks.peekFirst() > 1000) rightClicks.pollFirst();

		key(context, left + KEY + GAP, top, KEY, KEY, "W", mc.options.keyUp);
		int row = top + KEY + GAP;
		key(context, left, row, KEY, KEY, "A", mc.options.keyLeft);
		key(context, left + KEY + GAP, row, KEY, KEY, "S", mc.options.keyDown);
		key(context, left + (KEY + GAP) * 2, row, KEY, KEY, "D", mc.options.keyRight);
		row += KEY + GAP;
		if (mouse.get()) {
			int half = (width(editing) - GAP) / 2;
			button(context, left, row, half, leftDown, "LMB", leftClicks.size());
			button(context, left + half + GAP, row, half, rightDown, "RMB", rightClicks.size());
			row += KEY + GAP;
		}
		if (space.get()) {
			boolean down = mc.player != null && KeyUtil.isPhysicallyPressed(mc.options.keyJump);
			box(context, left, row, width(editing), 10, down);
			context.fill(left + width(editing) / 2 - 8, row + 5, left + width(editing) / 2 + 8, row + 6, down ? pressedText.get() : textColor());
		}
	}

	private void key(GuiGraphics context, int x, int y, int w, int h, String label, KeyMapping binding) {
		boolean down = mc.player != null && KeyUtil.isPhysicallyPressed(binding);
		box(context, x, y, w, h, down);
		context.drawString(mc.font, label, x + (w - mc.font.width(label)) / 2, y + (h - 8) / 2, down ? pressedText.get() : textColor());
	}

	private void button(GuiGraphics context, int x, int y, int w, boolean down, String label, int cps) {
		box(context, x, y, w, KEY, down);
		int text = down ? pressedText.get() : textColor();
		context.drawString(mc.font, label, x + (w - mc.font.width(label)) / 2, y + 3, text);
		String count = cps + " CPS";
		context.pose().pushMatrix();
		context.pose().translate(x + w / 2f - mc.font.width(count) * 0.35f, y + 12);
		context.pose().scale(0.7f, 0.7f);
		context.drawString(mc.font, count, 0, 0, down ? text : mutedColor());
		context.pose().popMatrix();
	}

	/** A key: glass when up, filled with the accent when held. */
	private void box(GuiGraphics context, int x, int y, int w, int h, boolean down) {
		Glass.draw(context, x, y, x + w, y + h, 4, down ? ColorUtil.withAlpha(accentColor(), 0xE0) : backgroundColor(Theme.list()), false);
	}
}
