package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;

/** Resizes or hides the totem that flies at your screen when you pop. Applied by the overlay mixin. */
public class TotemAnimationModule extends Module {
	private final BoolSetting hide = add(new BoolSetting("Hide", "Don't show the animation at all", false));
	private final NumberSetting scale = add(new NumberSetting("Scale", "Size of the floating totem", 0.6, 0.1, 2, 0.05)).visibleWhen(() -> !hide.get());

	public TotemAnimationModule() {
		super("Totem Animation", "Changes the totem pop animation", Category.COSMETIC);
	}

	public boolean hide() {
		return isEnabled() && hide.get();
	}

	public float getScale() {
		return isEnabled() ? scale.getFloat() : 1f;
	}
}
