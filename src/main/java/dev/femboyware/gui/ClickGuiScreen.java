package dev.femboyware.gui;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.Setting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ColorUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One panel per category group, each with a row of tabs, holding expandable modules. Layout is rebuilt every frame into {@link #rows},
 * and input handling hit-tests against the rows from the last frame.
 */
public class ClickGuiScreen extends Screen {
	private static final int PANEL_WIDTH = 116;
	private static final int HEADER_HEIGHT = 16;
	private static final int ROW_HEIGHT = 13;
	private static final int PANEL_BG = 0xE0161219;
	private static final int SETTING_BG = 0xE01E1923;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFAAA3B0;
	private static final String[] CHANNEL_NAMES = {"Red", "Green", "Blue", "Alpha"};
	private static final int[] CHANNEL_SHIFTS = {16, 8, 0, 24};

	private enum RowType { HEADER, TAB, MODULE, BIND, BOOL, NUMBER, ENUM, STRING, COLOR }

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

		Panel(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}

	private final ClickGuiModule module;
	private final Map<Category.Group, Panel> panels = new EnumMap<>(Category.Group.class);
	private final Set<Module> expanded = new HashSet<>();
	private final List<Row> rows = new ArrayList<>();

	private Panel dragging;
	private int dragOffsetX;
	private int dragOffsetY;
	private Row slider;
	private Module binding;
	private StringSetting typing;
	private boolean closing;

	public ClickGuiScreen(ClickGuiModule module) {
		super(Text.literal("femboyware"));
		this.module = module;
		int x = 10;
		for (Category.Group group : Category.Group.values()) {
			panels.put(group, new Panel(x, 10));
			x += PANEL_WIDTH + 8;
		}
	}

	public boolean isClosing() {
		return closing;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void removed() {
		closing = true;
		binding = null;
		typing = null;
		slider = null;
		dragging = null;
		module.setEnabled(false);
		closing = false;
		FemboyWare.getConfig().save();
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, 0x60000000);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		if (dragging != null) {
			dragging.x = mouseX - dragOffsetX;
			dragging.y = mouseY - dragOffsetY;
		}
		if (slider != null) updateSlider(mouseX);

		rows.clear();
		int accent = module.getAccent();
		TextRenderer font = textRenderer;

		for (Category.Group group : Category.Group.values()) {
			Panel panel = panels.get(group);
			List<Category> tabs = tabs(group);
			if (tabs.isEmpty()) continue;
			if (!tabs.contains(panel.tab)) panel.tab = tabs.getFirst();
			int x = panel.x;
			int y = panel.y;

			context.fill(x, y, x + PANEL_WIDTH, y + HEADER_HEIGHT, accent);
			context.drawTextWithShadow(font, group.getDisplayName(), x + 5, y + 4, TEXT);
			String arrow = panel.open ? "-" : "+";
			context.drawTextWithShadow(font, arrow, x + PANEL_WIDTH - 5 - font.getWidth(arrow), y + 4, TEXT);
			rows.add(new Row(RowType.HEADER, x, y, PANEL_WIDTH, HEADER_HEIGHT, panel.tab, null, null, 0));
			y += HEADER_HEIGHT;
			if (!panel.open) continue;

			if (tabs.size() > 1) {
				context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
				for (int i = 0; i < tabs.size(); i++) {
					Category tab = tabs.get(i);
					int left = x + PANEL_WIDTH * i / tabs.size();
					int right = x + PANEL_WIDTH * (i + 1) / tabs.size();
					Row tabRow = new Row(RowType.TAB, left, y, right - left, ROW_HEIGHT, tab, null, null, 0);
					rows.add(tabRow);
					if (tab == panel.tab) context.fill(left + 1, y + 1, right - 1, y + ROW_HEIGHT - 1, ColorUtil.withAlpha(accent, 0x60));
					else if (tabRow.contains(mouseX, mouseY)) context.fill(left, y, right, y + ROW_HEIGHT, 0x14FFFFFF);
					String name = tab.getDisplayName();
					context.drawTextWithShadow(font, name, (left + right - font.getWidth(name)) / 2, y + 3, tab == panel.tab ? TEXT : MUTED);
				}
				y += ROW_HEIGHT;
			}

			for (Module m : FemboyWare.getModules().getModules(panel.tab)) {
				Row moduleRow = new Row(RowType.MODULE, x, y, PANEL_WIDTH, ROW_HEIGHT, panel.tab, m, null, 0);
				rows.add(moduleRow);
				context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, PANEL_BG);
				if (m.isEnabled()) context.fill(x + 1, y, x + PANEL_WIDTH - 1, y + ROW_HEIGHT, ColorUtil.withAlpha(accent, 0x90));
				if (moduleRow.contains(mouseX, mouseY)) context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, 0x20FFFFFF);
				context.drawTextWithShadow(font, m.getName(), x + 5, y + 3, m.isEnabled() ? TEXT : MUTED);
				if (!m.getSettings().isEmpty()) {
					String dots = expanded.contains(m) ? "v" : "...";
					context.drawTextWithShadow(font, dots, x + PANEL_WIDTH - 5 - font.getWidth(dots), y + 2, MUTED);
				}
				y += ROW_HEIGHT;

				if (expanded.contains(m)) y = renderSettings(context, m, x, y, mouseX, mouseY, accent);
			}
			context.fill(x, y, x + PANEL_WIDTH, y + 2, accent);
		}

		renderTooltip(context, mouseX, mouseY);
	}

	/** Tabs of {@code group} that have at least one module; empty tabs are hidden. */
	private static List<Category> tabs(Category.Group group) {
		List<Category> tabs = new ArrayList<>();
		for (Category category : Category.values()) {
			if (category.getGroup() == group && !FemboyWare.getModules().getModules(category).isEmpty()) tabs.add(category);
		}
		return tabs;
	}

	private int renderSettings(DrawContext context, Module m, int x, int y, int mouseX, int mouseY, int accent) {
		TextRenderer font = textRenderer;
		int left = x + 8;
		int right = x + PANEL_WIDTH - 5;

		context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
		String bindText = binding == m ? "Bind: ..." : "Bind: " + keyName(m.getKey());
		context.drawTextWithShadow(font, bindText, left, y + 3, MUTED);
		rows.add(new Row(RowType.BIND, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, null, 0));
		y += ROW_HEIGHT;

		for (Setting<?> setting : m.getSettings()) {
			if (!setting.isVisible()) continue;

			if (setting instanceof ColorSetting color) {
				context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
				context.drawTextWithShadow(font, color.getName(), left, y + 3, TEXT);
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
					context.drawTextWithShadow(font, bool.getName(), left, y + 3, TEXT);
					context.fill(right - 8, y + 3, right, y + ROW_HEIGHT - 2, 0xFF2A2430);
					if (bool.get()) context.fill(right - 7, y + 4, right - 1, y + ROW_HEIGHT - 3, accent);
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
					context.drawTextWithShadow(font, mode.getName(), left, y + 3, TEXT);
					String value = mode.getDisplayName();
					context.drawTextWithShadow(font, value, right - font.getWidth(value), y + 3, accent);
					yield new Row(RowType.ENUM, x, y, PANEL_WIDTH, ROW_HEIGHT, null, m, setting, 0);
				}
				case StringSetting text -> {
					context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT * 2, SETTING_BG);
					context.drawTextWithShadow(font, text.getName(), left, y + 3, TEXT);
					String shown = text.get() + (typing == text && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
					String clipped = font.trimToWidth(shown, right - left - 4);
					context.fill(left, y + ROW_HEIGHT, right, y + ROW_HEIGHT * 2 - 1, typing == text ? 0xFF3A3242 : 0xFF2A2430);
					context.drawTextWithShadow(font, clipped, left + 2, y + ROW_HEIGHT + 2, MUTED);
					yield new Row(RowType.STRING, x, y, PANEL_WIDTH, ROW_HEIGHT * 2, null, m, setting, 0);
				}
				default -> null;
			};
			if (row == null) continue;
			if (row.contains(mouseX, mouseY)) context.fill(row.x, row.y, row.x + row.width, row.y + row.height, 0x14FFFFFF);
			rows.add(row);
			y += row.height;
		}
		return y;
	}

	private void drawSlider(DrawContext context, int x, int y, String name, String value, double fraction, int accent, int indent) {
		TextRenderer font = textRenderer;
		int left = x + 8 + indent;
		int right = x + PANEL_WIDTH - 5;
		context.fill(x, y, x + PANEL_WIDTH, y + ROW_HEIGHT, SETTING_BG);
		int filled = left + (int) Math.round((right - left) * Math.max(0, Math.min(1, fraction)));
		context.fill(left, y + ROW_HEIGHT - 3, right, y + ROW_HEIGHT - 1, 0xFF2A2430);
		context.fill(left, y + ROW_HEIGHT - 3, filled, y + ROW_HEIGHT - 1, accent);
		context.drawTextWithShadow(font, name, left, y + 1, TEXT);
		context.drawTextWithShadow(font, value, right - font.getWidth(value), y + 1, MUTED);
	}

	private void renderTooltip(DrawContext context, int mouseX, int mouseY) {
		if (dragging != null || slider != null) return;
		for (Row row : rows) {
			if (!row.contains(mouseX, mouseY)) continue;
			String description = row.setting != null ? row.setting.getDescription()
				: row.type == RowType.MODULE ? row.module.getDescription()
				: row.type == RowType.BIND ? "Click, then press a key. Escape or Delete unbinds" : null;
			if (description == null || description.isEmpty()) return;
			int textWidth = textRenderer.getWidth(description);
			int x = Math.min(mouseX + 8, width - textWidth - 6);
			int y = mouseY + 10;
			context.fill(x - 3, y - 3, x + textWidth + 3, y + textRenderer.fontHeight + 2, 0xF0100C12);
			context.drawTextWithShadow(textRenderer, description, x, y, TEXT);
			return;
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();
		int button = click.button();
		typing = null;

		if (binding != null) {
			// Mouse buttons can't be bound; clicking anywhere cancels
			binding = null;
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
				case BOOL -> ((BoolSetting) row.setting).toggle();
				case ENUM -> ((EnumSetting<?>) row.setting).cycle(button != GLFW.GLFW_MOUSE_BUTTON_RIGHT);
				case STRING -> typing = (StringSetting) row.setting;
				case NUMBER, COLOR -> {
					if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
						slider = row;
						updateSlider(mouseX);
					}
				}
			}
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseReleased(Click click) {
		dragging = null;
		slider = null;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		for (Panel panel : panels.values()) panel.y += (int) (verticalAmount * 14);
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
	public boolean keyPressed(KeyInput input) {
		int key = input.key();
		if (binding != null) {
			binding.setKey(key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE ? -1 : key);
			binding = null;
			return true;
		}
		if (typing != null) {
			String value = typing.get();
			if (key == GLFW.GLFW_KEY_BACKSPACE && !value.isEmpty()) {
				typing.set(value.substring(0, value.length() - 1));
			} else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
				typing = null;
			}
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (typing != null && input.isValidChar()) {
			typing.set(typing.get() + input.asString());
			return true;
		}
		return super.charTyped(input);
	}

	private static String keyName(int key) {
		if (key < 0) return "None";
		return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
	}

	private static String trim(double value) {
		String text = String.format("%.2f", value);
		if (text.contains(".")) text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
		return text;
	}
}
