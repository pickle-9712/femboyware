package dev.femboyware.module.hud;

import dev.femboyware.gui.ClickGuiScreen;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * Something drawn on the HUD, in the HUD panel. Its place is a share of the screen, so it stays
 * put at any window size; drag it about while the ClickGUI is open, or set X and Y exactly.
 */
public abstract class HudElement extends Module {
	private final NumberSetting x;
	private final NumberSetting y;
	/** Off, the element follows the theme. Null on elements with colour settings of their own. */
	private BoolSetting customColors;
	private ColorSetting customText;
	private ColorSetting customAccent;
	private ColorSetting customMuted;
	private ColorSetting customBackground;

	protected HudElement(String name, String description, double defaultX, double defaultY) {
		this(name, description, defaultX, defaultY, true);
	}

	protected HudElement(String name, String description, double defaultX, double defaultY, boolean standardColors) {
		super(name, description, Category.ELEMENTS);
		x = add(new NumberSetting("X", "Across the screen, in percent; or drag it while the ClickGUI is open", defaultX, 0, 100, 0.5));
		y = add(new NumberSetting("Y", "Down the screen, in percent; or drag it while the ClickGUI is open", defaultY, 0, 100, 0.5));
		if (standardColors) {
			customColors = add(new BoolSetting("Custom Colors", "Pick this element's colours yourself instead of following the theme", false));
			customText = add(new ColorSetting("Text Color", "Main text", 0xFFFFFFFF)).visibleWhen(this::usesCustomColors);
			customAccent = add(new ColorSetting("Accent Color", "Highlights", 0xFFFF77C8)).visibleWhen(this::usesCustomColors);
			customMuted = add(new ColorSetting("Muted Color", "Secondary text", 0xB0FFFFFF)).visibleWhen(this::usesCustomColors);
			customBackground = add(new ColorSetting("Background Color", "Tint of its background", 0x70201028)).visibleWhen(this::usesCustomColors);
		}
	}

	protected boolean usesCustomColors() {
		return customColors != null && customColors.get();
	}

	protected int textColor() {
		return usesCustomColors() ? customText.get() : Theme.text();
	}

	protected int accentColor() {
		return usesCustomColors() ? customAccent.get() : Theme.accent();
	}

	protected int mutedColor() {
		return usesCustomColors() ? customMuted.get() : Theme.muted();
	}

	/** The background tint, or {@code themed} when following the theme. */
	protected int backgroundColor(int themed) {
		return usesCustomColors() ? customBackground.get() : themed;
	}

	/** Whether its corner can be dragged in the ClickGUI to resize it. */
	public boolean resizable() {
		return false;
	}

	/** Sets a resizable element's size in GUI units. */
	public void resizeTo(int width, int height, int screenWidth, int screenHeight) {
	}

	/** Size right now, in GUI units. {@code editing} is true in the ClickGUI, where empty elements show an example. */
	public abstract int width(boolean editing);

	public abstract int height(boolean editing);

	/** Draws the element with its top left at {@code left, top}. */
	protected abstract void draw(DrawContext context, int left, int top, boolean editing);

	@Override
	public void onRender2D(DrawContext context, float tickDelta) {
		// In the ClickGUI the editor draws every element itself, with outlines, over the blur
		if (mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud() || mc.currentScreen instanceof ClickGuiScreen) return;
		if (width(false) <= 0) return;
		draw(context, left(context.getScaledWindowWidth(), false), top(context.getScaledWindowHeight(), false), false);
	}

	/** Draws it for the ClickGUI's HUD editor. */
	public void drawForEditor(DrawContext context) {
		draw(context, left(context.getScaledWindowWidth(), true), top(context.getScaledWindowHeight(), true), true);
	}

	public int left(int screenWidth, boolean editing) {
		return (int) Math.round((screenWidth - width(editing)) * x.get() / 100);
	}

	public int top(int screenHeight, boolean editing) {
		return (int) Math.round((screenHeight - height(editing)) * y.get() / 100);
	}

	/** Moves the element so its top left sits at {@code left, top}, kept on screen. */
	public void moveTo(int left, int top, int screenWidth, int screenHeight) {
		int spareX = Math.max(1, screenWidth - width(true));
		int spareY = Math.max(1, screenHeight - height(true));
		x.set(Math.round(MathHelper.clamp(left / (double) spareX, 0, 1) * 200) / 2.0);
		y.set(Math.round(MathHelper.clamp(top / (double) spareY, 0, 1) * 200) / 2.0);
	}

	/** Whether it sits on the right half of the screen, so lists line up against the right edge. */
	protected boolean onRight() {
		return x.get() > 50;
	}

	/** Whether it sits on the bottom half, so lists grow upwards. */
	protected boolean onBottom() {
		return y.get() > 50;
	}
}
