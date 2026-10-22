package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;

/**
 * Hides your name everywhere it's drawn, and can show every player with a default skin.
 * Ported from Meteor Client's NameProtect (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class NameProtectModule extends Module {
	private final BoolSetting hideName = add(new BoolSetting("Hide Name", "Replace your name in all text", true));
	private final StringSetting name = add(new StringSetting("Name", "What to show instead of your name", "femboy")).visibleWhen(hideName::get);
	private final BoolSetting skinProtect = add(new BoolSetting("Skin Protect", "Show every player with a default skin", true));

	public NameProtectModule() {
		super("Name Protect", "Hides your name and player skins", Category.MISC);
	}

	/** Called from TextVisitFactoryMixin for every string the game draws. */
	public String replaceName(String text) {
		if (!isEnabled() || !hideName.get() || text == null || mc.getSession() == null) return text;
		String username = mc.getSession().getUsername();
		return username.isEmpty() ? text : text.replace(username, name.get());
	}

	public boolean skinProtect() {
		return isEnabled() && skinProtect.get();
	}
}
