package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;

/** Stretched resolution: renders the world with a different aspect ratio. Applied by the game renderer mixin. */
public class AspectModule extends Module {
	private final NumberSetting ratio = add(new NumberSetting("Ratio", "Aspect ratio to render with (1.33 is 4:3)", 1.33, 0.5, 3, 0.01));

	public AspectModule() {
		super("Aspect", "Changes the aspect ratio of the world", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return String.valueOf(ratio.get());
	}

	public float modifyRatio(float original) {
		return isEnabled() ? ratio.getFloat() : original;
	}
}
