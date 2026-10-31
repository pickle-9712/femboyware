package dev.femboyware.module.theme;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;

/** Tint, shape and shadow of the liquid glass. A colour's alpha is how strong its tint is. Turn off for the defaults. */
public class GlassModule extends Module {
	public static final int DEFAULT_PANEL = 0x70140C18;
	public static final int DEFAULT_LIST = 0x50140C18;
	public static final int DEFAULT_TOOLTIP = 0xC0140C18;
	public static final int DEFAULT_ROUNDNESS = 6;

	private final ColorSetting panel = add(new ColorSetting("Panel Color", "Tint of the menu's glass panels; alpha is how see-through they are", DEFAULT_PANEL));
	private final ColorSetting list = add(new ColorSetting("List Color", "Tint of the boxes in the module list, top right", DEFAULT_LIST));
	private final ColorSetting tooltip = add(new ColorSetting("Tooltip Color", "Tint of the tooltip bubbles", DEFAULT_TOOLTIP));
	private final NumberSetting roundness = add(new NumberSetting("Roundness", "How rounded the panel corners are", DEFAULT_ROUNDNESS, 0, 12, 1));
	private final BoolSetting shadow = add(new BoolSetting("Shadow", "Soft shadow under the panels", true));

	public GlassModule() {
		super("Glass", "Tint, roundness and shadow of the glass; off for the defaults", Category.THEME);
		setEnabled(true);
	}

	public int panel() {
		return panel.get();
	}

	public int list() {
		return list.get();
	}

	public int tooltip() {
		return tooltip.get();
	}

	public int roundness() {
		return roundness.getInt();
	}

	public boolean shadow() {
		return shadow.get();
	}
}
