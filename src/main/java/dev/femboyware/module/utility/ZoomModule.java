package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;

/** Zooms the camera in while enabled. The field of view is divided by the game renderer mixin. */
public class ZoomModule extends Module {
	private final NumberSetting factor = add(new NumberSetting("Factor", "How many times to zoom in", 4, 1, 20, 0.5));
	private final BoolSetting smoothCamera = add(new BoolSetting("Smooth Camera", "Use cinematic camera while zoomed", true));

	private boolean wasSmooth;

	public ZoomModule() {
		super("Zoom", "Zooms in", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		wasSmooth = mc.options.smoothCameraEnabled;
		if (smoothCamera.get()) mc.options.smoothCameraEnabled = true;
	}

	@Override
	protected void onDisable() {
		mc.options.smoothCameraEnabled = wasSmooth;
	}

	public float modifyFov(float fov) {
		return isEnabled() ? fov / factor.getFloat() : fov;
	}
}
