package dev.femboyware.module.theme;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.hud.HudColors;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import java.util.function.BooleanSupplier;

/**
 * Every colour of the ClickGUI and the HUD in one place, on two pages. Turn it off for the
 * defaults. A Wallpaper preset swaps in its own palette for the main ones while it's on, and these
 * come back when it's off.
 */
public class ColorsModule extends Module {
	public enum Page {
		MENU,
		HUD
	}

	public static final int DEFAULT_ACCENT = 0xFFFF77C8;
	public static final int DEFAULT_TEXT = 0xFFFFFFFF;
	public static final int DEFAULT_MUTED = 0xFFAAA3B0;
	public static final int DEFAULT_PANEL = 0x70140C18;
	public static final int DEFAULT_CONTENT = 0x22000000;
	public static final int DEFAULT_SURFACE = 0x14FFFFFF;
	public static final int DEFAULT_SURFACE_HOVER = 0x24FFFFFF;
	public static final int DEFAULT_SWITCH_OFF = 0x38FFFFFF;
	public static final int DEFAULT_KNOB = 0xFFFFFFFF;
	public static final int DEFAULT_TRACK = 0x40FFFFFF;
	public static final int DEFAULT_DIVIDER = 0x20FFFFFF;
	public static final int DEFAULT_SETTING_ROW = 0x1C000000;
	public static final int DEFAULT_TOOLTIP = 0xC0140C18;
	public static final int DEFAULT_HIGHLIGHT = 0xFFFFFFFF;
	public static final int DEFAULT_DIM = 0x30000000;
	public static final int DEFAULT_ONLINE = 0xFF3DDC84;

	public static final int DEFAULT_HUD_MUTED = 0xFFAAA3B0;
	public static final int DEFAULT_HUD_BACKGROUND = 0x50140C18;
	public static final int DEFAULT_HEALTH_FULL = 0xFF50FF50;
	public static final int DEFAULT_HEALTH_LOW = 0xFFFF3050;
	public static final int DEFAULT_ABSORPTION = 0xFFFFC94A;
	public static final int DEFAULT_GOOD = 0xFF80FF90;
	public static final int DEFAULT_OKAY = 0xFFFFD060;
	public static final int DEFAULT_LOW = 0xFFFF6060;
	public static final int DEFAULT_PRESSED_TEXT = 0xFF1A0A18;
	public static final int DEFAULT_STAFF = 0xFFFF5A5A;
	public static final int DEFAULT_ISLAND_PILL = 0xB0141028;
	public static final int DEFAULT_ISLAND_HIGHLIGHT = 0xFF7A4BD8;
	public static final int DEFAULT_ISLAND_TEXT = 0xFFF2EEFF;
	public static final int DEFAULT_ISLAND_BARS = 0xFFC9A6FF;
	public static final int DEFAULT_ISLAND_TIME = 0xFFFFFFFF;

	private final EnumSetting<Page> page = add(new EnumSetting<>("Page", "The ClickGUI's colours or the HUD's", Page.MENU));

	// ---------------------------------------------------------------- the ClickGUI
	private final ColorSetting accent = menu("Accent", "Enabled modules, selected tabs, switches and sliders", DEFAULT_ACCENT);
	private final ColorSetting text = menu("Text", "Headers, enabled modules and setting names", DEFAULT_TEXT);
	private final ColorSetting muted = menu("Muted Text", "Disabled modules, other tabs and values", DEFAULT_MUTED);
	private final ColorSetting panel = menu("Panel Color", "Tint of the menu's glass panels; alpha is how see-through they are", DEFAULT_PANEL);
	private final ColorSetting content = menu("Content Color", "The darker area behind the cards and settings", DEFAULT_CONTENT);
	private final ColorSetting surface = menu("Card Color", "Cards, chips, the search bar and buttons", DEFAULT_SURFACE);
	private final ColorSetting surfaceHover = menu("Card Hover Color", "Cards, chips and buttons under the mouse", DEFAULT_SURFACE_HOVER);
	private final ColorSetting switchOff = menu("Switch Off Color", "A switch that's off", DEFAULT_SWITCH_OFF);
	private final ColorSetting knob = menu("Switch Knob Color", "The knob on every switch", DEFAULT_KNOB);
	private final ColorSetting track = menu("Slider Track Color", "The empty part of sliders", DEFAULT_TRACK);
	private final ColorSetting divider = menu("Divider Color", "Lines between sections", DEFAULT_DIVIDER);
	private final ColorSetting settingRow = menu("Setting Row Color", "Behind each row on a module's settings page", DEFAULT_SETTING_ROW);
	private final ColorSetting tooltip = menu("Tooltip Color", "Tint of the tooltip bubbles", DEFAULT_TOOLTIP);
	private final ColorSetting highlight = menu("Highlight Color", "Colour of the glass's rim and sheen", DEFAULT_HIGHLIGHT);
	private final ColorSetting dim = menu("Dim Color", "Laid over the world behind the menu; alpha is how strong", DEFAULT_DIM);
	private final ColorSetting online = menu("Online Color", "The dot next to your name", DEFAULT_ONLINE);

