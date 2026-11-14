package dev.femboyware.module.theme;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;

/** The light on the glass: the bright rim round each edge and the sheen across the top. Turn off for the defaults. */
public class ShineModule extends Module {
	private final NumberSetting rim = add(new NumberSetting("Rim Strength", "How bright the edge highlight is", 1, 0, 2, 0.05));
	private final NumberSetting sheen = add(new NumberSetting("Sheen Strength", "How glossy the top of the glass is", 1, 0, 2, 0.05));

	public ShineModule() {
		super("Shine", "How strong the rim and sheen on the glass are; off for the defaults", Category.THEME);
		setEnabled(true);
	}

	public float rim() {
		return rim.getFloat();
	}

	public float sheen() {
		return sheen.getFloat();
	}
}
