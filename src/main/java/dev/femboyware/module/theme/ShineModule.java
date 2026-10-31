package dev.femboyware.module.theme;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;

/** The light on the glass: the bright rim round each edge and the sheen across the top. Turn off for the defaults. */
public class ShineModule extends Module {
	public static final int DEFAULT_HIGHLIGHT = 0xFFFFFFFF;

	private final ColorSetting highlight = add(new ColorSetting("Highlight Color", "Colour of the rim and sheen", DEFAULT_HIGHLIGHT));
	private final NumberSetting rim = add(new NumberSetting("Rim Strength", "How bright the edge highlight is", 1, 0, 2, 0.05));
	private final NumberSetting sheen = add(new NumberSetting("Sheen Strength", "How glossy the top of the glass is", 1, 0, 2, 0.05));

	public ShineModule() {
		super("Shine", "Rim and sheen highlights on the glass; off for the defaults", Category.THEME);
		setEnabled(true);
	}

	public int highlight() {
		return highlight.get() & 0x00FFFFFF;
	}

	public float rim() {
		return rim.getFloat();
	}

	public float sheen() {
		return sheen.getFloat();
	}
}
