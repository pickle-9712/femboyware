package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;

/** Lights everything up as if you had night vision. Applied by the lightmap mixin. */
public class FullBrightModule extends Module {
	public FullBrightModule() {
		super("Full Bright", "See in the dark", Category.COSMETIC);
	}
}
