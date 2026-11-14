package dev.femboyware.module.theme;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;

/** Shape and shadow of the liquid glass (its colours are in Colors). Turn off for the defaults. */
public class GlassModule extends Module {
	public static final int DEFAULT_ROUNDNESS = 6;

	private final NumberSetting roundness = add(new NumberSetting("Roundness", "How rounded the panel corners are", DEFAULT_ROUNDNESS, 0, 12, 1));
	private final BoolSetting shadow = add(new BoolSetting("Shadow", "Soft shadow under the panels", true));

	public GlassModule() {
		super("Glass", "Roundness and shadow of the glass; off for the defaults", Category.THEME);
		setEnabled(true);
	}

	public int roundness() {
		return roundness.getInt();
	}

	public boolean shadow() {
		return shadow.get();
	}
}
