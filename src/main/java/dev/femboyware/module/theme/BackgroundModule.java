package dev.femboyware.module.theme;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;

/** What's behind the menu while it's open. Turn off for the defaults. */
public class BackgroundModule extends Module {
	public static final int DEFAULT_DIM = 0x30000000;

	private final BoolSetting blur = add(new BoolSetting("Blur", "Blur the world behind the menu", true));
	private final ColorSetting dim = add(new ColorSetting("Dim Color", "Laid over the background; alpha is how strong", DEFAULT_DIM));

	public BackgroundModule() {
		super("Background", "Blur and dimming behind the menu; off for the defaults", Category.THEME);
		setEnabled(true);
	}

	public boolean blur() {
		return blur.get();
	}

	public int dim() {
		return dim.get();
	}
}
