package dev.femboyware.module.theme;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;

/** What's behind the menu while it's open. Turn off for the defaults. */
public class BackgroundModule extends Module {
	private final BoolSetting blur = add(new BoolSetting("Blur", "Blur the world behind the menu", true));

	public BackgroundModule() {
		super("Background", "Blur behind the menu; off for the defaults", Category.THEME);
		setEnabled(true);
	}

	public boolean blur() {
		return blur.get();
	}
}