	// ---------------------------------------------------------------- the HUD
	private final BoolSetting matchMenu = add(new BoolSetting("Match Menu", "HUD text, accent and muted text use the menu's", true)).visibleWhen(() -> page.is(Page.HUD));
	private final ColorSetting hudText = hud("HUD Text", "Main HUD text", DEFAULT_TEXT, () -> !matchMenu.get());
	private final ColorSetting hudAccent = hud("HUD Accent", "HUD highlights", DEFAULT_ACCENT, () -> !matchMenu.get());
	private final ColorSetting hudMuted = hud("HUD Muted", "Secondary HUD text", DEFAULT_HUD_MUTED, () -> !matchMenu.get());
	private final ColorSetting hudBackground = hud("HUD Background", "Tint behind HUD elements and the module list", DEFAULT_HUD_BACKGROUND, null);
	private final EnumSetting<HudColors> listColors = add(new EnumSetting<>("Module List Colors", "How the module list's names are coloured", HudColors.PASTEL)).visibleWhen(() -> page.is(Page.HUD));
	private final ColorSetting listColor = hud("Module List Color", "Colour for Static", DEFAULT_ACCENT, () -> listColors.is(HudColors.STATIC));
	private final EnumSetting<HudColors> watermarkColors = add(new EnumSetting<>("Watermark Colors", "How the watermark's letters are coloured", HudColors.PASTEL)).visibleWhen(() -> page.is(Page.HUD));
	private final ColorSetting watermarkColor = hud("Watermark Color", "Colour for Static", DEFAULT_ACCENT, () -> watermarkColors.is(HudColors.STATIC));
	private final ColorSetting healthFull = hud("Health Full", "Health bars at full health", DEFAULT_HEALTH_FULL, null);
	private final ColorSetting healthLow = hud("Health Low", "Health bars when nearly dead", DEFAULT_HEALTH_LOW, null);
	private final ColorSetting absorption = hud("Absorption", "Extra hearts on health bars", DEFAULT_ABSORPTION, null);
	private final ColorSetting good = hud("Durability Good", "Armour above 60%", DEFAULT_GOOD, null);
	private final ColorSetting okay = hud("Durability Okay", "Armour above 30%", DEFAULT_OKAY, null);
	private final ColorSetting low = hud("Warning", "Low durability, no totems, effects running out, no word from the guard", DEFAULT_LOW, null);
	private final ColorSetting pressedText = hud("Pressed Key Text", "Keystrokes text on a held key", DEFAULT_PRESSED_TEXT, null);
	private final ColorSetting staff = hud("Staff Color", "Rank tags in the staff list", DEFAULT_STAFF, null);
	private final ColorSetting islandPill = hud("Island Pill", "Tint of the island", DEFAULT_ISLAND_PILL, null);
	private final ColorSetting islandHighlight = hud("Island Highlight", "Behind the word being sung", DEFAULT_ISLAND_HIGHLIGHT, null);
	private final ColorSetting islandText = hud("Island Text", "Words in the island", DEFAULT_ISLAND_TEXT, null);
	private final ColorSetting islandBars = hud("Island Equalizer", "The moving bars", DEFAULT_ISLAND_BARS, null);
	private final ColorSetting islandTime = hud("Island Time", "The time and ping", DEFAULT_ISLAND_TIME, null);

	public ColorsModule() {
		super("Colors", "Every colour of the menu and the HUD; off for the defaults", Category.THEME);
		setEnabled(true);
	}

	private ColorSetting menu(String name, String description, int color) {
		return add(new ColorSetting(name, description, color)).visibleWhen(() -> page.is(Page.MENU));
	}

	private ColorSetting hud(String name, String description, int color, BooleanSupplier also) {
		return add(new ColorSetting(name, description, color)).visibleWhen(() -> page.is(Page.HUD) && (also == null || also.getAsBoolean()));
	}

	public int accent() { return accent.get() | 0xFF000000; }
	public int text() { return text.get() | 0xFF000000; }
	public int muted() { return muted.get() | 0xFF000000; }
	public int panel() { return panel.get(); }
	public int content() { return content.get(); }
	public int surface() { return surface.get(); }
	public int surfaceHover() { return surfaceHover.get(); }
	public int switchOff() { return switchOff.get(); }
	public int knob() { return knob.get(); }
	public int track() { return track.get(); }
	public int divider() { return divider.get(); }
	public int settingRow() { return settingRow.get(); }
	public int tooltip() { return tooltip.get(); }
	public int highlight() { return highlight.get(); }
	public int dim() { return dim.get(); }
	public int online() { return online.get(); }

	public boolean matchMenu() { return matchMenu.get(); }
	public int hudText() { return hudText.get(); }
	public int hudAccent() { return hudAccent.get(); }
	public int hudMuted() { return hudMuted.get(); }
	public int hudBackground() { return hudBackground.get(); }
	public HudColors listColors() { return listColors.get(); }
	public int listColor() { return listColor.get(); }
	public HudColors watermarkColors() { return watermarkColors.get(); }
	public int watermarkColor() { return watermarkColor.get(); }
	public int healthFull() { return healthFull.get(); }
	public int healthLow() { return healthLow.get(); }
	public int absorption() { return absorption.get(); }
	public int good() { return good.get(); }
	public int okay() { return okay.get(); }
	public int low() { return low.get(); }
	public int pressedText() { return pressedText.get(); }
	public int staff() { return staff.get(); }
	public int islandPill() { return islandPill.get(); }
	public int islandHighlight() { return islandHighlight.get(); }
	public int islandText() { return islandText.get(); }
	public int islandBars() { return islandBars.get(); }
	public int islandTime() { return islandTime.get(); }
}
