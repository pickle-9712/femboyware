package dev.femboyware.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.module.hud.HudElement;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
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
	private static final int SETTING_BG = 0x1C000000;
	private static final String[] CHANNEL_NAMES = {"Red", "Green", "Blue", "Alpha"};
	private static final int[] CHANNEL_SHIFTS = {16, 8, 0, 24};

	private enum RowType { NAV, FAVORITES, HUD_EDIT, GUI_SETTINGS, HUD_DONE, CHIP, CARD, STAR, SWITCH, BACK, HEADER_SWITCH, SEARCH, BIND, BOOL, NUMBER, ENUM, ENUM_OPTION, STRING, COLOR, KEY }

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
	private final Map<Category.Group, Category> tab = new EnumMap<>(Category.Group.class);
	/** The module whose settings are open, or null for the cards. */
	private Module open;
	private boolean hudEditing;
	/** How far the content is scrolled, and how tall it was last frame. */
	private int scroll, contentHeight, viewHeight;
	/** Where the content is drawn: eases toward {@link #scroll} so the wheel glides instead of jumping. */
	private float smooth;
	private long lastFrame;
	/** The cards in order last frame, and which one has keyboard focus (-1 none). */
	private final List<Module> cards = new ArrayList<>();
	private final List<int[]> cardSpots = new ArrayList<>();
	private int focus = -1;

	/** The mode setting whose dropdown is open, if any. */
	private EnumSetting<?> openEnum;
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
		if (hudEditing) {
			context.fill(0, 0, width, height, 0x30000000);
			return;
		}
		if (Theme.blur()) renderBlurredBackground(context);
		// The dim fades with the window
		int dim = Theme.dim();
		context.fill(0, 0, width, height, ColorUtil.withAlpha(dim, (int) ((dim >>> 24) * openness())));
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
				dashedOutline(context, left - 1, top - 1, right + 1, bottom + 1, hot ? Theme.accent() : 0x80FFFFFF);
				if (element.resizable()) context.fill(right - RESIZE_HANDLE, bottom - RESIZE_HANDLE, right + 1, bottom + 1, element == resizingElement || hot ? Theme.accent() : 0xC0FFFFFF);
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
		if (slider != null) updateSlider(mouseX);
		rows.clear();
		int accent = module.getAccent();
		if (hudEditing) {
			// Just a pill to come back by; the elements are dragged about underneath
			String label = "Drag your HUD about  ·  Done";
			int w = font.width(label) + 24, x = (canvasWidth() - w) / 2, y = 8;
			Row done = new Row(RowType.HUD_DONE, x, y, w, 18, null, null, null, 0, null);
			rows.add(done);
			Glass.draw(context, x, y, x + w, y + 18, 9, done.contains(mouseX, mouseY) ? ColorUtil.withAlpha(accent, 0xB0) : Theme.panel(), true);
			context.drawString(font, label, x + 12, y + 5, Theme.text());
			return;
		}

		int w = Math.min(600, canvasWidth() - 24), h = Math.min(340, canvasHeight() - 20);
		int x1 = (canvasWidth() - w) / 2, y1 = (canvasHeight() - h) / 2, x2 = x1 + w, y2 = y1 + h;
		Glass.draw(context, x1, y1, x2, y2, Math.max(8, Theme.roundness()), Theme.panel(), true);
		renderSidebar(context, x1, y1, y2 - FOOTER, mouseX, mouseY, accent);

		// The content side: search along the top, then the cards or a module's settings
		int cx1 = x1 + SIDEBAR + 4, cx2 = x2 - PAD, cy1 = y1 + PAD;
		Glass.rounded(context, cx1 - 4, y1 + 4, x2 - 4, y2 - FOOTER, 8, 0x22000000);
		renderSearch(context, cx1 + 2, cy1, cx2, mouseX, mouseY, accent);
		int top = cy1 + SEARCH_HEIGHT + 6;
		if (open == null && !favorites && search.get().isBlank()) top = renderChips(context, cx1 + 2, top, cx2, mouseX, mouseY, accent);
		int bottom = y2 - FOOTER - 6;
		viewHeight = bottom - top;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewHeight)));
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		smooth += (scroll - smooth) * (1 - (float) Math.exp(-dt * 16));
		if (Math.abs(scroll - smooth) < 0.3f) smooth = scroll;
		int drawn = Math.round(smooth);
		int first = rows.size();
		context.enableScissor(cx1 - 4, top, x2 - 4, bottom);
		int end = open != null ? renderSettingsPage(context, open, cx1 + 2, top - drawn, cx2, mouseX, mouseY, accent)
			: renderCards(context, cx1 + 2, top - drawn, cx2, mouseX, mouseY, accent);
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
			Glass.rounded(context, x2 - 8, barY, x2 - 6, barY + barH, 1, ColorUtil.withAlpha(accent, 0xA0));
		}

		// The footer: how to drive it
		String hint = open != null ? "Esc goes back  ·  Right click a setting resets it  ·  Middle click binds"
			: "Click toggles  ·  Right click or Enter: settings  ·  Space toggles  ·  Tab moves  ·  Middle click binds";
		context.drawString(font, ellipsize(hint, w - 2 * PAD), x1 + PAD, y2 - FOOTER + 4, Theme.muted());
		renderTooltip(context, mouseX, mouseY);
	}

	private void renderSidebar(GuiGraphics context, int x1, int y1, int bottom, int mouseX, int mouseY, int accent) {
		int x = x1 + PAD, y = y1 + PAD + 2;
		// The logo: a little star in the accent, the name, and the version
		drawIcon(context, "logo", x - 1, y + 1, 12, accent);
		context.drawString(font, "femboyware", x + 12, y, Theme.text());
		context.drawString(font, "Version " + FemboyWare.VERSION, x + 12, y + 10, ColorUtil.withAlpha(accent, 0xFF));
		y += 24;
		y = sectionHeader(context, "FEATURES", x, y);
		y = navItem(context, RowType.FAVORITES, null, "star", "Favorites", favorites && open == null, x1, y, mouseX, mouseY, accent);
		for (Category.Group g : FEATURES) y = navItem(context, RowType.NAV, g, icon(g), g.getDisplayName(), !favorites && group == g, x1, y, mouseX, mouseY, accent);
		y += 6;
		y = sectionHeader(context, "OTHERS", x, y);
		for (Category.Group g : OTHERS) y = navItem(context, RowType.NAV, g, icon(g), g == Category.Group.HUD ? "HUD Elements" : g.getDisplayName(), !favorites && group == g, x1, y, mouseX, mouseY, accent);
		y = navItem(context, RowType.HUD_EDIT, null, "hud_editor", "HUD Editor", false, x1, y, mouseX, mouseY, accent);
		// The ClickGUI's own settings: open and close animations and how long they take
		y = navItem(context, RowType.GUI_SETTINGS, null, "customize", "Animations", open instanceof dev.femboyware.module.client.ClickGuiModule, x1, y, mouseX, mouseY, accent);

		// You, at the bottom
		if (mc().player != null && y <= bottom - 20) {
			int fy = bottom - 20;
			//? if >=26.1 {
			/*net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(context, mc().player.getSkin(), x, fy, 16);
			*///?} else {
			net.minecraft.client.gui.components.PlayerFaceRenderer.draw(context, mc().player.getSkin(), x, fy, 16);
			//?}
			context.drawString(font, mc().player.getGameProfile().name(), x + 22, fy, Theme.text());
			Glass.rounded(context, x + 22, fy + 11, x + 26, fy + 15, 2, 0xFF3DDC84);
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
		context.fill(x + font.width(text) + 5, y + 4, x + SIDEBAR - 2 * PAD, y + 5, 0x20FFFFFF);
		return y + 12;
	}

	private int navItem(GuiGraphics context, RowType type, Category.Group g, String icon, String label, boolean selected, int x1, int y, int mouseX, int mouseY, int accent) {
		Row row = new Row(type, x1 + 5, y, SIDEBAR - 8, NAV_HEIGHT, null, null, null, 0, g);
		rows.add(row);
		if (selected) {
			Glass.rounded(context, row.x, y, row.x + row.width, y + NAV_HEIGHT, 5, ColorUtil.withAlpha(accent, 0x48));
			Glass.rounded(context, row.x, y + 3, row.x + 2, y + NAV_HEIGHT - 3, 1, accent);
		} else if (row.contains(mouseX, mouseY)) {
			Glass.rounded(context, row.x, y, row.x + row.width, y + NAV_HEIGHT, 5, 0x14FFFFFF);
		}
		int color = selected ? Theme.text() : Theme.muted();
		drawIcon(context, icon, row.x + 8, y + 2, 9, selected ? accent : color);
		context.drawString(font, label, row.x + 22, y + 3, color);
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
		Glass.rounded(context, x1, y, x2, y + SEARCH_HEIGHT, 7, searching ? ColorUtil.withAlpha(accent, 0x30) : box.contains(mouseX, mouseY) ? 0x30FFFFFF : 0x24FFFFFF);
		// A little magnifying glass
		drawIcon(context, "search", x1 + 7, y + 5, 8, Theme.muted());
		if (search.get().isEmpty() && !searching) {
			context.drawString(font, "Search modules...", x1 + 19, y + 5, Theme.muted());
		} else {
			editor(search).render(context, font, x1 + 19, y + 3, x2 - 8, y + SEARCH_HEIGHT - 2, searching, Theme.text(), (accent & 0x00FFFFFF) | 0x90000000, accent);
		}
	}

	/** The section's tabs as chips, when it has more than one. Returns the y below them. */
	private int renderChips(GuiGraphics context, int x1, int y, int x2, int mouseX, int mouseY, int accent) {
		List<Category> tabs = tabs(group);
		if (tabs.size() <= 1) return y;
		Category current = currentTab();
		int x = x1;
		for (Category c : tabs) {
			int w = font.width(c.getDisplayName()) + 16;
			if (x + w > x2) {
				x = x1;
				y += CHIP_HEIGHT + 4;
			}
			Row chip = new Row(RowType.CHIP, x, y, w, CHIP_HEIGHT, c, null, null, 0, null);
			rows.add(chip);
			boolean on = c == current;
			Glass.rounded(context, x, y, x + w, y + CHIP_HEIGHT, 6, on ? ColorUtil.withAlpha(accent, 0x90) : chip.contains(mouseX, mouseY) ? 0x26FFFFFF : 0x16FFFFFF);
			context.drawString(font, c.getDisplayName(), x + 8, y + 3, on ? Theme.text() : Theme.muted());
			x += w + 4;
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
		if (favorites) {
			List<Module> list = new ArrayList<>();
			for (Module m : FemboyWare.getModules().getModules()) if (module.isFavorite(m)) list.add(m);
			list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
			return list;
		}
		Category c = currentTab();
		return c == null ? List.of() : FemboyWare.getModules().getModules(c);
	}

	private int renderCards(GuiGraphics context, int x1, int y, int x2, int mouseX, int mouseY, int accent) {
		List<Module> list = shown();
		cards.clear();
		cardSpots.clear();
		if (list.isEmpty()) {
			String empty = favorites ? "Star a module to keep it here" : "Nothing matches";
			context.drawString(font, empty, x1 + 4, y + 6, Theme.muted());
			return y + 24;
		}
		int cardW = (x2 - x1 - GAP) / 2;
		for (int i = 0; i < list.size(); i++) {
			Module m = list.get(i);
			int x = x1 + (i % 2) * (cardW + GAP), cy = y + (i / 2) * (CARD_HEIGHT + GAP);
			cards.add(m);
			cardSpots.add(new int[]{x, cy});
			renderCard(context, m, x, cy, cardW, mouseX, mouseY, accent, i == focus);
		}
		return y + ((list.size() + 1) / 2) * (CARD_HEIGHT + GAP);
	}

	private void renderCard(GuiGraphics context, Module m, int x, int y, int w, int mouseX, int mouseY, int accent, boolean focused) {
		Row card = new Row(RowType.CARD, x, y, w, CARD_HEIGHT, m.getCategory(), m, null, 0, null);
		rows.add(card);
		boolean on = m.isEnabled(), hover = card.contains(mouseX, mouseY);
		int base = on ? ColorUtil.withAlpha(accent, hover ? 0x58 : 0x44) : hover ? 0x22FFFFFF : 0x14FFFFFF;
		Glass.rounded(context, x, y, x + w, y + CARD_HEIGHT, 7, base);
		if (focused) outline(context, x, y, x + w, y + CARD_HEIGHT, accent);
		String name = binding == m ? m.getName() + "  [press a key]" : m.getName();
		context.drawString(font, name, x + 10, y + 6, Theme.text());
		int textRight = x + w - 52;
		String description = ellipsize(m.getDescription(), textRight - (x + 10));
		context.drawString(font, description, x + 10, y + 17, Theme.muted());

		// The star, then the switch
		boolean fav = module.isFavorite(m);
		int sx = x + w - 46, sy = y + (CARD_HEIGHT - 12) / 2;
		Row star = new Row(RowType.STAR, sx - 2, sy - 2, 14, 16, null, m, null, 0, null);
		rows.add(star);
		drawIcon(context, fav ? "star" : "star_outline", sx, sy + 1, 10, fav ? accent : star.contains(mouseX, mouseY) ? Theme.text() : Theme.muted());
		if (m != module) {
			int tx = x + w - 28, ty = y + (CARD_HEIGHT - 10) / 2;
			rows.add(new Row(RowType.SWITCH, tx - 2, ty - 3, 24, 16, null, m, null, 0, null));
			drawSwitch(context, tx, ty, on, accent);
		}
	}

	private static void drawSwitch(GuiGraphics context, int x, int y, boolean on, int accent) {
		Glass.rounded(context, x, y, x + 20, y + 10, 5, on ? accent : 0x38FFFFFF);
		int knob = on ? x + 11 : x + 1;
		Glass.rounded(context, knob, y + 1, knob + 8, y + 9, 4, 0xFFFFFFFF);
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

	/** Every module whose name or description contains {@code query}, name matches first. */
	private static List<Module> matches(String query) {
		List<Module> byName = new ArrayList<>();
		List<Module> byDescription = new ArrayList<>();
		for (Module m : FemboyWare.getModules().getModules()) {
			if (m.getName().toLowerCase().contains(query)) byName.add(m);
			else if (m.getDescription().toLowerCase().contains(query)) byDescription.add(m);
		}
		byName.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		byName.addAll(byDescription);
		return byName;
	}

	/** Tabs of {@code group} that have at least one module; empty tabs are hidden. */
	private static List<Category> tabs(Category.Group group) {
		List<Category> tabs = new ArrayList<>();
		for (Category category : Category.values()) {
			if (category.getGroup() == group && !FemboyWare.getModules().getModules(category).isEmpty()) tabs.add(category);
		}
		return tabs;
	}

	// ---------------------------------------------------------------- a module's settings

	private int renderSettingsPage(GuiGraphics context, Module m, int x1, int y, int x2, int mouseX, int mouseY, int accent) {
		int w = x2 - x1;
		// Back, the name with its switch, and what it does
		Row back = new Row(RowType.BACK, x1, y, font.width("Back") + 20, 14, null, null, null, 0, null);
		rows.add(back);
		Glass.rounded(context, back.x, y, back.x + back.width, y + 14, 6, back.contains(mouseX, mouseY) ? 0x30FFFFFF : 0x18FFFFFF);
		drawIcon(context, "back", back.x + 5, y + 3, 8, Theme.text());
		context.drawString(font, "Back", back.x + 14, y + 3, Theme.text());
		context.drawString(font, m.getName(), back.x + back.width + 8, y + 3, Theme.text());
		boolean fav = module.isFavorite(m);
		int sx = x2 - 46;
		Row star = new Row(RowType.STAR, sx - 2, y - 1, 14, 16, null, m, null, 0, null);
		rows.add(star);
		drawIcon(context, fav ? "star" : "star_outline", sx, y + 2, 10, fav ? accent : star.contains(mouseX, mouseY) ? Theme.text() : Theme.muted());
		if (m != module) {
			rows.add(new Row(RowType.HEADER_SWITCH, x2 - 30, y - 1, 24, 16, null, m, null, 0, null));
			drawSwitch(context, x2 - 28, y + 2, m.isEnabled(), accent);
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

		Glass.rounded(context, x, y, x + w, y + ROW_HEIGHT + 2, 4, SETTING_BG);
		String bindText = binding == m ? "Bind: press a key..." : "Bind: " + keyName(m.getKey());
		context.drawString(font, bindText, left, y + 4, Theme.muted());
		rows.add(new Row(RowType.BIND, x, y, w, ROW_HEIGHT + 2, null, m, null, 0, null));
		y += ROW_HEIGHT + 5;

		for (Setting<?> setting : m.getSettings()) {
			if (!setting.isVisible()) continue;

			if (setting instanceof ColorSetting color) {
				context.fill(x, y, x + w, y + ROW_HEIGHT, SETTING_BG);
				context.drawString(font, color.getName(), left, y + 3, Theme.text());
				Glass.rounded(context, right - 14, y + 2, right, y + ROW_HEIGHT - 2, 2, color.get() | 0xFF000000);
				rows.add(new Row(RowType.COLOR, x, y, w, ROW_HEIGHT, null, m, color, -1, null));
				y += ROW_HEIGHT;
				for (int i = 0; i < 4; i++) {
					int value = color.getChannel(CHANNEL_SHIFTS[i]);
					drawSlider(context, x, y, w, CHANNEL_NAMES[i], String.valueOf(value), value / 255.0, accent, 4);
					rows.add(new Row(RowType.COLOR, x, y, w, ROW_HEIGHT, null, m, color, CHANNEL_SHIFTS[i], null));
					y += ROW_HEIGHT;
				}
				y += 2;
				continue;
			}

			Row row = switch (setting) {
				case BoolSetting bool -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT, SETTING_BG);
					context.drawString(font, bool.getName(), left, y + 3, Theme.text());
					drawSwitch(context, right - 20, y + 2, bool.get(), accent);
					yield new Row(RowType.BOOL, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				case NumberSetting number -> {
					double fraction = (number.get() - number.getMin()) / (number.getMax() - number.getMin());
					String value = number.isInteger() ? String.valueOf(number.getInt()) : trim(number.get());
					drawSlider(context, x, y, w, number.getName(), value, fraction, accent, 0);
					yield new Row(RowType.NUMBER, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				case EnumSetting<?> mode -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT, SETTING_BG);
					context.drawString(font, mode.getName(), left, y + 3, Theme.text());
					String value = mode.getDisplayName() + (openEnum == mode ? " ▴" : " ▾");
					context.drawString(font, value, right - font.width(value), y + 3, accent);
					yield new Row(RowType.ENUM, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				case StringSetting text -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT * 2, SETTING_BG);
					context.drawString(font, text.getName(), left, y + 3, Theme.text());
					boolean focused = typing == text;
					Glass.rounded(context, left - 2, y + ROW_HEIGHT, right, y + ROW_HEIGHT * 2 - 1, 3, focused ? 0x40FFFFFF : 0x30000000);
					editor(text).render(context, font, left, y + ROW_HEIGHT, right, y + ROW_HEIGHT * 2 - 1, focused,
						focused ? Theme.text() : Theme.muted(), (accent & 0x00FFFFFF) | 0x90000000, accent);
					yield new Row(RowType.STRING, x, y, w, ROW_HEIGHT * 2, null, m, setting, 0, null);
				}
				case KeySetting key -> {
					context.fill(x, y, x + w, y + ROW_HEIGHT, SETTING_BG);
					context.drawString(font, key.getName(), left, y + 3, Theme.text());
					String value = bindingKey == key ? "..." : keyName(key.get());
					context.drawString(font, value, right - font.width(value), y + 3, accent);
					yield new Row(RowType.KEY, x, y, w, ROW_HEIGHT, null, m, setting, 0, null);
				}
				default -> null;
			};
			if (row == null) continue;
			if (row.contains(mouseX, mouseY)) context.fill(row.x, row.y, row.x + row.width, row.y + row.height, 0x10FFFFFF);
			rows.add(row);
			y += row.height;
			if (setting instanceof EnumSetting<?> mode && openEnum == mode) {
				Enum<?>[] options = mode.getConstants();
				for (int i = 0; i < options.length; i++) {
					boolean current = options[i] == mode.get();
					context.fill(x, y, x + w, y + ROW_HEIGHT, SETTING_BG);
					context.fill(left - 2, y, left - 1, y + ROW_HEIGHT, current ? accent : 0x30FFFFFF);
					context.drawString(font, EnumSetting.prettify(options[i].name()), left + 4, y + 3, current ? accent : Theme.muted());
					Row option = new Row(RowType.ENUM_OPTION, x, y, w, ROW_HEIGHT, null, m, setting, i, null);
					if (option.contains(mouseX, mouseY)) context.fill(x, y, x + w, y + ROW_HEIGHT, 0x10FFFFFF);
					rows.add(option);
					y += ROW_HEIGHT;
				}
			}
		}
		return y + 6;
	}

	private void drawSlider(GuiGraphics context, int x, int y, int w, String name, String value, double fraction, int accent, int indent) {
		int left = x + 8 + indent;
		int right = x + w - 6;
		context.fill(x, y, x + w, y + ROW_HEIGHT, SETTING_BG);
		int filled = left + (int) Math.round((right - left) * Math.max(0, Math.min(1, fraction)));
		Glass.rounded(context, left, y + ROW_HEIGHT - 3, right, y + ROW_HEIGHT - 1, 1, 0x40FFFFFF);
		if (filled > left) Glass.rounded(context, left, y + ROW_HEIGHT - 3, filled, y + ROW_HEIGHT - 1, 1, accent);
		context.drawString(font, name, left, y + 1, Theme.text());
		context.drawString(font, value, right - font.width(value), y + 1, Theme.muted());
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
			if (description == null || description.isEmpty()) return;
			int textWidth = font.width(description);
			int x = Math.min(mouseX + 8, canvasWidth() - textWidth - 6);
			int y = mouseY + 10;
			Glass.draw(context, x - 4, y - 3, x + textWidth + 4, y + font.lineHeight + 2, Math.min(4, Theme.roundness()), Theme.tooltip(), true);
			context.drawString(font, description, x, y, Theme.text());
			return;
		}
	}

	// ---------------------------------------------------------------- input

	private void openSettings(Module m) {
		open = m;
		openEnum = null;
		scroll = 0;
		smooth = 0;
	}

	private void showGroup(Category.Group g, boolean favs) {
		group = g == null ? group : g;
		favorites = favs;
		open = null;
		scroll = 0;
		smooth = 0;
		focus = -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (closeAt != 0) return true;
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
				case NAV -> showGroup(row.group, false);
				case FAVORITES -> showGroup(null, true);
				case HUD_EDIT -> hudEditing = true;
				case GUI_SETTINGS -> openSettings(dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.ClickGuiModule.class));
				case HUD_DONE -> hudEditing = false;
				case CHIP -> {
					tab.put(group, row.category);
					scroll = 0;
					smooth = 0;
					focus = -1;
				}
				case CARD -> {
					if (left && row.module != module) row.module.toggle();
					else if (right) openSettings(row.module);
					else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) binding = row.module;
				}
				case STAR -> module.toggleFavorite(row.module);
				case SWITCH, HEADER_SWITCH -> row.module.toggle();
				case BACK -> {
					open = null;
					scroll = 0;
					smooth = 0;
				}
				case BIND -> binding = row.module;
				case KEY -> {
					if (right) row.setting.reset();
					else bindingKey = (KeySetting) row.setting;
				}
				case BOOL -> {
					if (right) row.setting.reset();
					else ((BoolSetting) row.setting).toggle();
				}
				case ENUM -> {
					EnumSetting<?> mode = (EnumSetting<?>) row.setting;
					if (right) {
						mode.reset();
						openEnum = null;
					} else openEnum = openEnum == mode ? null : mode;
				}
				case ENUM_OPTION -> {
					((EnumSetting<?>) row.setting).setIndex(row.shift);
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
		if (selectingText && typing != null) {
			editor(typing).drag(font, click.x() / UiScale.scale(), typingRowX + 8);
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		draggingElement = null;
		resizingElement = null;
		slider = null;
		selectingText = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double rawX, double rawY, double horizontalAmount, double verticalAmount) {
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
				if (key == GLFW.GLFW_KEY_TAB) {
					focus = 0;
					scrollToFocus();
				}
				return true;
			}
			editor(search).keyPressed(input);
			scroll = 0;
			smooth = 0;
			return true;
		}
		if (key == GLFW.GLFW_KEY_F && input.hasControlDown()) {
			searching = true;
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && open != null) {
			open = null;
			scroll = 0;
			smooth = 0;
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && !search.get().isEmpty()) {
			clearSearch();
			return true;
		}
		if (open == null && !cards.isEmpty()) {
			int n = cards.size();
			switch (key) {
				case GLFW.GLFW_KEY_TAB -> {
					focus = focus < 0 ? 0 : (focus + (input.hasShiftDown() ? n - 1 : 1)) % n;
					scrollToFocus();
					return true;
				}
				case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
					int step = key == GLFW.GLFW_KEY_RIGHT ? 1 : key == GLFW.GLFW_KEY_LEFT ? -1 : key == GLFW.GLFW_KEY_DOWN ? 2 : -2;
					focus = focus < 0 ? 0 : Math.max(0, Math.min(n - 1, focus + step));
					scrollToFocus();
					return true;
				}
				case 32 -> { // space
					if (focus >= 0 && focus < n && cards.get(focus) != module) cards.get(focus).toggle();
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
		// Typing with nothing else focused goes straight into the search
		if (!hudEditing && binding == null && bindingKey == null && input.isAllowedChatCharacter() && (searching || !input.codepointAsString().isBlank())) {
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
