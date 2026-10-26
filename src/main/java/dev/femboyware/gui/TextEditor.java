package dev.femboyware.gui;

import dev.femboyware.setting.StringSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Cursor, selection and horizontal scroll for one text box in the ClickGUI. Supports clicking and
 * dragging to select, double-click for a word, Ctrl+A/C/X/V, Ctrl and Shift with the arrow keys,
 * Home/End, Delete, and scrolling long text left and right.
 */
final class TextEditor {
	/** Space between the box edge and the text. */
	private static final int PADDING = 2;

	private final StringSetting setting;
	private int cursor;
	/** Other end of the selection; equal to {@link #cursor} when nothing is selected. */
	private int anchor;
	/** How many pixels of text are scrolled off the left edge. */
	private int scroll;
	/** When the cursor last moved or the text changed; the cursor stays solid for a moment after. */
	private long lastActivity;

	TextEditor(StringSetting setting) {
		this.setting = setting;
		cursor = anchor = setting.get().length();
	}

	// ---------------------------------------------------------------- editing

	/** Handles a key while this box has focus. Returns false for keys it doesn't use. */
	boolean keyPressed(KeyInput input) {
		clamp();
		lastActivity = System.currentTimeMillis();
		boolean shift = input.hasShift() || isDown(GLFW.GLFW_KEY_LEFT_SHIFT) || isDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
		boolean word = isCtrl(input);
		boolean shortcut = word && !input.hasAlt();
		int key = input.key();
		if (shortcut && key == GLFW.GLFW_KEY_A) {
			anchor = 0;
			cursor = text().length();
		} else if (shortcut && key == GLFW.GLFW_KEY_C) {
			if (hasSelection()) clipboard(selected());
		} else if (shortcut && key == GLFW.GLFW_KEY_X) {
			if (hasSelection()) {
				clipboard(selected());
				replaceSelection("");
			}
		} else if (shortcut && key == GLFW.GLFW_KEY_V) {
			replaceSelection(MinecraftClient.getInstance().keyboard.getClipboard().replaceAll("[\\r\\n\\t]+", " "));
		} else {
			switch (key) {
				case GLFW.GLFW_KEY_BACKSPACE -> {
					if (!hasSelection()) anchor = word ? wordStart(cursor) : Math.max(0, cursor - 1);
					replaceSelection("");
				}
				case GLFW.GLFW_KEY_DELETE -> {
					if (!hasSelection()) anchor = word ? wordEnd(cursor) : Math.min(text().length(), cursor + 1);
					replaceSelection("");
				}
				case GLFW.GLFW_KEY_LEFT -> {
					if (hasSelection() && !shift) moveTo(Math.min(cursor, anchor), false);
					else moveTo(word ? wordStart(cursor) : Math.max(0, cursor - 1), shift);
				}
				case GLFW.GLFW_KEY_RIGHT -> {
					if (hasSelection() && !shift) moveTo(Math.max(cursor, anchor), false);
					else moveTo(word ? wordEnd(cursor) : Math.min(text().length(), cursor + 1), shift);
				}
				case GLFW.GLFW_KEY_HOME -> moveTo(0, shift);
				case GLFW.GLFW_KEY_END -> moveTo(text().length(), shift);
				default -> {
					return false;
				}
			}
		}
		return true;
	}

	void type(String typed) {
		lastActivity = System.currentTimeMillis();
		replaceSelection(typed);
	}

	private void replaceSelection(String replacement) {
		clamp();
		String text = text();
		int start = Math.min(cursor, anchor);
		int end = Math.max(cursor, anchor);
		setting.set(text.substring(0, start) + replacement + text.substring(end));
		cursor = anchor = start + replacement.length();
	}

	private void moveTo(int index, boolean select) {
		cursor = index;
		if (!select) anchor = cursor;
	}

	/** Start of the word before {@code index}, skipping any spaces first. */
	private int wordStart(int index) {
		String text = text();
		while (index > 0 && text.charAt(index - 1) == ' ') index--;
		while (index > 0 && text.charAt(index - 1) != ' ') index--;
		return index;
	}

	/** End of the word after {@code index}, skipping any spaces first. */
	private int wordEnd(int index) {
		String text = text();
		while (index < text.length() && text.charAt(index) == ' ') index++;
		while (index < text.length() && text.charAt(index) != ' ') index++;
		return index;
	}

