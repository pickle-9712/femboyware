package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;

/** Tints your first person arms, optionally with an enchantment glint. Applied by the player renderer mixin. */
public class HandChamsModule extends Module {
	private final ColorSetting color = add(new ColorSetting("Color", "Arm tint, alpha makes it see-through", 0xA0FF77C8));
	private final BoolSetting glint = add(new BoolSetting("Glint", "Add the enchantment shimmer", true));

	public HandChamsModule() {
		super("Hand Chams", "Colours your hands", Category.COSMETIC);
	}

	public int getColor() {
		return color.get();
	}

	public boolean hasGlint() {
		return glint.get();
	}
}
