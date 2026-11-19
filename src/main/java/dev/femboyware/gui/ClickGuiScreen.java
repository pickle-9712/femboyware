package dev.femboyware.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.LarpOnlyModule;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.module.hud.HudElement;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.Choice;
import dev.femboyware.setting.ButtonSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.Setting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ColorUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The menu: one window, a sidebar of sections down the left, a search bar along the top, and the
 * section's modules as cards in two columns, each with a star to favourite it and a switch to turn
 * it on. Right click a card (or Enter) for its settings. Layout is rebuilt every frame into
 * {@link #rows}, and clicks hit-test against the rows from the last frame.
 */
public class ClickGuiScreen extends Screen {
	private static final int ROW_HEIGHT = 13;
	private static final int SIDEBAR = 112;
	private static final int CARD_HEIGHT = 30;
	private static final int GAP = 6;
	private static final int PAD = 10;
	private static final int SEARCH_HEIGHT = 18;
	private static final int CHIP_HEIGHT = 13;
	private static final int FOOTER = 16;
	private static final int NAV_HEIGHT = 13;
	private static final String[] CHANNEL_NAMES = {"Red", "Green", "Blue", "Alpha"};
	private static final int[] CHANNEL_SHIFTS = {16, 8, 0, 24};

	private enum RowType { NAV, FAVORITES, ENABLED, HUD_EDIT, GUI_SETTINGS, CONFIGS, RESET_ALL, HUD_DONE, CHIP, CARD, STAR, SWITCH, BACK, HEADER_SWITCH, SEARCH, BIND, BOOL, BUTTON, NUMBER, ENUM, ENUM_OPTION, STRING, COLOR, KEY }

	private record Row(RowType type, int x, int y, int width, int height, Category category, Module module, Setting<?> setting, int shift, Category.Group group) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}

	/** Sidebar sections: the two groups after FEATURES that are more about the client than playing. */
	private static final Category.Group[] FEATURES = {Category.Group.PVP, Category.Group.MOVEMENT, Category.Group.VISUAL, Category.Group.UTILITY};
	private static final Category.Group[] OTHERS = {Category.Group.CUSTOMIZE, Category.Group.HUD};

	private final ClickGuiModule module;
	private final List<Row> rows = new ArrayList<>();
	/** Which section is showing, and the chosen tab in each; kept while the game runs. */
	private Category.Group group = Category.Group.PVP;
	private boolean favorites;
	/** The Enabled section: every module that's on right now. */
	private boolean enabledView;
	private final Map<Category.Group, Category> tab = new EnumMap<>(Category.Group.class);
	/** The module whose settings are open, or null for the cards. */
	private Module open;
	private boolean hudEditing;
	/** How far the content is scrolled, and how tall it was last frame. */
	private int scroll, contentHeight, viewHeight;
	/** Where the content is drawn: eases toward {@link #scroll} so the wheel glides instead of jumping. */
	private float smooth;
	private long lastFrame;
	/** Where the scrolled content starts on screen this frame: the glides keep their place relative to it. */
	private int contentOrigin;
	/** The cards in order last frame, and which one has keyboard focus (-1 none). */
	private final List<Module> cards = new ArrayList<>();
	private final List<int[]> cardSpots = new ArrayList<>();
	private int focus = -1;

	/** The mode setting whose dropdown is open, if any. */
	private Choice openEnum;
	private static final int RESIZE_HANDLE = 5;
	private HudElement draggingElement;
	private HudElement resizingElement;
	private int elementOffsetX;
	private int elementOffsetY;
	private Row slider;
	private Module binding;
	private KeySetting bindingKey;
	private StringSetting typing;
	private final Map<StringSetting, TextEditor> editors = new HashMap<>();
	private int typingRowX;
	private boolean selectingText;
	private boolean closing;
	private final StringSetting search = new StringSetting("Search", "Search modules by name or description", "");
	private boolean searching;

	// ---------------------------------------------------------------- animation

	/** Seconds since the last frame, capped so a hitch doesn't jump everything to its end. */
	private float dt;
	/** Every eased value, by what it belongs to (see {@link #ease}). */
	private final Map<Object, float[]> anims = new HashMap<>();
	/** The highlights that glide from what you'd picked to what you pick next. */
	private final Glide navGlide = new Glide(), chipGlide = new Glide(), cardGlide = new Glide(), settingGlide = new Glide(), optionGlide = new Glide();
	/** The card you last clicked or moved to with the keys, and the setting you last clicked. */
	private Module selected;
	private Object selectedSetting;
	/** When the cards last changed (a new section, tab, search or coming back), so they cascade in. */
	private long cardsAt;
	private Object cardsKey;
	/** When a settings page opened, so it slides in; and when a dropdown opened, so it fades in. */
	private long settingsAt, enumAt;
	/** When each star was last clicked, so it pops. */
	private final Map<Module, Long> starPops = new HashMap<>();
	private final List<Ripple> ripples = new ArrayList<>();
	/** The tooltip showing and when it came up, so it slides in. */
	private String tooltipText;
	private long tooltipAt;

	private record Key(String kind, Object of, int index) {
	}

	// ---------------------------------------------------------------- search, undo

	/** Search results found by a setting's name: which of their settings matched. */
	private final Map<Module, List<Setting<?>>> settingHits = new HashMap<>();
	/** A setting to bring into view once its page is drawn (opened from a search). */
	private Setting<?> scrollToSetting;

	/** A module as it was before a change in the menu, to go back to. */
	private record Change(Module module, com.google.gson.JsonObject before) {
	}

	private final java.util.ArrayDeque<Change> undo = new java.util.ArrayDeque<>(), redo = new java.util.ArrayDeque<>();
	private static final int UNDO_KEPT = 100;
	/** A note in the footer for a moment (what was undone, reset...), and when it was shown. */
	private String flash;
	private long flashAt;

	/** Remembers {@code m} as it is, before something in the menu changes it. */
	private void remember(Module m) {
		if (m == null) return;
		undo.push(new Change(m, FemboyWare.getConfig().snapshot(m, false)));
		while (undo.size() > UNDO_KEPT) undo.removeLast();
		redo.clear();
	}

	/** Ctrl+Z (or Ctrl+Y / Ctrl+Shift+Z to redo): puts the module last changed back how it was. */
	private void undo(boolean redoing) {
		java.util.ArrayDeque<Change> from = redoing ? redo : undo, to = redoing ? undo : redo;
		Change change = from.poll();
		if (change == null) {
			flash(redoing ? "Nothing to redo" : "Nothing to undo");
			return;
		}
		to.push(new Change(change.module, FemboyWare.getConfig().snapshot(change.module, false)));
		FemboyWare.getConfig().apply(change.module, change.before, true);
		// Text boxes show the restored text
		editors.keySet().removeIf(setting -> setting != search);
		flash((redoing ? "Redid " : "Undid ") + change.module.getName() + (redoing ? "" : "  ·  Ctrl+Y redoes"));
	}

	private void flash(String text) {
		flash = text;
		flashAt = System.nanoTime();
	}

	/** Modules that are opened rather than turned on: this menu itself, and Configs. */
	private boolean settingsOnly(Module m) {
		return m == module || m instanceof dev.femboyware.module.client.ConfigsModule;
	}

	/** Every setting of {@code m} back to how it came, less saved data (the phone's balance and such). */
	private void resetAll(Module m) {
		remember(m);
		for (Setting<?> setting : m.getSettings()) {
			if (setting.getDescription() == null || setting.getDescription().isEmpty() || setting instanceof ButtonSetting) continue;
			setting.reset();
		}
		editors.keySet().removeIf(setting -> setting != search);
		openEnum = null;
		flash("Reset " + m.getName() + "  ·  Ctrl+Z undoes it");
	}

	/** A click's ripple: spreading out from where you clicked over the thing you clicked, then fading. */
	private record Ripple(float x, float y, int x1, int y1, int x2, int y2, int radius, long at, boolean content) {
	}

	/**
	 * A highlight that glides: it's aimed at something each frame, and eases its position and size
	 * there, fading in when it's first aimed and out when nothing is.
	 */
	private static final class Glide {
		float x, y, w, h, alpha;
		/** How fast it's moving vertically, in GUI units a second, for the stretch while it travels. */
		float speed;
		private float tx, ty, tw, th;
		private boolean aimed;

		void aim(float x, float y, float w, float h) {
			tx = x;
			ty = y;
			tw = w;
			th = h;
			aimed = true;
		}

		/** Moves toward where it was aimed (since the last step), then forgets the aim. */
		void step(float dt, float rate) {
			float k = 1 - (float) Math.exp(-dt * rate);
			if (aimed) {
				if (alpha < 0.02f) {
					// Nothing showing: appear where it's wanted rather than flying in from wherever it was
					x = tx;
					y = ty;
					w = tw;
					h = th;
					speed = 0;
				} else {
					float lastY = y;
					x += (tx - x) * k;
					y += (ty - y) * k;
					w += (tw - w) * k;
					h += (th - h) * k;
					speed = dt > 0 ? (y - lastY) / dt : 0;
					if (Math.abs(tx - x) < 0.05f && Math.abs(ty - y) < 0.05f) {
						x = tx;
						y = ty;
					}
				}
				alpha += (1 - alpha) * Math.min(1, k * 1.4f);
			} else {
				alpha += (0 - alpha) * k;
				speed = 0;
			}
			aimed = false;
		}

		boolean visible() {
			return alpha > 0.01f;
		}
	}

	/** Eases the value kept under {@code key} toward {@code target}, {@code rate} being how fast (higher is snappier). */
	private float ease(Object key, float target, float rate) {
		float[] v = anims.get(key);
		if (v == null) {
			anims.put(key, new float[]{target});
			return target;
		}
		v[0] += (target - v[0]) * (1 - (float) Math.exp(-dt * rate));
		if (Math.abs(target - v[0]) < 0.002f) v[0] = target;
		return v[0];
	}

	private float ease(String kind, Object of, float target, float rate) {
		return ease(new Key(kind, of, 0), target, rate);
	}

	/** Between two colours, alpha included: {@code t} 0 is {@code a}, 1 is {@code b}. */
	private static int mix(int a, int b, float t) {
		if (t <= 0) return a;
		if (t >= 1) return b;
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
			out |= Math.round(ca + (cb - ca) * t) << shift;
		}
		return out;
	}

	/** {@code color} with its alpha scaled by {@code t}. */
	private static int fade(int color, float t) {
		if (t >= 1) return color;
		return ColorUtil.withAlpha(color, Math.round((color >>> 24) * Math.max(0, t)));
	}

	private static float easeOut(float t) {
		t = Math.max(0, Math.min(1, t));
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	private static float seconds(long since) {
		return (System.nanoTime() - since) / 1e9f;
	}

	/** Text, unless it's faded too far to draw (the font draws nearly-clear text solid). */
	private void text(GuiGraphics context, String s, int x, int y, int color) {
		if ((color >>> 24) < 8) return;
		context.drawString(font, s, x, y, color);
	}

	/** Where the content sat when a ripple started: clicks land on last frame's rows. */
	private void ripple(Row row, double mouseX, double mouseY, int radius, boolean content) {
		ripples.add(new Ripple((float) mouseX, (float) mouseY, row.x, row.y, row.x + row.width, row.y + row.height, radius, System.nanoTime(), content));
	}

	/** The ripples on the scrolling content (drawn inside its clip) or on everything else. */
	private void renderRipples(GuiGraphics context, int accent, boolean content) {
		for (int i = ripples.size() - 1; i >= 0; i--) {
			Ripple r = ripples.get(i);
			if (r.content != content) continue;
			float t = seconds(r.at) / 0.5f;
			if (t >= 1) {
				ripples.remove(i);
				continue;
			}
			float far = (float) Math.max(Math.hypot(r.x - r.x1, r.y - r.y1), Math.max(Math.hypot(r.x - r.x2, r.y - r.y1),
				Math.max(Math.hypot(r.x - r.x1, r.y - r.y2), Math.hypot(r.x - r.x2, r.y - r.y2))));
			float radius = far * easeOut(t * 1.4f);
			int color = ColorUtil.withAlpha(mix(Theme.text(), accent, 0.5f), Math.round(0x46 * (1 - t) * (1 - t)));
			if ((color >>> 24) == 0) continue;
			int h = r.y2 - r.y1, cr = Math.min(r.radius, Math.min(r.x2 - r.x1, h) / 2);
			for (int y = r.y1; y < r.y2; y++) {
				float dy = y + 0.5f - r.y;
				if (Math.abs(dy) >= radius) continue;
				float dx = (float) Math.sqrt(radius * radius - dy * dy);
				// Kept inside the thing's rounded corners
				int row = y - r.y1, inset = 0;
				int fromEdge = Math.min(row, h - 1 - row);
				if (fromEdge < cr) {
					double ey = cr - fromEdge - 0.5;
					inset = (int) Math.round(cr - Math.sqrt(cr * cr - ey * ey));
				}
				int left = Math.max(r.x1 + inset, Math.round(r.x - dx)), right = Math.min(r.x2 - inset, Math.round(r.x + dx));
				if (right > left) context.fill(left, y, right, y + 1, color);
			}
		}
	}

	public ClickGuiScreen(ClickGuiModule module) {
		super(Component.literal("femboyware"));
		this.module = module;
	}

	public boolean isClosing() {
		return closing;
	}

	/** When it last opened, and when it started closing (0 while it isn't). */
	private long openedAt, closeAt;

	@Override
	public void added() {
		super.added();
		openedAt = System.nanoTime();
		closeAt = 0;
		cardsKey = null;
		settingsAt = openedAt;
		WorldGui.place(this);
	}

	/** Brought back while it was still animating shut. */
	public void reopen() {
		if (closeAt == 0) return;
		// Pick up from wherever the close had got to
		float shown = openness();
		double open = Math.max(0.01, module.openTime());
		openedAt = System.nanoTime() - (long) (shown * open * 1e9);
		closeAt = 0;
	}

	/** Escape, or the bind: plays the close animation, then really closes. */
	@Override
	public void onClose() {
		if (module.closeAnimation() == ClickGuiModule.Animation.NONE || hudEditing) {
			super.onClose();
			return;
		}
		if (closeAt == 0) closeAt = System.nanoTime();
	}

	@Override
	public void tick() {
		super.tick();
		if (closeAt != 0 && openness() <= 0) {
			closeAt = 0;
			super.onClose();
		}
	}

	/** How far in the window is: 0 hidden, 1 all the way open. */
	private float openness() {
		long now = System.nanoTime();
		if (closeAt != 0) {
			if (module.closeAnimation() == ClickGuiModule.Animation.NONE) return 0;
			return (float) Math.max(0, 1 - (now - closeAt) / 1e9 / Math.max(0.01, module.closeTime()));
		}
		if (module.openAnimation() == ClickGuiModule.Animation.NONE) return 1;
		return (float) Math.min(1, (now - openedAt) / 1e9 / Math.max(0.01, module.openTime()));
	}

	/** Moves, scales or spins the window for {@code animation} at {@code t} (0 hidden, 1 open). */
	private void animate(GuiGraphics context, ClickGuiModule.Animation animation, float t, float cw, float ch) {
		if (t >= 1 || animation == ClickGuiModule.Animation.NONE) return;
		float sx = 1, sy = 1, dx = 0, dy = 0, spin = 0;
		float ease = 1 - (float) Math.pow(1 - t, 3);
		switch (animation) {
			case POP -> sx = sy = backOut(t);
			case GROW -> sx = sy = ease;
			case SLIDE_UP -> dy = (1 - ease) * ch;
			case SLIDE_DOWN -> dy = -(1 - ease) * ch;
			case SLIDE_LEFT -> dx = (1 - ease) * cw;
			case SLIDE_RIGHT -> dx = -(1 - ease) * cw;
			case DROP -> dy = -(1 - bounceOut(t)) * ch;
			case UNFOLD -> sy = ease;
			case FLIP -> sx = ease;
			case SPIN -> {
				sx = sy = ease;
				spin = (1 - ease) * (float) Math.PI;
			}
			default -> {
			}
		}
		context.pose().translate(cw / 2 + dx, ch / 2 + dy);
		if (spin != 0) context.pose().rotate(spin);
		context.pose().scale(Math.max(0.001f, sx), Math.max(0.001f, sy));
		context.pose().translate(-cw / 2, -ch / 2);
	}

	/** Overshoots a touch then settles. */
	private static float backOut(float t) {
		float c = 1.7f, u = t - 1;
		return 1 + (c + 1) * u * u * u + c * u * u;
	}

	private static float bounceOut(float t) {
		if (t < 1 / 2.75f) return 7.5625f * t * t;
		if (t < 2 / 2.75f) {
			t -= 1.5f / 2.75f;
			return 7.5625f * t * t + 0.75f;
		}
		if (t < 2.5f / 2.75f) {
			t -= 2.25f / 2.75f;
			return 7.5625f * t * t + 0.9375f;
		}
		t -= 2.625f / 2.75f;
		return 7.5625f * t * t + 0.984375f;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		WorldGui.forget(this);
		closing = true;
		binding = null;
		bindingKey = null;
		typing = null;
		searching = false;
		selectingText = false;
		slider = null;
		hudEditing = false;
		module.setEnabled(false);
		closing = false;
		FemboyWare.getConfig().save();
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		// Hanging in the world: nothing over your screen
		if (WorldGui.showing(this)) return;
		if (hudEditing) {
			context.fill(0, 0, width, height, Theme.editorDim());
			return;
		}
		if (Theme.blur()) renderBlurredBackground(context);
		// The dim fades with the window
		int dim = Theme.dim();
		context.fill(0, 0, width, height, ColorUtil.withAlpha(dim, (int) ((dim >>> 24) * openness())));
		dev.femboyware.module.theme.GuiBackgroundModule.drawScreen(context, width, height, openness());
	}

	/** A click on the world panel, moved to where it lands on the GUI. */
	private MouseButtonEvent onPanel(MouseButtonEvent click) {
		if (!WorldGui.showing(this)) return click;
		double[] point = WorldGui.toGui(click.x(), click.y());
		return new MouseButtonEvent(point[0], point[1], click.buttonInfo());
	}

	/** Whether a text box or key bind is taking your keys, so they shouldn't also walk you around. */
	public boolean takesKeys() {
		return searching || typing != null || binding != null || bindingKey != null || hudEditing;
	}

	public boolean isHudEditing() {
		return hudEditing;
	}

	/** Draws the GUI into the world screen's texture (see WorldGui). */
	public void paintForWorld(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		//? if >=26.1 {
		/*extractRenderState(context, mouseX, mouseY, deltaTicks);
		*///?} else {
		render(context, mouseX, mouseY, deltaTicks);
		//?}
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		// In the world it's only drawn into the panel's texture (WorldGui.renderPass)
		if (WorldGui.showing(this) && !WorldGui.drawing) return;
		if (hudEditing) renderHudEditor(context, mouseX, mouseY);
		float scale = UiScale.scale();
		context.pose().pushMatrix();
		context.pose().scale(scale, scale);
		UiScale.begin();
		if (!hudEditing) animate(context, closeAt != 0 ? module.closeAnimation() : module.openAnimation(), openness(), canvasWidth(), canvasHeight());
		renderScaled(context, (int) (mouseX / scale), (int) (mouseY / scale));
		UiScale.end();
		context.pose().popMatrix();
	}

	private int canvasWidth() {
		return (int) (width / UiScale.scale());
	}

	private int canvasHeight() {
		return (int) (height / UiScale.scale());
	}

	// ---------------------------------------------------------------- HUD editor

	private void renderHudEditor(GuiGraphics context, int mouseX, int mouseY) {
		if (draggingElement != null) draggingElement.moveTo(mouseX - elementOffsetX, mouseY - elementOffsetY, width, height);
		if (resizingElement != null) {
			int left = resizingElement.left(width, true), top = resizingElement.top(height, true);
			resizingElement.resizeTo(mouseX - left, mouseY - top, width, height);
			resizingElement.moveTo(left, top, width, height);
		}
		UiScale.beginClient();
		try {
			for (HudElement element : hudElements()) {
				element.drawForEditor(context);
				int left = element.left(width, true), top = element.top(height, true);
				int right = left + element.width(true), bottom = top + element.height(true);
				boolean hot = element == draggingElement || (draggingElement == null && mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom);
				dashedOutline(context, left - 1, top - 1, right + 1, bottom + 1, hot ? Theme.accent() : Theme.editorOutline());
				if (element.resizable()) context.fill(right - RESIZE_HANDLE, bottom - RESIZE_HANDLE, right + 1, bottom + 1, element == resizingElement || hot ? Theme.accent() : Theme.editorOutline());
			}
		} finally {
			UiScale.endClient();
		}
	}

	private static List<HudElement> hudElements() {
		List<HudElement> elements = new ArrayList<>();
		for (Module m : FemboyWare.getModules().getModules(Category.ELEMENTS)) {
			if (m instanceof HudElement element && m.isEnabled() && element.width(true) > 0) elements.add(element);
		}
		return elements;
	}

	private static void dashedOutline(GuiGraphics context, int x1, int y1, int x2, int y2, int color) {
		for (int x = x1; x < x2; x += 4) {
			context.fill(x, y1, Math.min(x + 2, x2), y1 + 1, color);
			context.fill(x, y2 - 1, Math.min(x + 2, x2), y2, color);
		}
		for (int y = y1; y < y2; y += 4) {
			context.fill(x1, y, x1 + 1, Math.min(y + 2, y2), color);
			context.fill(x2 - 1, y, x2, Math.min(y + 2, y2), color);
		}
	}

	// ---------------------------------------------------------------- the window

	private void renderScaled(GuiGraphics context, int mouseX, int mouseY) {
		long now = System.nanoTime();
		dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (slider != null) updateSlider(mouseX);
		rows.clear();
		int accent = module.getAccent();
		if (hudEditing) {
			// Just a pill to come back by; the elements are dragged about underneath
			String label = "Drag your HUD about  ·  Done";
			int w = font.width(label) + 24, x = (canvasWidth() - w) / 2, y = 8;
			Row done = new Row(RowType.HUD_DONE, x, y, w, 18, null, null, null, 0, null);
			rows.add(done);
			Glass.draw(context, x, y, x + w, y + 18, 9, done.contains(mouseX, mouseY) ? ColorUtil.withAlpha(accent, 0xB0) : Theme.solidPanel(), true);
			context.drawString(font, label, x + 12, y + 5, Theme.text());
			return;
		}

		int w = Math.min(600, canvasWidth() - 24), h = Math.min(340, canvasHeight() - 20);
		int x1 = (canvasWidth() - w) / 2, y1 = (canvasHeight() - h) / 2, x2 = x1 + w, y2 = y1 + h;
		// A wallpaper fills the window solid; the glass then only shades it
		dev.femboyware.module.theme.WallpaperModule.draw(context, x1, y1, x2, y2, Math.max(8, Theme.roundness()));
		// Your own picture, see-through, under the glass
		dev.femboyware.module.theme.GuiBackgroundModule.drawWindow(context, x1, y1, x2, y2, Math.max(8, Theme.roundness()));
		Glass.draw(context, x1, y1, x2, y2, Math.max(8, Theme.roundness()), Theme.panel(), true);
		renderSidebar(context, x1, y1, y2 - FOOTER, mouseX, mouseY, accent);

		// The content side: search along the top, then the cards or a module's settings
		int cx1 = x1 + SIDEBAR + 4, cx2 = x2 - PAD, cy1 = y1 + PAD;
		Glass.rounded(context, cx1 - 4, y1 + 4, x2 - 4, y2 - FOOTER, 8, Theme.content());
		renderSearch(context, cx1 + 2, cy1, cx2, mouseX, mouseY, accent);
		int top = cy1 + SEARCH_HEIGHT + 6;
		if (open == null && !favorites && !enabledView && search.get().isBlank()) top = renderChips(context, cx1 + 2, top, cx2, mouseX, mouseY, accent);
		int bottom = y2 - FOOTER - 6;
		viewHeight = bottom - top;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewHeight)));
		smooth += (scroll - smooth) * (1 - (float) Math.exp(-dt * 16));
		if (Math.abs(scroll - smooth) < 0.3f) smooth = scroll;
		int drawn = Math.round(smooth);
		int first = rows.size();
		contentOrigin = top - drawn;
		context.enableScissor(cx1 - 4, top, x2 - 4, bottom);
		int end;
		if (open != null) {
			// A settings page slides in from the right
			float slide = 16 * (1 - easeOut(seconds(settingsAt) / 0.28f));
			context.pose().pushMatrix();
			context.pose().translate(slide, 0);
			end = renderSettingsPage(context, open, cx1 + 2, top - drawn, cx2, mouseX, mouseY, accent);
			context.pose().popMatrix();
		} else end = renderCards(context, cx1 + 2, top - drawn, cx2, mouseX, mouseY, accent);
		renderRipples(context, accent, true);
		context.disableScissor();
		contentHeight = end + drawn - top;
		// Rows scrolled out of view can't be clicked
		for (int i = rows.size() - 1; i >= first; i--) {
			Row r = rows.get(i);
			if (r.y + r.height <= top || r.y >= bottom) rows.remove(i);
		}
		if (contentHeight > viewHeight) {
			int barH = Math.max(14, viewHeight * viewHeight / contentHeight);
			int barY = top + (int) ((long) (viewHeight - barH) * drawn / Math.max(1, contentHeight - viewHeight));
			// Thickens and brightens under the mouse
			boolean barHover = mouseX >= x2 - 16 && mouseX < x2 - 2 && mouseY >= top && mouseY < bottom;
			float bh = ease("bar", "hover", barHover ? 1 : 0, 14);
			int bw = 2 + Math.round(2 * bh);
			Glass.live(context, x2 - 6 - bw, barY, x2 - 6, barY + barH, 1, ColorUtil.withAlpha(accent, 0xA0 + Math.round(0x50 * bh)));
		}

		// The footer: how to drive it
		String hint = open != null ? "Esc goes back  ·  Right click a setting resets it  ·  Ctrl+Z undoes  ·  Middle click binds"
			: "Click toggles  ·  Right click or Enter: settings  ·  Ctrl+Z undoes  ·  Tab moves  ·  Middle click binds";
		float flashT = flash == null ? 1 : seconds(flashAt) / 2.6f;
		if (flashT < 1) {
			// A note slides in over the hint, then fades back to it
			float in = easeOut(flashT * 10), out = flashT > 0.8f ? (1 - flashT) / 0.2f : 1;
			context.pose().pushMatrix();
			context.pose().translate(0, 4 * (1 - in));
			text(context, ellipsize(flash, w - 2 * PAD), x1 + PAD, y2 - FOOTER + 4, fade(accent | 0xFF000000, in * out));
			context.pose().popMatrix();
			text(context, ellipsize(hint, w - 2 * PAD), x1 + PAD, y2 - FOOTER + 4, fade(Theme.muted(), 1 - in * out));
		} else {
			flash = null;
			context.drawString(font, ellipsize(hint, w - 2 * PAD), x1 + PAD, y2 - FOOTER + 4, Theme.muted());
		}
		renderRipples(context, accent, false);
		renderTooltip(context, mouseX, mouseY);
	}

	private void renderSidebar(GuiGraphics context, int x1, int y1, int bottom, int mouseX, int mouseY, int accent) {
		int x = x1 + PAD, y = y1 + PAD + 2;
		// The logo: a little star in the accent, the name, and the version
		drawIcon(context, "logo", x - 1, y + 1, 12, accent);
		context.drawString(font, ellipsize(dev.femboyware.module.theme.BrandingModule.name(), SIDEBAR - 2 * PAD - 12), x + 12, y, Theme.text());
		if (dev.femboyware.module.theme.BrandingModule.showVersion()) context.drawString(font, "Version " + FemboyWare.VERSION, x + 12, y + 10, ColorUtil.withAlpha(accent, 0xFF));
		y += 24;
		// The selected section's highlight, gliding to wherever you click, its bar stretching as it goes
		navGlide.step(dt, 16);
		if (navGlide.visible()) {
			float a = navGlide.alpha, stretch = Math.min(9, Math.abs(navGlide.speed) * 0.025f);
			int gw = Math.round(navGlide.w), gh = Math.round(navGlide.h);
			context.pose().pushMatrix();
			context.pose().translate(navGlide.x, navGlide.y);
			Glass.live(context, 0, 0, gw, gh, 5, ColorUtil.withAlpha(accent, Math.round(0x48 * a)));
			// The bar trails behind the way it's going
			float lead = navGlide.speed > 0 ? -stretch : 0;
			context.pose().translate(0, 3 + lead);
			Glass.live(context, 0, 0, 2, Math.max(2, Math.round(gh - 6 + stretch)), 1, fade(accent, a));
			context.pose().popMatrix();
		}
		y = sectionHeader(context, "FEATURES", x, y);
		y = navItem(context, RowType.FAVORITES, null, "star", "Favorites", favorites && open == null, x1, y, mouseX, mouseY, accent);
		y = navItem(context, RowType.ENABLED, null, "enabled", "Enabled (" + enabledModules().size() + ")", enabledView && open == null, x1, y, mouseX, mouseY, accent);
		// With Larp Only hiding cheats, sections left with nothing in them go too
		if (tabs(group).isEmpty() && !favorites && !enabledView) group = Category.Group.VISUAL;
		for (Category.Group g : FEATURES) if (!tabs(g).isEmpty()) y = navItem(context, RowType.NAV, g, icon(g), g.getDisplayName(), !favorites && !enabledView && group == g, x1, y, mouseX, mouseY, accent);
		y += 6;
		y = sectionHeader(context, "OTHERS", x, y);
		for (Category.Group g : OTHERS) y = navItem(context, RowType.NAV, g, icon(g), g == Category.Group.HUD ? "HUD Elements" : g.getDisplayName(), !favorites && !enabledView && group == g, x1, y, mouseX, mouseY, accent);
		y = navItem(context, RowType.HUD_EDIT, null, "hud_editor", "HUD Editor", false, x1, y, mouseX, mouseY, accent);
		// The ClickGUI's own settings: open and close animations and how long they take
		y = navItem(context, RowType.GUI_SETTINGS, null, "customize", "Animations", open instanceof dev.femboyware.module.client.ClickGuiModule, x1, y, mouseX, mouseY, accent);
		y = navItem(context, RowType.CONFIGS, null, "configs", "Configs", open instanceof dev.femboyware.module.client.ConfigsModule, x1, y, mouseX, mouseY, accent);

		// You, at the bottom
		if (mc().player != null && y <= bottom - 20) {
			int fy = bottom - 20;
			//? if >=26.1 {
			/*net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(context, mc().player.getSkin(), x, fy, 16);
			*///?} else {
			net.minecraft.client.gui.components.PlayerFaceRenderer.draw(context, mc().player.getSkin(), x, fy, 16);
			//?}
			context.drawString(font, mc().player.getGameProfile().name(), x + 22, fy, Theme.text());
			Glass.rounded(context, x + 22, fy + 11, x + 26, fy + 15, 2, Theme.online());
			context.drawString(font, "Online", x + 29, fy + 9, Theme.muted());
		}
	}

	private static final Map<String, Identifier> ICONS = new HashMap<>();

	/** One of the menu's icons (white on clear, in textures/gui/icons), tinted {@code color}. */
	private static void drawIcon(GuiGraphics context, String name, int x, int y, int size, int color) {
		Identifier id = ICONS.computeIfAbsent(name, n -> Identifier.fromNamespaceAndPath("femboyware", "textures/gui/icons/" + n + ".png"));
		context.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, size, size, 64, 64, 64, 64, color);
	}

	private static net.minecraft.client.Minecraft mc() {
		return net.minecraft.client.Minecraft.getInstance();
	}

	private int sectionHeader(GuiGraphics context, String text, int x, int y) {
		context.drawString(font, text, x, y, ColorUtil.withAlpha(Theme.muted(), 0x90));
		context.fill(x + font.width(text) + 5, y + 4, x + SIDEBAR - 2 * PAD, y + 5, Theme.divider());
		return y + 12;
	}

	private int navItem(GuiGraphics context, RowType type, Category.Group g, String icon, String label, boolean selected, int x1, int y, int mouseX, int mouseY, int accent) {
		Row row = new Row(type, x1 + 5, y, SIDEBAR - 8, NAV_HEIGHT, null, null, null, 0, g);
		rows.add(row);
		Key key = new Key("nav", g == null ? type : g, type.ordinal());
		float hover = ease(new Key("navHover", key, 0), row.contains(mouseX, mouseY) ? 1 : 0, 14);
		float sel = ease(new Key("navSel", key, 0), selected ? 1 : 0, 12);
		if (selected) navGlide.aim(row.x, y, row.width, NAV_HEIGHT);
		// Hover shows a soft surface, fading in and out, under the glide
		float bg = hover * (1 - sel);
		if (bg > 0.01f) Glass.live(context, row.x, y, row.x + row.width, y + NAV_HEIGHT, 5, fade(Theme.surface(), bg));
		int color = mix(Theme.muted(), Theme.text(), Math.max(sel, hover * 0.7f));
		// Nudged right as it's picked or hovered
		float nudge = 2 * Math.max(sel, hover * 0.6f);
		context.pose().pushMatrix();
		context.pose().translate(nudge, 0);
		drawIcon(context, icon, row.x + 8, y + 2, 9, mix(color, accent, sel));
		context.drawString(font, label, row.x + 22, y + 3, color);
		context.pose().popMatrix();
		return y + NAV_HEIGHT + 1;
	}

	private static String icon(Category.Group g) {
		return switch (g) {
			case PVP -> "pvp";
			case MOVEMENT -> "movement";
			case VISUAL -> "visual";
			case UTILITY -> "utility";
			case CUSTOMIZE -> "customize";
			case HUD -> "hud";
		};
	}

	private void renderSearch(GuiGraphics context, int x1, int y, int x2, int mouseX, int mouseY, int accent) {
		Row box = new Row(RowType.SEARCH, x1, y, x2 - x1, SEARCH_HEIGHT, null, null, search, 0, null);
		rows.add(box);
		float hover = ease("search", "hover", box.contains(mouseX, mouseY) ? 1 : 0, 14);
		float focused = ease("search", "focus", searching ? 1 : 0, 12);
		int bg = mix(mix(Theme.surface(), Theme.surfaceHover(), hover), ColorUtil.withAlpha(accent, 0x30), focused);
		shape(context, x1, y, x2, y + SEARCH_HEIGHT, 7, bg, settled(hover) && settled(focused));
		// Typing in it: an accent line grows out from the middle along the bottom
		if (focused > 0.01f) {
			float half = ((x2 - x1) / 2f - 7) * easeOut(focused);
			float mid = (x1 + x2) / 2f;
			context.pose().pushMatrix();
			context.pose().translate(mid - half, y + SEARCH_HEIGHT - 1);
			context.fill(0, 0, Math.max(1, Math.round(half * 2)), 1, fade(accent, focused));
			context.pose().popMatrix();
		}
		// A little magnifying glass
		drawIcon(context, "search", x1 + 7, y + 5, 8, mix(Theme.muted(), accent, focused));
		if (search.get().isEmpty() && !searching) {
			context.drawString(font, "Search modules...", x1 + 19, y + 5, Theme.muted());
		} else {
			editor(search).render(context, font, x1 + 19, y + 3, x2 - 8, y + SEARCH_HEIGHT - 2, searching, Theme.text(), Theme.textSelection(), accent);
		}
	}

	/** The section's tabs as chips, when it has more than one. Returns the y below them. */
	private int renderChips(GuiGraphics context, int x1, int y, int x2, int mouseX, int mouseY, int accent) {
		List<Category> tabs = tabs(group);
		if (tabs.size() <= 1) return y;
		Category current = currentTab();
		List<Row> chips = new ArrayList<>();
		int x = x1;
		for (Category c : tabs) {
			int w = font.width(c.getDisplayName()) + 16;
			if (x + w > x2) {
				x = x1;
				y += CHIP_HEIGHT + 4;
			}
			chips.add(new Row(RowType.CHIP, x, y, w, CHIP_HEIGHT, c, null, null, 0, null));
			x += w + 4;
		}
		for (Row chip : chips) {
			float hover = ease("chip", chip.category, chip.contains(mouseX, mouseY) ? 1 : 0, 14);
			shape(context, chip.x, chip.y, chip.x + chip.width, chip.y + CHIP_HEIGHT, 6, mix(Theme.surface(), Theme.surfaceHover(), hover), settled(hover));
			if (chip.category == current) chipGlide.aim(chip.x, chip.y, chip.width, CHIP_HEIGHT);
		}
		// The chosen tab's fill glides (and stretches to fit) from the last one you picked
		chipGlide.step(dt, 16);
		if (chipGlide.visible()) {
			context.pose().pushMatrix();
			context.pose().translate(chipGlide.x, chipGlide.y);
			Glass.live(context, 0, 0, Math.round(chipGlide.w), CHIP_HEIGHT, 6, ColorUtil.withAlpha(accent, Math.round(0x90 * chipGlide.alpha)));
			context.pose().popMatrix();
		}
		for (Row chip : chips) {
			float on = ease("chipOn", chip.category, chip.category == current ? 1 : 0, 12);
			context.drawString(font, chip.category.getDisplayName(), chip.x + 8, chip.y + 3, mix(Theme.muted(), Theme.text(), on));
			rows.add(chip);
		}
		return y + CHIP_HEIGHT + 6;
	}

	private Category currentTab() {
		List<Category> tabs = tabs(group);
		Category c = tab.get(group);
		if (c == null || !tabs.contains(c)) {
			c = tabs.isEmpty() ? null : tabs.getFirst();
			tab.put(group, c);
		}
		return c;
	}

	/** What the cards show: search results, favourites, or the section's chosen tab. */
	private List<Module> shown() {
		String query = search.get().trim().toLowerCase();
		if (!query.isEmpty()) return matches(query);
		if (enabledView) return enabledModules();
		if (favorites) {
			List<Module> list = new ArrayList<>();
			for (Module m : FemboyWare.getModules().getModules()) if (module.isFavorite(m) && !LarpOnlyModule.hides(m)) list.add(m);
			list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
			return list;
		}
		Category c = currentTab();
		return c == null ? List.of() : visible(c);
	}

	/** Every module that's on (less any Larp Only is hiding), by name. */
	private static List<Module> enabledModules() {
		List<Module> list = new ArrayList<>();
		for (Module m : FemboyWare.getModules().getModules()) if (m.isEnabled() && !LarpOnlyModule.hides(m)) list.add(m);
		list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		return list;
	}

	private int renderCards(GuiGraphics context, int x1, int y, int x2, int mouseX, int mouseY, int accent) {
		List<Module> list = shown();
		cards.clear();
		cardSpots.clear();
		// A different set of cards cascades in, one after another
		Object key = List.of(group, String.valueOf(tab.get(group)), favorites, enabledView, search.get());
		if (!key.equals(cardsKey)) {
			cardsKey = key;
			cardsAt = System.nanoTime();
		}
		float since = seconds(cardsAt);
		if (list.isEmpty()) {
			cardGlide.step(dt, 16);
			String empty = enabledView ? "Nothing is on" : favorites ? "Star a module to keep it here" : "Nothing matches";
			text(context, empty, x1 + 4, y + 6, fade(Theme.muted(), easeOut(since / 0.25f)));
			return y + 24;
		}
		int cardW = (x2 - x1 - GAP) / 2;
		focus = selected == null ? -1 : list.indexOf(selected);
		if (focus >= 0) cardGlide.aim((focus % 2) * (cardW + GAP), (focus / 2) * (CARD_HEIGHT + GAP), cardW, CARD_HEIGHT);
		cardGlide.step(dt, 16);
		// The picked card: a soft glow under it that breathes, gliding card to card
		float glow = cardGlide.alpha * (0.85f + 0.15f * (float) Math.sin(System.nanoTime() / 1e9 * 3));
		if (cardGlide.visible()) {
			context.pose().pushMatrix();
			context.pose().translate(x1 + cardGlide.x, y + cardGlide.y);
			Glass.live(context, -2, -2, cardW + 2, CARD_HEIGHT + 2, 8, ColorUtil.withAlpha(accent, Math.round(0x3A * glow)));
			context.pose().popMatrix();
		}
		for (int i = 0; i < list.size(); i++) {
			Module m = list.get(i);
			int x = x1 + (i % 2) * (cardW + GAP), cy = y + (i / 2) * (CARD_HEIGHT + GAP);
			cards.add(m);
			cardSpots.add(new int[]{x, cy});
			float in = easeOut((since - Math.min(i, 18) * 0.022f) / 0.24f);
			renderCard(context, m, x, cy, cardW, mouseX, mouseY, accent, in);
		}
		// And its ring on top
		if (cardGlide.visible()) {
			context.pose().pushMatrix();
			context.pose().translate(x1 + cardGlide.x, y + cardGlide.y);
			outline(context, 0, 0, cardW, CARD_HEIGHT, fade(accent, cardGlide.alpha));
			outline(context, -1, -1, cardW + 1, CARD_HEIGHT + 1, ColorUtil.withAlpha(accent, Math.round(0x50 * glow)));
			context.pose().popMatrix();
		}
		return y + ((list.size() + 1) / 2) * (CARD_HEIGHT + GAP);
	}

	/** One module's card; {@code in} is how far it's come in (0 to 1) as the cards cascade. */
	private void renderCard(GuiGraphics context, Module m, int x, int y, int w, int mouseX, int mouseY, int accent, float in) {
		Row card = new Row(RowType.CARD, x, y, w, CARD_HEIGHT, m.getCategory(), m, null, 0, null);
		rows.add(card);
		if (in <= 0.01f) return;
		boolean hover = card.contains(mouseX, mouseY);
		float hoverT = ease("hover", m, hover ? 1 : 0, 14), onT = ease("on", m, m.isEnabled() ? 1 : 0, 12);
		int off = mix(Theme.surface(), Theme.surfaceHover(), hoverT);
		int lit = ColorUtil.withAlpha(accent, 0x44 + Math.round(0x14 * hoverT));
		boolean still = in >= 1 && settled(hoverT) && settled(onT);
		context.pose().pushMatrix();
		context.pose().translate(0, 8 * (1 - in));
		shape(context, x, y, x + w, y + CARD_HEIGHT, 7, fade(mix(off, lit, onT), in), still);
		// On: a thin accent stripe down the left edge, growing from its middle
		if (onT > 0.01f) {
			float half = (CARD_HEIGHT / 2f - 8) * easeOut(onT);
			context.pose().pushMatrix();
			context.pose().translate(x + 3, y + CARD_HEIGHT / 2f - half);
			context.fill(0, 0, 2, Math.max(1, Math.round(half * 2)), fade(accent, in * onT));
			context.pose().popMatrix();
		}
		String name = binding == m ? m.getName() + "  [press a key]" : m.getName();
		text(context, name, x + 10, y + 6, fade(Theme.text(), in));
		int textRight = x + w - 52;
		List<Setting<?>> hits = search.get().isBlank() ? null : settingHits.get(m);
		if (hits != null) {
			// Found by a setting: say which
			StringBuilder found = new StringBuilder("Setting: ");
			for (int i = 0; i < hits.size(); i++) found.append(i == 0 ? "" : ", ").append(hits.get(i).getName());
			text(context, ellipsize(found.toString(), textRight - (x + 10)), x + 10, y + 17, fade(mix(Theme.muted(), accent | 0xFF000000, 0.75f), in));
		} else {
			String description = ellipsize(m.getDescription(), textRight - (x + 10));
			text(context, description, x + 10, y + 17, fade(Theme.muted(), in));
		}

		// The star, then the switch
		int sx = x + w - 46, sy = y + (CARD_HEIGHT - 12) / 2;
		Row star = new Row(RowType.STAR, sx - 2, sy - 2, 14, 16, null, m, null, 0, null);
		rows.add(star);
		drawStar(context, m, sx, sy + 1, star.contains(mouseX, mouseY), accent, in);
		if (!settingsOnly(m)) {
			int tx = x + w - 28, ty = y + (CARD_HEIGHT - 10) / 2;
			rows.add(new Row(RowType.SWITCH, tx - 2, ty - 3, 24, 16, null, m, null, 0, null));
			drawSwitch(context, tx, ty, onT, accent, in);
		}
		context.pose().popMatrix();
	}

	/** A module's favourite star, which pops when you click it and brightens under the mouse. */
	private void drawStar(GuiGraphics context, Module m, int x, int y, boolean hover, int accent, float in) {
		boolean fav = module.isFavorite(m);
		float h = ease("star", m, hover ? 1 : 0, 16), f = ease("fav", m, fav ? 1 : 0, 12);
		int color = mix(mix(Theme.muted(), Theme.text(), h), accent, f);
		Long popped = starPops.get(m);
		float scale = 1;
		if (popped != null) {
			float t = seconds(popped) / 0.38f;
			if (t >= 1) starPops.remove(m);
			else scale = 1 + 0.5f * (float) Math.sin(Math.PI * t) * (1 - t * 0.4f);
		}
		scale += 0.12f * h;
		context.pose().pushMatrix();
		context.pose().translate(x + 5, y + 5);
		context.pose().scale(scale, scale);
		drawIcon(context, fav ? "star" : "star_outline", -5, -5, 10, fade(color, in));
		context.pose().popMatrix();
	}

	/**
	 * A switch, {@code t} of the way on (0 off, 1 on, in between while it slides): the track's colour
	 * blends across and the knob slides, stretching a little in the middle of its travel.
	 */
	private static void drawSwitch(GuiGraphics context, int x, int y, float t, int accent, float in) {
		boolean still = in >= 1 && settled(t);
		shape(context, x, y, x + 20, y + 10, 5, fade(mix(Theme.switchOff(), accent, t), in), still);
		float stretch = 3 * (float) Math.sin(Math.PI * t);
		context.pose().pushMatrix();
		context.pose().translate(x + 1 + (10 - stretch) * t, y + 1);
		shape(context, 0, 0, Math.round(8 + stretch), 8, 4, fade(Theme.knob(), in), still);
		context.pose().popMatrix();
	}

	private static boolean settled(float t) {
		return t <= 0 || t >= 1;
	}

	/**
	 * A rounded shape: from the cache while it's {@code still}, so it keeps its smooth corners, and
	 * straight from fills while it animates, so a changing colour doesn't bake a texture a frame.
	 */
	private static void shape(GuiGraphics context, int x1, int y1, int x2, int y2, int radius, int color, boolean still) {
		if (still) Glass.rounded(context, x1, y1, x2, y2, radius, color);
		else Glass.live(context, x1, y1, x2, y2, radius, color);
	}

	private static void outline(GuiGraphics context, int x1, int y1, int x2, int y2, int color) {
		context.fill(x1 + 3, y1, x2 - 3, y1 + 1, color);
		context.fill(x1 + 3, y2 - 1, x2 - 3, y2, color);
		context.fill(x1, y1 + 3, x1 + 1, y2 - 3, color);
		context.fill(x2 - 1, y1 + 3, x2, y2 - 3, color);
	}

	private String ellipsize(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		String dots = "...";
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) + font.width(dots) > maxWidth) end--;
		return text.substring(0, end).stripTrailing() + dots;
	}

	/**
	 * What a search finds, best first: names starting with it, then containing it, then initials
	 * ("ka" for Kill Aura), then modules with a setting called that (noted in {@link #settingHits}),
	 * then descriptions, then names with its letters in order ("klar" for Kill Aura).
	 */
	private List<Module> matches(String query) {
		settingHits.clear();
		String squashed = query.replace(" ", "");
		List<List<Module>> tiers = new ArrayList<>();
		for (int i = 0; i < 6; i++) tiers.add(new ArrayList<>());
		for (Module m : FemboyWare.getModules().getModules()) {
			if (LarpOnlyModule.hides(m)) continue;
			String name = m.getName().toLowerCase();
			if (name.startsWith(query)) tiers.get(0).add(m);
			else if (name.contains(query)) tiers.get(1).add(m);
			else if (squashed.length() >= 2 && initials(name).startsWith(squashed)) tiers.get(2).add(m);
			else {
				List<Setting<?>> hits = new ArrayList<>();
				if (query.length() >= 2) {
					for (Setting<?> setting : m.getSettings()) {
						if (setting.getDescription() == null || setting.getDescription().isEmpty()) continue;
						if (setting.getName().toLowerCase().contains(query)) hits.add(setting);
					}
				}
				if (!hits.isEmpty()) {
					settingHits.put(m, hits);
					tiers.get(3).add(m);
				} else if (m.getDescription().toLowerCase().contains(query)) tiers.get(4).add(m);
				else if (squashed.length() >= 3 && inOrder(squashed, name.replace(" ", ""))) tiers.get(5).add(m);
			}
		}
		List<Module> out = new ArrayList<>();
		for (List<Module> tier : tiers) {
			tier.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
			out.addAll(tier);
		}
		return out;
	}

	private static String initials(String name) {
		StringBuilder out = new StringBuilder();
		for (String word : name.split("[ \\-]+")) if (!word.isEmpty()) out.append(word.charAt(0));
		return out.toString();
	}

	/** Whether every letter of {@code query} turns up in {@code text}, in order. */
	private static boolean inOrder(String query, String text) {
		int at = 0;
		for (int i = 0; i < query.length(); i++) {
			at = text.indexOf(query.charAt(i), at);
			if (at < 0) return false;
			at++;
		}
		return true;
	}

	/** Tabs of {@code group} that have at least one module; empty tabs are hidden. */
	private static List<Category> tabs(Category.Group group) {
		List<Category> tabs = new ArrayList<>();
		for (Category category : Category.values()) {
			if (category.getGroup() == group && !visible(category).isEmpty()) tabs.add(category);
		}
		return tabs;
	}

	/** A tab's modules, less any Larp Only is hiding. */
	private static List<Module> visible(Category category) {
		List<Module> list = FemboyWare.getModules().getModules(category);
		return list.stream().anyMatch(LarpOnlyModule::hides) ? list.stream().filter(m -> !LarpOnlyModule.hides(m)).toList() : list;
	}

	// ---------------------------------------------------------------- a module's settings

	private int renderSettingsPage(GuiGraphics context, Module m, int x1, int y, int x2, int mouseX, int mouseY, int accent) {
		int w = x2 - x1;
		// Back, the name with its switch, and what it does
		Row back = new Row(RowType.BACK, x1, y, font.width("Back") + 20, 14, null, null, null, 0, null);
		rows.add(back);
		float backHover = ease("back", "hover", back.contains(mouseX, mouseY) ? 1 : 0, 14);
		shape(context, back.x, y, back.x + back.width, y + 14, 6, mix(Theme.surface(), Theme.surfaceHover(), backHover), settled(backHover));
		// The arrow leans back the way it goes
		context.pose().pushMatrix();
		context.pose().translate(-1.5f * backHover, 0);
		drawIcon(context, "back", back.x + 5, y + 3, 8, Theme.text());
		context.pose().popMatrix();
		context.drawString(font, "Back", back.x + 14, y + 3, Theme.text());
		context.drawString(font, m.getName(), back.x + back.width + 8, y + 3, Theme.text());
		int sx = x2 - 46;
		Row star = new Row(RowType.STAR, sx - 2, y - 1, 14, 16, null, m, null, 0, null);
		rows.add(star);
		drawStar(context, m, sx, y + 2, star.contains(mouseX, mouseY), accent, 1);
		if (!(m instanceof dev.femboyware.module.client.ConfigsModule)) {
			// Every setting back to how it came (Ctrl+Z brings yours back)
			int rw = font.width("Reset") + 12, rx = sx - 8 - rw;
			Row reset = new Row(RowType.RESET_ALL, rx, y, rw, 14, null, m, null, 0, null);
			rows.add(reset);
			float rh = ease("reset", m, reset.contains(mouseX, mouseY) ? 1 : 0, 14);
			shape(context, rx, y, rx + rw, y + 14, 6, mix(Theme.surface(), ColorUtil.withAlpha(accent, 0x50), rh), settled(rh));
			context.drawString(font, "Reset", rx + 6, y + 3, mix(Theme.muted(), Theme.text(), rh));
		}
		if (!settingsOnly(m)) {
			rows.add(new Row(RowType.HEADER_SWITCH, x2 - 30, y - 1, 24, 16, null, m, null, 0, null));
			drawSwitch(context, x2 - 28, y + 2, ease("on", m, m.isEnabled() ? 1 : 0, 12), accent, 1);
		}
		y += 18;
		for (String line : wrap(m.getDescription(), w - 4)) {
			context.drawString(font, line, x1 + 2, y, Theme.muted());
			y += 10;
		}
		y += 4;
		return renderSettings(context, m, x1, y, w, mouseX, mouseY, accent);
	}

	private List<String> wrap(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (font.width(next) > maxWidth && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		return lines;
	}

	private int renderSettings(GuiGraphics context, Module m, int x, int y, int w, int mouseX, int mouseY, int accent) {
		Font font = this.font;
		int left = x + 8;
		int right = x + w - 6;

		Glass.rounded(context, x, y, x + w, y + ROW_HEIGHT + 2, 4, Theme.settingRow());
		Row bind = new Row(RowType.BIND, x, y, w, ROW_HEIGHT + 2, null, m, null, 0, null);
		hoverRow(context, bind, new Key("bind", m, 0), mouseX, mouseY);
		String bindText = binding == m ? "Bind: press a key..." : "Bind: " + keyName(m.getKey());
		context.drawString(font, bindText, left, y + 4, Theme.muted());
		rows.add(bind);
		y += ROW_HEIGHT + 5;

		for (Setting<?> setting : m.getSettings()) {
			if (!setting.isVisible()) continue;

			if (setting instanceof ColorSetting color) {
				context.fill(x, y, x + w, y + ROW_HEIGHT, Theme.settingRow());
				context.drawString(font, color.getName(), left, y + 3, Theme.text());
				Glass.rounded(context, right - 14, y + 2, right, y + ROW_HEIGHT - 2, 2, color.get() | 0xFF000000);
				rows.add(new Row(RowType.COLOR, x, y, w, ROW_HEIGHT, null, m, color, -1, null));
				if (color.equals(selectedSetting)) settingGlide.aim(x, y - contentOrigin, w, ROW_HEIGHT * 5);
				if (color == scrollToSetting) {
					scroll = Math.max(0, y - contentOrigin - 30);
					scrollToSetting = null;
				}
				y += ROW_HEIGHT;
				for (int i = 0; i < 4; i++) {
					int value = color.getChannel(CHANNEL_SHIFTS[i]);
					drawSlider(context, x, y, w, CHANNEL_NAMES[i], String.valueOf(value), value / 255.0, accent, 4, new Key("slider", color, CHANNEL_SHIFTS[i]), mouseX, mouseY);
					rows.add(new Row(RowType.COLOR, x, y, w, ROW_HEIGHT, null, m, color, CHANNEL_SHIFTS[i], null));
					y += ROW_HEIGHT;
				}
				y += 2;
				continue;
			}

			Row row = switch (setting) {
				case BoolSetting bool -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT, Theme.settingRow());
					context.drawString(font, bool.getName(), left, y + 3, Theme.text());
					drawSwitch(context, right - 20, y + 2, ease("switch", setting, bool.get() ? 1 : 0, 14), accent, 1);
					yield new Row(RowType.BOOL, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				case NumberSetting number -> {
					double fraction = (number.get() - number.getMin()) / (number.getMax() - number.getMin());
					String value = number.isInteger() ? String.valueOf(number.getInt()) : trim(number.get());
					drawSlider(context, x, y, w, number.getName(), value, fraction, accent, 0, new Key("slider", setting, 0), mouseX, mouseY);
					yield new Row(RowType.NUMBER, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				case ButtonSetting button -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT, Theme.settingRow());
					boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT;
					float press = ease("button", setting, over ? 1 : 0, 14);
					shape(context, left - 2, y + 1, right, y + ROW_HEIGHT - 1, 3, mix(Theme.surface(), ColorUtil.withAlpha(accent, 0x40), press), settled(press));
					context.drawString(font, button.getName(), (left + right - font.width(button.getName())) / 2, y + 3, mix(accent, Theme.text(), press));
					yield new Row(RowType.BUTTON, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				case Choice mode -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT, Theme.settingRow());
					context.drawString(font, mode.getName(), left, y + 3, Theme.text());
					String value = mode.getDisplayName() + (openEnum == mode ? " ▴" : " ▾");
					context.drawString(font, value, right - font.width(value), y + 3, accent);
					yield new Row(RowType.ENUM, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				case StringSetting text -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT * 2, Theme.settingRow());
					context.drawString(font, text.getName(), left, y + 3, Theme.text());
					boolean focused = typing == text;
					Glass.rounded(context, left - 2, y + ROW_HEIGHT, right, y + ROW_HEIGHT * 2 - 1, 3, focused ? Theme.surfaceHover() : Theme.content());
					editor(text).render(context, font, left, y + ROW_HEIGHT, right, y + ROW_HEIGHT * 2 - 1, focused,
						focused ? Theme.text() : Theme.muted(), Theme.textSelection(), accent);
					yield new Row(RowType.STRING, x, y, w, ROW_HEIGHT * 2, null, m, setting, 0, null);
				}
				case KeySetting key -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT, Theme.settingRow());
					context.drawString(font, key.getName(), left, y + 3, Theme.text());
					String value = bindingKey == key ? "..." : keyName(key.get());
					context.drawString(font, value, right - font.width(value), y + 3, accent);
					yield new Row(RowType.KEY, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				default -> null;
			};
			if (row == null) continue;
			hoverRow(context, row, new Key("row", setting, 0), mouseX, mouseY);
			if (setting.equals(selectedSetting)) settingGlide.aim(row.x, row.y - contentOrigin, row.width, row.height);
			if (setting == scrollToSetting) {
				scroll = Math.max(0, row.y - contentOrigin - 30);
				scrollToSetting = null;
			}
			rows.add(row);
			y += row.height;
			if (setting instanceof Choice mode && openEnum == mode) {
				java.util.List<String> options = mode.optionNames();
				int chosen = mode.selectedIndex(), n = options.size();
				context.fill(x, y, x + w, y + ROW_HEIGHT * n, Theme.settingRow());
				// A highlight that follows the mouse down the list, resting on the chosen one
				boolean inList = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT * n;
				int target = inList ? Math.min(n - 1, (mouseY - y) / ROW_HEIGHT) : chosen;
				optionGlide.aim(x, y + target * ROW_HEIGHT - contentOrigin, w, ROW_HEIGHT);
				optionGlide.step(dt, 22);
				if (optionGlide.visible()) {
					context.pose().pushMatrix();
					context.pose().translate(optionGlide.x, optionGlide.y + contentOrigin);
					Glass.live(context, 2, 1, Math.round(optionGlide.w) - 2, ROW_HEIGHT - 1, 3, ColorUtil.withAlpha(accent, Math.round(0x2C * optionGlide.alpha)));
					context.pose().popMatrix();
				}
				float since = seconds(enumAt);
				for (int i = 0; i < n; i++) {
					boolean current = i == chosen;
					// Each option slides and fades in just after the one above
					float in = easeOut((since - i * 0.018f) / 0.18f);
					context.fill(left - 2, y, left - 1, y + ROW_HEIGHT, fade(current ? accent : Theme.divider(), in));
					context.pose().pushMatrix();
					context.pose().translate(6 * (1 - in), 0);
					text(context, options.get(i), left + 4, y + 3, fade(current ? accent : mix(Theme.muted(), Theme.text(), ease("option", new Key("o", mode, i), i == target && inList ? 1 : 0, 16)), in));
					context.pose().popMatrix();
					rows.add(new Row(RowType.ENUM_OPTION, x, y, w, ROW_HEIGHT, null, m, setting, i, null));
					y += ROW_HEIGHT;
				}
			}
		}
		// The setting you last clicked: tinted, with an accent bar, gliding from the one before
		settingGlide.step(dt, 16);
		if (settingGlide.visible()) {
			float a = settingGlide.alpha, stretch = Math.min(8, Math.abs(settingGlide.speed) * 0.02f);
			context.pose().pushMatrix();
			context.pose().translate(settingGlide.x, settingGlide.y + contentOrigin);
			int gw = Math.round(settingGlide.w), gh = Math.round(settingGlide.h);
			context.fill(0, 0, gw, gh, ColorUtil.withAlpha(accent, Math.round(0x1C * a)));
			context.pose().translate(0, settingGlide.speed > 0 ? -stretch : 0);
			context.fill(0, 0, 2, Math.max(2, Math.round(gh + stretch)), fade(accent, a));
			context.pose().popMatrix();
		}
		return y + 6;
	}

	private void drawSlider(GuiGraphics context, int x, int y, int w, String name, String value, double fraction, int accent, int indent, Key key, int mouseX, int mouseY) {
		int left = x + 8 + indent;
		int right = x + w - 6;
		context.fill(x, y, x + w, y + ROW_HEIGHT, Theme.settingRow());
		// The fill eases to the value (quick while you drag it), and a knob shows at its end under the mouse
		boolean dragging = slider != null && slider.setting != null && new Key("slider", slider.setting, slider.type == RowType.COLOR ? slider.shift : 0).equals(key);
		float shown = ease(key, (float) Math.max(0, Math.min(1, fraction)), dragging ? 40 : 14);
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT;
		float knob = ease(new Key("knob", key, 0), over || dragging ? 1 : 0, 14);
		float end = left + (right - left) * shown;
		Glass.rounded(context, left, y + ROW_HEIGHT - 3, right, y + ROW_HEIGHT - 1, 1, Theme.track());
		if (end > left + 0.5f) {
			context.pose().pushMatrix();
			context.pose().translate(left, y + ROW_HEIGHT - 3);
			Glass.live(context, 0, 0, Math.max(1, Math.round(end - left)), 2, 1, accent);
			context.pose().popMatrix();
		}
		if (knob > 0.01f) {
			float size = 2 + 3 * easeOut(knob);
			context.pose().pushMatrix();
			context.pose().translate(end, y + ROW_HEIGHT - 2);
			context.pose().scale(size / 6f, size / 6f);
			Glass.live(context, -3, -3, 3, 3, 3, fade(Theme.knob(), knob));
			context.pose().popMatrix();
		}
		context.drawString(font, name, left, y + 1, Theme.text());
		context.drawString(font, value, right - font.width(value), y + 1, Theme.muted());
	}

	/** A settings row's hover tint, fading in and out. */
	private void hoverRow(GuiGraphics context, Row row, Key key, int mouseX, int mouseY) {
		float t = ease(new Key("rowHover", key, 0), row.contains(mouseX, mouseY) ? 1 : 0, 16);
		if (t > 0.01f) context.fill(row.x, row.y, row.x + row.width, row.y + row.height, fade(Theme.surface(), t));
	}

	private void renderTooltip(GuiGraphics context, int mouseX, int mouseY) {
		if (slider != null) return;
		for (int i = rows.size() - 1; i >= 0; i--) {
			Row row = rows.get(i);
			if (!row.contains(mouseX, mouseY)) continue;
			String description = row.setting != null && row.type != RowType.SEARCH ? row.setting.getDescription()
				: row.type == RowType.BIND ? "Click, then press a key. Escape or Delete unbinds"
				: row.type == RowType.STAR ? (module.isFavorite(row.module) ? "Unfavourite" : "Favourite")
				: row.type == RowType.CARD && font.width(row.module.getDescription()) > row.width - 62 ? row.module.getDescription() : null;
			if (description == null || description.isEmpty()) break;
			if (!description.equals(tooltipText)) {
				tooltipText = description;
				tooltipAt = System.nanoTime();
			}
			int textWidth = font.width(description);
			int x = Math.min(mouseX + 8, canvasWidth() - textWidth - 6);
			int y = mouseY + 10;
			context.pose().pushMatrix();
			context.pose().translate(0, 5 * (1 - easeOut(seconds(tooltipAt) / 0.16f)));
			Glass.draw(context, x - 4, y - 3, x + textWidth + 4, y + font.lineHeight + 2, Math.min(4, Theme.roundness()), Theme.tooltip(), true);
			context.drawString(font, description, x, y, Theme.text());
			context.pose().popMatrix();
			return;
		}
		tooltipText = null;
	}

	// ---------------------------------------------------------------- input

	private void openSettings(Module m) {
		if (open != m) settingsAt = System.nanoTime();
		open = m;
		openEnum = null;
		selectedSetting = null;
		settingGlide.alpha = 0;
		scrollToSetting = null;
		List<Setting<?>> hits = search.get().isBlank() ? null : settingHits.get(m);
		if (hits != null && !hits.isEmpty()) {
			selectedSetting = hits.getFirst();
			scrollToSetting = hits.getFirst();
		}
		// Coming back, the cards cascade in again
		cardsKey = null;
		scroll = 0;
		smooth = 0;
	}

	/** The card at {@code i} picked with the keys: highlighted, and kept in view. */
	private void focusCard(int i) {
		focus = i;
		selected = i >= 0 && i < cards.size() ? cards.get(i) : null;
		scrollToFocus();
	}

	private void showGroup(Category.Group g, boolean favs) {
		showGroup(g, favs, false);
	}

	private void showGroup(Category.Group g, boolean favs, boolean enabled) {
		// A new section starts with an empty search
		clearSearch();
		searching = false;
		group = g == null ? group : g;
		favorites = favs;
		enabledView = enabled;
		open = null;
		scroll = 0;
		smooth = 0;
		focus = -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (closeAt != 0) return true;
		click = onPanel(click);
		double mouseX = click.x() / UiScale.scale();
		double mouseY = click.y() / UiScale.scale();
		int button = click.button();
		StringSetting wasTyping = typing;
		boolean wasSearching = searching;
		typing = null;
		searching = false;

		if (binding != null || bindingKey != null) {
			binding = null;
			bindingKey = null;
			return true;
		}

		for (int i = rows.size() - 1; i >= 0; i--) {
			Row row = rows.get(i);
			if (!row.contains(mouseX, mouseY)) continue;
			boolean left = button == GLFW.GLFW_MOUSE_BUTTON_LEFT, right = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
			switch (row.type) {
				case NAV, FAVORITES, ENABLED, HUD_EDIT, GUI_SETTINGS, CONFIGS -> ripple(row, mouseX, mouseY, 5, false);
				case RESET_ALL -> ripple(row, mouseX, mouseY, 6, true);
				case CHIP -> ripple(row, mouseX, mouseY, 6, false);
				case CARD -> ripple(row, mouseX, mouseY, 7, true);
				case BACK -> ripple(row, mouseX, mouseY, 6, true);
				case BIND, KEY, BUTTON, BOOL, ENUM, ENUM_OPTION -> ripple(row, mouseX, mouseY, 3, true);
				default -> {
				}
			}
			// What you click stays highlighted, the highlight gliding over from the last thing
			switch (row.type) {
				case CARD, STAR, SWITCH -> {
					if (open == null) selected = row.module;
				}
				case BIND -> selectedSetting = new Key("bind", row.module, 0);
				case KEY, BUTTON, BOOL, ENUM, ENUM_OPTION, STRING, NUMBER, COLOR -> selectedSetting = row.setting;
				default -> {
				}
			}
			if (row.type == RowType.STAR) starPops.put(row.module, System.nanoTime());
			// Changes the menu can take back with Ctrl+Z
			boolean changes = switch (row.type) {
				case CARD -> !right && !settingsOnly(row.module);
				case SWITCH, HEADER_SWITCH, BIND, KEY, BOOL, ENUM_OPTION -> true;
				case ENUM -> right;
				case STRING -> right || wasTyping != row.setting;
				case NUMBER, COLOR -> right || (left && row.shift != -1);
				default -> false;
			};
			if (changes) remember(row.module);
			switch (row.type) {
				case NAV -> showGroup(row.group, false);
				case FAVORITES -> showGroup(null, true);
				case ENABLED -> showGroup(null, false, true);
				case HUD_EDIT -> hudEditing = true;
				case GUI_SETTINGS -> openSettings(dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.ClickGuiModule.class));
				case CONFIGS -> openSettings(dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.ConfigsModule.class));
				case RESET_ALL -> resetAll(row.module);
				case HUD_DONE -> hudEditing = false;
				case CHIP -> {
					clearSearch();
					searching = false;
					tab.put(group, row.category);
					scroll = 0;
					smooth = 0;
					focus = -1;
				}
				case CARD -> {
					if (left && settingsOnly(row.module)) openSettings(row.module);
					else if (left) row.module.toggle();
					else if (right) openSettings(row.module);
					else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) binding = row.module;
				}
				case STAR -> module.toggleFavorite(row.module);
				case SWITCH, HEADER_SWITCH -> row.module.toggle();
				case BACK -> {
					open = null;
					cardsKey = null;
					scroll = 0;
					smooth = 0;
				}
				case BIND -> binding = row.module;
				case KEY -> {
					if (right) row.setting.reset();
					else bindingKey = (KeySetting) row.setting;
				}
				case BUTTON -> {
					if (left) ((ButtonSetting) row.setting).press();
				}
				case BOOL -> {
					if (right) row.setting.reset();
					else ((BoolSetting) row.setting).toggle();
				}
				case ENUM -> {
					Choice mode = (Choice) row.setting;
					if (right) {
						mode.reset();
						openEnum = null;
					} else {
						openEnum = openEnum == mode ? null : mode;
						enumAt = System.nanoTime();
						optionGlide.alpha = 0;
					}
				}
				case ENUM_OPTION -> {
					((Choice) row.setting).setIndex(row.shift);
					openEnum = null;
				}
				case STRING -> {
					if (right) {
						row.setting.reset();
						editors.remove(row.setting);
						return true;
					}
					typing = (StringSetting) row.setting;
					typingRowX = row.x;
					TextEditor editor = editor(typing);
					editor.click(font, mouseX, row.x + 8, click.hasShiftDown() && wasTyping == typing);
					if (doubled) editor.selectWord();
					selectingText = left;
				}
				case NUMBER, COLOR -> {
					if (right) row.setting.reset();
					else if (left && row.shift != -1) {
						slider = row;
						updateSlider(mouseX);
					}
				}
				case SEARCH -> {
					if (right) clearSearch();
					searching = true;
					editor(search).click(font, mouseX, row.x + 19, click.hasShiftDown() && wasSearching);
					if (doubled) editor(search).selectWord();
				}
			}
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hudEditing) {
			List<HudElement> elements = hudElements();
			for (int i = elements.size() - 1; i >= 0; i--) {
				HudElement element = elements.get(i);
				int left = element.left(width, true), top = element.top(height, true);
				int right = left + element.width(true), bottom = top + element.height(true);
				if (element.resizable() && Math.abs(click.x() - right) <= RESIZE_HANDLE && Math.abs(click.y() - bottom) <= RESIZE_HANDLE) {
					resizingElement = element;
					return true;
				}
				if (click.x() < left || click.x() >= right || click.y() < top || click.y() >= bottom) continue;
				draggingElement = element;
				elementOffsetX = (int) click.x() - left;
				elementOffsetY = (int) click.y() - top;
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
		click = onPanel(click);
		if (selectingText && typing != null) {
			editor(typing).drag(font, click.x() / UiScale.scale(), typingRowX + 8);
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		click = onPanel(click);
		draggingElement = null;
		resizingElement = null;
		slider = null;
		selectingText = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double rawX, double rawY, double horizontalAmount, double verticalAmount) {
		if (WorldGui.showing(this)) {
			double[] point = WorldGui.toGui(rawX, rawY);
			rawX = point[0];
			rawY = point[1];
		}
		double mouseX = rawX / UiScale.scale();
		double mouseY = rawY / UiScale.scale();
		for (Row row : rows) {
			if (row.type != RowType.STRING || mouseX < row.x + 8 || mouseX >= row.x + row.width - 6
				|| mouseY < row.y + ROW_HEIGHT || mouseY >= row.y + ROW_HEIGHT * 2) continue;
			int pixels = (int) ((horizontalAmount - verticalAmount) * 12);
			editor((StringSetting) row.setting).scrollBy(font, pixels, row.width - 14 - 4);
			return true;
		}
		scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewHeight), scroll - (int) (verticalAmount * 22)));
		return true;
	}

	private void updateSlider(double mouseX) {
		Row row = slider;
		int indent = row.type == RowType.COLOR ? 4 : 0;
		int left = row.x + 8 + indent;
		int right = row.x + row.width - 6;
		double fraction = Math.max(0, Math.min(1, (mouseX - left) / (right - left)));
		if (row.setting instanceof NumberSetting number) {
			number.set(number.getMin() + fraction * (number.getMax() - number.getMin()));
		} else if (row.setting instanceof ColorSetting color) {
			color.setChannel(row.shift, (int) Math.round(fraction * 255));
		}
	}

	/** Keeps the focused card in view. */
	private void scrollToFocus() {
		if (focus < 0 || focus >= cardSpots.size() || cardSpots.isEmpty()) return;
		int cardTop = cardSpots.get(focus)[1] - cardSpots.get(0)[1];
		if (cardTop < scroll) scroll = cardTop;
		else if (cardTop + CARD_HEIGHT > scroll + viewHeight) scroll = cardTop + CARD_HEIGHT - viewHeight;
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (closeAt != 0) return true;
		int key = input.key();
		boolean unbind = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE;
		if (binding != null) {
			binding.setKey(unbind ? -1 : key);
			binding = null;
			return true;
		}
		if (bindingKey != null) {
			bindingKey.set(unbind ? -1 : key);
			bindingKey = null;
			return true;
		}
		if (typing != null) {
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) typing = null;
			else editor(typing).keyPressed(input);
			return true;
		}
		if (hudEditing && key == GLFW.GLFW_KEY_ESCAPE) {
			hudEditing = false;
			return true;
		}
		if (searching) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				if (search.get().isEmpty()) searching = false;
				else clearSearch();
				return true;
			}
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_TAB) {
				searching = false;
				if (key == GLFW.GLFW_KEY_TAB) focusCard(0);
				return true;
			}
			editor(search).keyPressed(input);
			scroll = 0;
			smooth = 0;
			return true;
		}
		if (input.hasControlDown() && (key == 90 || key == 89)) {
			undo(key == 89 || input.hasShiftDown()); // Z, Y
			return true;
		}
		if (key == GLFW.GLFW_KEY_F && input.hasControlDown()) {
			searching = true;
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && open != null) {
			open = null;
			cardsKey = null;
			scroll = 0;
			smooth = 0;
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && !search.get().isEmpty()) {
			clearSearch();
			return true;
		}
		// In the world your keys walk you around, so they don't move the card focus
		if (open == null && !cards.isEmpty() && !WorldGui.showing(this)) {
			int n = cards.size();
			switch (key) {
				case GLFW.GLFW_KEY_TAB -> {
					focusCard(focus < 0 ? 0 : (focus + (input.hasShiftDown() ? n - 1 : 1)) % n);
					return true;
				}
				case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
					int step = key == GLFW.GLFW_KEY_RIGHT ? 1 : key == GLFW.GLFW_KEY_LEFT ? -1 : key == GLFW.GLFW_KEY_DOWN ? 2 : -2;
					focusCard(focus < 0 ? 0 : Math.max(0, Math.min(n - 1, focus + step)));
					return true;
				}
				case 32 -> { // space
					if (focus >= 0 && focus < n && !settingsOnly(cards.get(focus))) {
						remember(cards.get(focus));
						cards.get(focus).toggle();
					}
					return true;
				}
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
					if (focus >= 0 && focus < n) openSettings(cards.get(focus));
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (closeAt != 0) return true;
		if (typing != null && input.isAllowedChatCharacter()) {
			editor(typing).type(input.codepointAsString());
			return true;
		}
		// Typing with nothing else focused goes straight into the search; in the world only once
		// you've clicked into it, so WASD walks instead
		boolean autoSearch = !input.codepointAsString().isBlank() && !WorldGui.showing(this);
		if (!hudEditing && binding == null && bindingKey == null && input.isAllowedChatCharacter() && (searching || autoSearch)) {
			searching = true;
			open = null;
			scroll = 0;
			smooth = 0;
			focus = -1;
			editor(search).type(input.codepointAsString());
			return true;
		}
		return super.charTyped(input);
	}

	private void clearSearch() {
		search.set("");
		editors.remove(search);
		scroll = 0;
		smooth = 0;
	}

	private TextEditor editor(StringSetting setting) {
		return editors.computeIfAbsent(setting, TextEditor::new);
	}

	private static String keyName(int key) {
		if (key < 0) return "None";
		return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
	}

	private static String trim(double value) {
		String text = String.format("%.2f", value);
		if (text.contains(".")) text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
		return text;
	}
}