	// ---------------------------------------------------------------- mouse

	/** Puts the cursor under {@code mouseX}; with {@code extend} the selection grows to it instead. */
	void click(TextRenderer font, double mouseX, int left, boolean extend) {
		clamp();
		lastActivity = System.currentTimeMillis();
		moveTo(indexAt(font, mouseX, left), extend);
	}

	/** Selects the word under the cursor, for double-clicks. */
	void selectWord() {
		clamp();
		String text = text();
		int start = cursor, end = cursor;
		while (start > 0 && text.charAt(start - 1) != ' ') start--;
		while (end < text.length() && text.charAt(end) != ' ') end++;
		anchor = start;
		cursor = end;
	}

	void drag(TextRenderer font, double mouseX, int left) {
		clamp();
		lastActivity = System.currentTimeMillis();
		moveTo(indexAt(font, mouseX, left), true);
	}

	/** Scrolls by {@code pixels}, positive to the right, without moving the cursor. */
	void scrollBy(TextRenderer font, int pixels, int innerWidth) {
		scroll = clampScroll(font, scroll + pixels, innerWidth);
	}

	/** The character boundary nearest {@code mouseX}. */
	private int indexAt(TextRenderer font, double mouseX, int left) {
		String text = text();
		double x = mouseX - (left + PADDING) + scroll;
		for (int i = 0; i < text.length(); i++) {
			int before = font.getWidth(text.substring(0, i));
			int after = font.getWidth(text.substring(0, i + 1));
			if (x < (before + after) / 2.0) return i;
		}
		return text.length();
	}

	// ---------------------------------------------------------------- drawing

	void render(DrawContext context, TextRenderer font, int left, int top, int right, int bottom, boolean focused, int textColor, int selectionColor, int caretColor) {
		clamp();
		String text = text();
		int innerWidth = right - left - PADDING * 2;
		if (focused) keepCursorVisible(font, innerWidth);
		else scroll = clampScroll(font, scroll, innerWidth);

		int textX = left + PADDING - scroll;
		int textY = top + 2;
		context.enableScissor(left + 1, top, right - 1, bottom);
		if (focused && hasSelection()) {
			int from = textX + font.getWidth(text.substring(0, Math.min(cursor, anchor)));
			int to = textX + font.getWidth(text.substring(0, Math.max(cursor, anchor)));
			context.fill(from, top + 1, to, bottom - 1, selectionColor);
		}
		context.drawTextWithShadow(font, text, textX, textY, textColor);
		// Solid while typing or moving, blinking once idle, like any text box
		long now = System.currentTimeMillis();
		if (focused && (now - lastActivity < 500 || (now / 500) % 2 == 0)) {
			int caret = textX + font.getWidth(text.substring(0, cursor));
			context.fill(caret, top + 1, caret + 1, bottom - 1, caretColor);
		}
		context.disableScissor();
	}

	private void keepCursorVisible(TextRenderer font, int innerWidth) {
		int caret = font.getWidth(text().substring(0, cursor));
		if (caret - scroll > innerWidth - 1) scroll = caret - innerWidth + 1;
		if (caret - scroll < 0) scroll = caret;
		scroll = clampScroll(font, scroll, innerWidth);
	}

	private int clampScroll(TextRenderer font, int value, int innerWidth) {
		int max = Math.max(0, font.getWidth(text()) - innerWidth + 1);
		return Math.max(0, Math.min(max, value));
	}

	// ---------------------------------------------------------------- helpers

	private String text() {
		return setting.get();
	}

	/** The setting can change elsewhere, e.g. a config load; keep the indices inside the text. */
	private void clamp() {
		int length = text().length();
		cursor = Math.min(cursor, length);
		anchor = Math.min(anchor, length);
	}

	private boolean hasSelection() {
		return cursor != anchor;
	}

	private String selected() {
		return text().substring(Math.min(cursor, anchor), Math.max(cursor, anchor));
	}

	/** Ctrl (Cmd on macOS) held, from the key event or, failing that, the keyboard itself. */
	private static boolean isCtrl(KeyInput input) {
		return input.hasCtrlOrCmd() || isDown(GLFW.GLFW_KEY_LEFT_CONTROL) || isDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	private static boolean isDown(int key) {
		return InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow(), key);
	}

	private static void clipboard(String text) {
		MinecraftClient.getInstance().keyboard.setClipboard(text);
	}
}
