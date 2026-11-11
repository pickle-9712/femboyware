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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * One panel per category group, each with a row of tabs, holding expandable modules. Layout is rebuilt every frame into {@link #rows},
 * and input handling hit-tests against the rows from the last frame.
 */
public class ClickGuiScreen extends Screen {
	private static final int PANEL_WIDTH = 116;
	private static final int HEADER_HEIGHT = 16;
	private static final int ROW_HEIGHT = 13;
	private static final int TABS_PER_ROW = 3;
	/** Room round a tab's name. */
	private static final int TAB_PADDING = 10;
	/** Settings sit in a slightly darker well inside the glass. */
	private static final int SETTING_BG = 0x24000000;
	private static final String[] CHANNEL_NAMES = {"Red", "Green", "Blue", "Alpha"};
	private static final int[] CHANNEL_SHIFTS = {16, 8, 0, 24};

	private enum RowType { HEADER, SEARCH_HEADER, TAB, MODULE, BIND, BOOL, NUMBER, ENUM, ENUM_OPTION, STRING, COLOR, KEY, SEARCH, PAGE }

	/** The three sections along the top; each shows its own panels. */
	private enum Page {
		MODULES("Modules"), HUD("HUD"), CUSTOMIZATION("Customization");

		private final String label;

		Page(String label) {
			this.label = label;
		}

		/** The page a panel belongs on. */
		static Page of(Category.Group group) {
			return switch (group) {
				case HUD -> HUD;
				case CUSTOMIZE -> CUSTOMIZATION;
				default -> MODULES;
			};
		}
	}

	private static final int PAGE_WIDTH = 92;
	private static final int PAGE_HEIGHT = 18;
	private static final int PAGE_GAP = 8;
	/** Panels start below the page bar. */
	private static final int TOP = 32;

	private record Row(RowType type, int x, int y, int width, int height, Category category, Module module, Setting<?> setting, int shift) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}

	private static class Panel {
		int x;
		int y;
		boolean open = true;
		Category tab;
		/** Height last frame, so the glass can go down before the rows are laid out. */
		int height = HEADER_HEIGHT;

		Panel(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}

	private final ClickGuiModule module;
	private final Map<Category.Group, Panel> panels = new EnumMap<>(Category.Group.class);
	private final Set<Module> expanded = new HashSet<>();
	/** The mode setting whose dropdown is open, if any. */
	private EnumSetting<?> openEnum;
	private final List<Row> rows = new ArrayList<>();

	private Panel dragging;
	/** The section showing; kept while the game runs, so the menu opens where you left it. */
	private Page page = Page.MODULES;
	/** A HUD element being dragged around the screen, and where on it the mouse grabbed it. */
	private static final int RESIZE_HANDLE = 5;
	private HudElement draggingElement;
	/** A HUD element whose corner is being dragged, to resize it. */
	private HudElement resizingElement;
	private int elementOffsetX;
	private int elementOffsetY;
	private int dragOffsetX;
	private int dragOffsetY;
	private Row slider;
	private Module binding;
	/** A key setting waiting for its key, like a module bind. */
	private KeySetting bindingKey;
	private StringSetting typing;
	/** Text boxes keep their cursor, selection and scroll while the GUI is open. */
	private final Map<StringSetting, TextEditor> editors = new HashMap<>();
	/** Left edge of the focused text box's row, for drag-selecting. */
	private int typingRowX;
	private boolean selectingText;
	private boolean closing;
	/** What's typed in the search bar; never saved, since it belongs to no module. */
	private final StringSetting search = new StringSetting("Search", "Search modules by name or description", "");
	private boolean searching;
	/** The Search panel; starts under Customize, and drags and folds like the others. */
	private final Panel searchPanel;

	public ClickGuiScreen(ClickGuiModule module) {
		super(Component.literal("femboyware"));
		this.module = module;
		int x = 10;
		for (Category.Group group : Category.Group.values()) {
			if (Page.of(group) != Page.MODULES) continue;
			panels.put(group, new Panel(x, TOP));
			x += PANEL_WIDTH + 8;
		}
		searchPanel = new Panel(x, TOP);
		// The HUD and Customize panels each have a page to themselves
		panels.put(Category.Group.HUD, new Panel(10, TOP));
		panels.put(Category.Group.CUSTOMIZE, new Panel(10, TOP));
	}

	public boolean isClosing() {
		return closing;
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
		dragging = null;
		module.setEnabled(false);
		closing = false;
		FemboyWare.getConfig().save();
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		// Frosted: blur whatever is behind, then dim it a little so the glass stands out
		if (Theme.blur()) renderBlurredBackground(context);
		context.fill(0, 0, width, height, Theme.dim());
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		renderHudEditor(context, mouseX, mouseY);
		// Lay out on a fixed canvas, so the GUI takes the same share of the screen at any GUI scale
		float scale = UiScale.scale();
		context.pose().pushMatrix();
		context.pose().scale(scale, scale);
		UiScale.begin();
		renderScaled(context, (int) (mouseX / scale), (int) (mouseY / scale));
		UiScale.end();
		context.pose().popMatrix();
	}

	/**
	 * The HUD editor: every HUD element that's on, drawn over the blur in its real place, with an
	 * outline. Drag one to move it.
	 */
	private void renderHudEditor(GuiGraphics context, int mouseX, int mouseY) {
		if (page != Page.HUD) return;
		if (draggingElement != null) {
			draggingElement.moveTo(mouseX - elementOffsetX, mouseY - elementOffsetY, width, height);
		}
		if (resizingElement != null) {
			// Its place is stored as a share of the spare room, so put the top left back after changing the size
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

	private void renderScaled(GuiGraphics context, int mouseX, int mouseY) {
		if (dragging != null) {
			dragging.x = mouseX - dragOffsetX;
			dragging.y = mouseY - dragOffsetY;
		}
		if (slider != null) updateSlider(mouseX);

		rows.clear();
		int accent = module.getAccent();
		Font font = this.font;

		renderPageBar(context, mouseX, mouseY, accent);
		for (Category.Group group : Category.Group.values()) {
			if (Page.of(group) != page) continue;
			Panel panel = panels.get(group);
			List<Category> tabs = tabs(group);
			if (tabs.isEmpty()) continue;
			if (!tabs.contains(panel.tab)) panel.tab = tabs.getFirst();
			int x = panel.x;
			int y = panel.y;

			Glass.draw(context, x, y, x + PANEL_WIDTH, y + panel.height, Theme.roundness(), Theme.panel(), true);
			context.drawString(font, group.getDisplayName(), x + 7, y + 4, Theme.text());
			String arrow = panel.open ? "-" : "+";
			context.drawString(font, arrow, x + PANEL_WIDTH - 7 - font.width(arrow), y + 4, Theme.text());
			rows.add(new Row(RowType.HEADER, x, y, PANEL_WIDTH, HEADER_HEIGHT, panel.tab, null, null, 0));
			y += HEADER_HEIGHT;
			if (!panel.open) {
				panel.height = HEADER_HEIGHT;
				continue;
			}
			context.fill(x + 6, y - 1, x + PANEL_WIDTH - 6, y, 0x30FFFFFF);

			if (tabs.size() > 1) {
				// Tabs as wide as their names, as many to a row as fit (at most three), the spare room
				// shared out, so long names like Cosmetic never run off the panel or into each other
				for (int start = 0; start < tabs.size(); ) {
					int count = 0, used = 0;
					while (start + count < tabs.size() && count < TABS_PER_ROW) {
						int w = font.width(tabs.get(start + count).getDisplayName()) + TAB_PADDING;
						if (count > 0 && used + w > PANEL_WIDTH) break;
						used += w;
						count++;
					}
					int spare = Math.max(0, PANEL_WIDTH - used) / count, left = x;
					for (int i = 0; i < count; i++) {
						Category tab = tabs.get(start + i);
						int right = i == count - 1 ? x + PANEL_WIDTH : left + font.width(tab.getDisplayName()) + TAB_PADDING + spare;
						Row tabRow = new Row(RowType.TAB, left, y, right - left, ROW_HEIGHT, tab, null, null, 0);
						rows.add(tabRow);
						if (tab == panel.tab) Glass.draw(context, left + 3, y + 1, right - 3, y + ROW_HEIGHT - 1, 5, ColorUtil.withAlpha(accent, 0x70), false);
						else if (tabRow.contains(mouseX, mouseY)) Glass.rounded(context, left + 3, y + 1, right - 3, y + ROW_HEIGHT - 1, 5, 0x18FFFFFF);
						String name = tab.getDisplayName();
						context.drawString(font, name, (left + right - font.width(name)) / 2, y + 3, tab == panel.tab ? Theme.text() : Theme.muted());
						left = right;
					}
					start += count;
					y += ROW_HEIGHT;
				}
			}

			for (Module m : FemboyWare.getModules().getModules(panel.tab)) y = renderModule(context, m, x, y, mouseX, mouseY, accent);
			// A little breathing room before the glass's rounded bottom
			panel.height = y + 4 - panel.y;
		}

		if (page == Page.MODULES) renderSearchPanel(context, mouseX, mouseY, accent);
		renderTooltip(context, mouseX, mouseY);
	}

	/** The Modules, HUD and Customization buttons centred along the top, each its own glass button. */
	private void renderPageBar(GuiGraphics context, int mouseX, int mouseY, int accent) {
		Page[] pages = Page.values();
		int canvasWidth = (int) (width / UiScale.scale());
		int total = pages.length * PAGE_WIDTH + (pages.length - 1) * PAGE_GAP;
		int left = (canvasWidth - total) / 2;
		int top = 8;
		for (int i = 0; i < pages.length; i++) {
			int x = left + i * (PAGE_WIDTH + PAGE_GAP);
			Row row = new Row(RowType.PAGE, x, top, PAGE_WIDTH, PAGE_HEIGHT, null, null, null, i);
			rows.add(row);
			boolean selected = pages[i] == page;
			int tint = selected ? ColorUtil.withAlpha(accent, 0xA0) : Theme.panel();
			Glass.draw(context, x, top, x + PAGE_WIDTH, top + PAGE_HEIGHT, PAGE_HEIGHT / 2, tint, true);
			if (!selected && row.contains(mouseX, mouseY)) Glass.rounded(context, x, top, x + PAGE_WIDTH, top + PAGE_HEIGHT, PAGE_HEIGHT / 2, 0x18FFFFFF);
			String label = pages[i].label;
			context.drawString(font, label, x + (PAGE_WIDTH - font.width(label)) / 2, top + (PAGE_HEIGHT - 8) / 2 + 1, selected ? Theme.text() : Theme.muted());
		}
	}

	/** One module's row, plus its settings when expanded. Returns the y below them. */
	private int renderModule(GuiGraphics context, Module m, int x, int y, int mouseX, int mouseY, int accent) {
		Font font = this.font;
		Row moduleRow = new Row(RowType.MODULE, x, y, PANEL_WIDTH, ROW_HEIGHT, m.getCategory(), m, null, 0);
		rows.add(moduleRow);
		if (m.isEnabled()) Glass.draw(context, x + 3, y + 1, x + PANEL_WIDTH - 3, y + ROW_HEIGHT, 4, ColorUtil.withAlpha(accent, 0x80), false);
		else if (moduleRow.contains(mouseX, mouseY)) Glass.rounded(context, x + 3, y + 1, x + PANEL_WIDTH - 3, y + ROW_HEIGHT, 4, 0x18FFFFFF);
		context.drawString(font, m.getName(), x + 7, y + 3, m.isEnabled() ? Theme.text() : Theme.muted());
		if (!m.getSettings().isEmpty()) {
			String dots = expanded.contains(m) ? "v" : "...";
			context.drawString(font, dots, x + PANEL_WIDTH - 7 - font.width(dots), y + 2, m.isEnabled() ? Theme.text() : Theme.muted());
		}
		y += ROW_HEIGHT;
		if (expanded.contains(m)) y = renderSettings(context, m, x, y, mouseX, mouseY, accent);
		return y;
	}

	/** The Search panel: a text box, then every module whose name or description matches. */
	private void renderSearchPanel(GuiGraphics context, int mouseX, int mouseY, int accent) {
		Font font = this.font;
		Panel panel = searchPanel;
		int x = panel.x;
		int y = panel.y;
		Glass.draw(context, x, y, x + PANEL_WIDTH, y + panel.height, Theme.roundness(), Theme.panel(), true);
		context.drawString(font, "Search", x + 7, y + 4, Theme.text());
		String arrow = panel.open ? "-" : "+";
		context.drawString(font, arrow, x + PANEL_WIDTH - 7 - font.width(arrow), y + 4, Theme.text());
		rows.add(new Row(RowType.SEARCH_HEADER, x, y, PANEL_WIDTH, HEADER_HEIGHT, null, null, null, 0));
		y += HEADER_HEIGHT;
		if (!panel.open) {
			panel.height = HEADER_HEIGHT;
			return;
		}
		context.fill(x + 6, y - 1, x + PANEL_WIDTH - 6, y, 0x30FFFFFF);

		// The text box
		Row box = new Row(RowType.SEARCH, x, y + 1, PANEL_WIDTH, ROW_HEIGHT, null, null, search, 0);
		rows.add(box);
		Glass.rounded(context, x + 5, y + 2, x + PANEL_WIDTH - 5, y + ROW_HEIGHT, 4,
			searching ? ColorUtil.withAlpha(accent, 0x40) : box.contains(mouseX, mouseY) ? 0x40FFFFFF : 0x30000000);
		if (search.get().isEmpty() && !searching) {
			context.drawString(font, "Type to search...", x + 10, y + 4, Theme.muted());
		} else {
			editor(search).render(context, font, x + 8, y + 2, x + PANEL_WIDTH - 8, y + ROW_HEIGHT, searching,
				Theme.text(), (accent & 0x00FFFFFF) | 0x90000000, accent);
		}
		y += ROW_HEIGHT + 3;

		String query = search.get().trim().toLowerCase();
		if (!query.isEmpty()) {
			List<Module> results = matches(query);
			if (results.isEmpty()) {
				context.drawString(font, "No matches", x + 7, y + 3, Theme.muted());
				y += ROW_HEIGHT;
			}
			for (Module m : results) y = renderModule(context, m, x, y, mouseX, mouseY, accent);
		}
		panel.height = y + 4 - panel.y;
	}

	/** Every module whose name or description contains {@code query}, name matches first. */
	private static List<Module> matches(String query) {
		List<Module> byName = new ArrayList<>();
		List<Module> byDescription = new ArrayList<>();
		for (Module m : FemboyWare.getModules().getModules()) {
			if (m.getName().toLowerCase().contains(query)) byName.add(m);
			else if (m.getDescription().toLowerCase().contains(query)) byDescription.add(m);
		}
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

	private int renderSettings(GuiGraphics context, Module m, int x, int y, int mouseX, int mouseY, int accent) {
		Font font = this.font;
		int left = x + 8;
		int right = x + PANEL_WIDTH - 5;

		context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
		String bindText = binding == m ? "Bind: ..." : "Bind: " + keyName(m.getKey());
		context.drawString(font, bindText, left, y + 3, Theme.muted());
		rows.add(new Row(RowType.BIND, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, null, 0));
		y += ROW_HEIGHT;

		for (Setting<?> setting : m.getSettings()) {
			if (!setting.isVisible()) continue;

			if (setting instanceof ColorSetting color) {
				context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
				context.drawString(font, color.getName(), left, y + 3, Theme.text());
				context.fill(right - 10, y + 2, right, y + ROW_HEIGHT - 2, color.get() | 0xFF000000);
				y += ROW_HEIGHT;
				for (int i = 0; i < 4; i++) {
					int value = color.getChannel(CHANNEL_SHIFTS[i]);
					drawSlider(context, x, y, CHANNEL_NAMES[i], String.valueOf(value), value / 255.0, accent, 4);
					rows.add(new Row(RowType.COLOR, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, color, CHANNEL_SHIFTS[i]));
					y += ROW_HEIGHT;
				}
				continue;
			}

			Row row = switch (setting) {
				case BoolSetting bool -> {
					context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
					context.drawString(font, bool.getName(), left, y + 3, Theme.text());
					// A little switch: a pill track with a round knob that slides across when on
					int trackLeft = right - 14;
					Glass.rounded(context, trackLeft, y + 3, right, y + ROW_HEIGHT - 3, 4, bool.get() ? accent : 0x40FFFFFF);
					int knob = bool.get() ? right - 7 : trackLeft;
					Glass.rounded(context, knob + 1, y + 4, knob + 6, y + ROW_HEIGHT - 4, 3, 0xFFFFFFFF);
					yield new Row(RowType.BOOL, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, setting, 0);
				}
				case NumberSetting number -> {
					double fraction = (number.get() - number.getMin()) / (number.getMax() - number.getMin());
					String value = number.isInteger() ? String.valueOf(number.getInt()) : trim(number.get());
					drawSlider(context, x, y, number.getName(), value, fraction, accent, 0);
					yield new Row(RowType.NUMBER, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, setting, 0);
				}
				case EnumSetting<?> mode -> {
					context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
					context.drawString(font, mode.getName(), left, y + 3, Theme.text());
					String value = mode.getDisplayName() + (openEnum == mode ? " ^" : " v");
					context.drawString(font, value, right - font.width(value), y + 3, accent);
					yield new Row(RowType.ENUM, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, setting, 0);
				}
				case StringSetting text -> {
					context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT * 2, SETTING_BG);
					context.drawString(font, text.getName(), left, y + 3, Theme.text());
					boolean focused = typing == text;
					Glass.rounded(context, left - 2, y + ROW_HEIGHT, right, y + ROW_HEIGHT * 2 - 1, 3, focused ? 0x40FFFFFF : 0x30000000);
					editor(text).render(context, font, left, y + ROW_HEIGHT, right, y + ROW_HEIGHT * 2 - 1, focused,
						focused ? Theme.text() : Theme.muted(), (accent & 0x00FFFFFF) | 0x90000000, accent);
					yield new Row(RowType.STRING, x, y, PANEL_WIDTH, ROW_HEIGHT * 2, null, m, setting, 0);
				}
				case KeySetting key -> {
					context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
					context.drawString(font, key.getName(), left, y + 3, Theme.text());
					String value = bindingKey == key ? "..." : keyName(key.get());
					context.drawString(font, value, right - font.width(value), y + 3, accent);
					yield new Row(RowType.KEY, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, setting, 0);
				}
				default -> null;
			};
			if (row == null) continue;
			if (row.contains(mouseX, mouseY)) context.fill(row.x, row.y, row.x + row.width, row.y + row.height, 0x10FFFFFF);
			rows.add(row);
			y += row.height;
			if (setting instanceof EnumSetting<?> mode && openEnum == mode) {
				// The open dropdown: one row per option, the current one highlighted
				Enum<?>[] options = mode.getConstants();
				for (int i = 0; i < options.length; i++) {
					boolean current = options[i] == mode.get();
					context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
					context.fill(left - 2, y, left - 1, y + ROW_HEIGHT, current ? accent : 0x30FFFFFF);
					context.drawString(font, EnumSetting.prettify(options[i].name()), left + 4, y + 3, current ? accent : Theme.muted());
					Row option = new Row(RowType.ENUM_OPTION, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, setting, i);
					if (option.contains(mouseX, mouseY)) context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, 0x10FFFFFF);
					rows.add(option);
					y += ROW_HEIGHT;
				}
			}
		}
		return y;
	}

	private void drawSlider(GuiGraphics context, int x, int y, String name, String value, double fraction, int accent, int indent) {
		Font font = this.font;
		int left = x + 8 + indent;
		int right = x + PANEL_WIDTH - 5;
		context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
		int filled = left + (int) Math.round((right - left) * Math.max(0, Math.min(1, fraction)));
		Glass.rounded(context, left, y + ROW_HEIGHT - 3, right, y + ROW_HEIGHT - 1, 1, 0x40FFFFFF);
		if (filled > left) Glass.rounded(context, left, y + ROW_HEIGHT - 3, filled, y + ROW_HEIGHT - 1, 1, accent);
		context.drawString(font, name, left, y + 1, Theme.text());
		context.drawString(font, value, right - font.width(value), y + 1, Theme.muted());
	}

	private void renderTooltip(GuiGraphics context, int mouseX, int mouseY) {
		if (dragging != null || slider != null) return;
		for (Row row : rows) {
			if (!row.contains(mouseX, mouseY)) continue;
			// The Customize panel's modules and settings explain themselves; no tooltips over them
			if (row.module != null && row.module.getCategory() == Category.THEME) return;
			String description = row.setting != null ? row.setting.getDescription()
				: row.type == RowType.MODULE ? row.module.getDescription()
				: row.type == RowType.BIND ? "Click, then press a key. Escape or Delete unbinds" : null;
			if (description == null || description.isEmpty()) return;
			int textWidth = font.width(description);
			int x = Math.min(mouseX + 8, (int) (width / UiScale.scale()) - textWidth - 6);
			int y = mouseY + 10;
			Glass.draw(context, x - 4, y - 3, x + textWidth + 4, y + font.lineHeight + 2, Math.min(4, Theme.roundness()), Theme.tooltip(), true);
			context.drawString(font, description, x, y, Theme.text());
			return;
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		double mouseX = click.x() / UiScale.scale();
		double mouseY = click.y() / UiScale.scale();
		int button = click.button();
		StringSetting wasTyping = typing;
		boolean wasSearching = searching;
		typing = null;
		searching = false;

		if (binding != null || bindingKey != null) {
			// Mouse buttons can't be bound; clicking anywhere cancels
			binding = null;
			bindingKey = null;
			return true;
		}

		for (int i = rows.size() - 1; i >= 0; i--) {
			Row row = rows.get(i);
			if (!row.contains(mouseX, mouseY)) continue;
			switch (row.type) {
				case HEADER -> {
					Panel panel = panels.get(row.category.getGroup());
					if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
						dragging = panel;
						dragOffsetX = (int) mouseX - panel.x;
						dragOffsetY = (int) mouseY - panel.y;
					} else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
						panel.open = !panel.open;
					}
				}
				case PAGE -> {
					page = Page.values()[row.shift];
					draggingElement = null;
				}
				case SEARCH_HEADER -> {
					if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
						dragging = searchPanel;
						dragOffsetX = (int) mouseX - searchPanel.x;
						dragOffsetY = (int) mouseY - searchPanel.y;
					} else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
						searchPanel.open = !searchPanel.open;
					}
				}
				case TAB -> panels.get(row.category.getGroup()).tab = row.category;
				case MODULE -> {
					if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
						if (row.module != module) row.module.toggle();
					} else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
						if (!expanded.remove(row.module)) expanded.add(row.module);
					} else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
						binding = row.module;
					}
				}
				case BIND -> binding = row.module;
				case KEY -> bindingKey = (KeySetting) row.setting;
				case BOOL -> ((BoolSetting) row.setting).toggle();
				case ENUM -> {
					// Left click opens the dropdown, right click still cycles backwards
					EnumSetting<?> mode = (EnumSetting<?>) row.setting;
					if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) mode.cycle(false);
					else openEnum = openEnum == mode ? null : mode;
				}
				case ENUM_OPTION -> {
					((EnumSetting<?>) row.setting).setIndex(row.shift);
					openEnum = null;
				}
				case STRING -> {
					typing = (StringSetting) row.setting;
					typingRowX = row.x;
					TextEditor editor = editor(typing);
					// Shift+click grows the selection, but only within the box that already had focus
					editor.click(font, mouseX, row.x + 8, click.hasShiftDown() && wasTyping == typing);
					if (doubled) editor.selectWord();
					selectingText = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
				}
				case NUMBER, COLOR -> {
					if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
						slider = row;
						updateSlider(mouseX);
					}
				}
				case SEARCH -> {
					if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) search.set("");
					searching = true;
					editor(search).click(font, mouseX, row.x + 8, click.hasShiftDown() && wasSearching);
					if (doubled) editor(search).selectWord();
				}
			}
			return true;
		}
		// Nothing in the menu was clicked: pick up a HUD element under the mouse, topmost first
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && page == Page.HUD) {
			List<HudElement> elements = hudElements();
			for (int i = elements.size() - 1; i >= 0; i--) {
				HudElement element = elements.get(i);
				int left = element.left(width, true), top = element.top(height, true);
				int right = left + element.width(true), bottom = top + element.height(true);
				if (element.resizable() && Math.abs(click.x() - right) <= RESIZE_HANDLE && Math.abs(click.y() - bottom) <= RESIZE_HANDLE) {
					resizingElement = element;
					return true;
				}
				if (click.x() < left || click.x() >= left + element.width(true) || click.y() < top || click.y() >= top + element.height(true)) continue;
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
		dragging = null;
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
		// Over a text box, the wheel scrolls its text sideways instead of moving the panels
		for (Row row : rows) {
			if (row.type != RowType.STRING || mouseX < row.x + 8 || mouseX >= row.x + PANEL_WIDTH - 5
				|| mouseY < row.y + ROW_HEIGHT || mouseY >= row.y + ROW_HEIGHT * 2) continue;
			int pixels = (int) ((horizontalAmount - verticalAmount) * 12);
			editor((StringSetting) row.setting).scrollBy(font, pixels, PANEL_WIDTH - 13 - 4);
			return true;
		}
		for (Panel panel : panels.values()) panel.y += (int) (verticalAmount * 14);
		searchPanel.y += (int) (verticalAmount * 14);
		return true;
	}

	private void updateSlider(double mouseX) {
		Row row = slider;
		int indent = row.type == RowType.COLOR ? 4 : 0;
		int left = row.x + 8 + indent;
		int right = row.x + PANEL_WIDTH - 5;
		double fraction = Math.max(0, Math.min(1, (mouseX - left) / (right - left)));
		if (row.setting instanceof NumberSetting number) {
			number.set(number.getMin() + fraction * (number.getMax() - number.getMin()));
		} else if (row.setting instanceof ColorSetting color) {
			color.setChannel(row.shift, (int) Math.round(fraction * 255));
		}
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
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
			// Swallow everything else too, so typing never fires keybinds
			return true;
		}
		if (searching) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				// First Escape clears the search, the next closes the menu
				if (search.get().isEmpty()) searching = false;
				else clearSearch();
			} else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				searching = false;
			} else {
				editor(search).keyPressed(input);
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_F && input.hasControlDown()) {
			searching = true;
			searchPanel.open = true;
			// Search lives on the Modules page
			page = Page.MODULES;
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && !search.get().isEmpty()) {
			clearSearch();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (typing != null && input.isAllowedChatCharacter()) {
			editor(typing).type(input.codepointAsString());
			return true;
		}
		// Typing with nothing else focused goes straight into the search
		if (binding == null && bindingKey == null && input.isAllowedChatCharacter() && (searching || !input.codepointAsString().isBlank())) {
			searching = true;
			searchPanel.open = true;
			// Search lives on the Modules page
			page = Page.MODULES;
			editor(search).type(input.codepointAsString());
			return true;
		}
		return super.charTyped(input);
	}

	private void clearSearch() {
		search.set("");
		editors.remove(search);
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
