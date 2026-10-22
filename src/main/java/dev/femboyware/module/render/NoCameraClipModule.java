package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;

/** Lets the third person camera pass through blocks and sets its distance. Applied by the camera mixin. */
public class NoCameraClipModule extends Module {
	private final NumberSetting distance = add(new NumberSetting("Distance", "Third person camera distance", 4, 1, 20, 0.5));

	public NoCameraClipModule() {
		super("No Camera Clip", "Third person camera goes through walls", Category.COSMETIC);
	}

	public float getDistance() {
		return distance.getFloat();
	}
}
