package dev.femboyware.module.theme;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;

/** The menu's accent and text colours. Turn off for the defaults. */
public class ColorsModule extends Module {
	public static final int DEFAULT_ACCENT = 0xFFFF77C8;
	public static final int DEFAULT_TEXT = 0xFFFFFFFF;
	public static final int DEFAULT_MUTED = 0xFFAAA3B0;

	private final ColorSetting accent = add(new ColorSetting("Accent", "Enabled modules, selected tabs, switches and sliders", DEFAULT_ACCENT));
	private final ColorSetting text = add(new ColorSetting("Text", "Headers, enabled modules and setting names", DEFAULT_TEXT));
	private final ColorSetting muted = add(new ColorSetting("Muted Text", "Disabled modules, other tabs and values", DEFAULT_MUTED));

	public ColorsModule() {
		super("Colors", "Accent and text colours of the menu; off for the defaults", Category.THEME);
		setEnabled(true);
	}

	public int accent() {
		return accent.get() | 0xFF000000;
	}

	public int text() {
		return text.get() | 0xFF000000;
	}

	public int muted() {
		return muted.get() | 0xFF000000;
	}
}
