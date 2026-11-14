package dev.femboyware.module.render;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.Minecraft;

/**
 * Your own field of view, past the 30 to 110 the options allow. Sprinting, speed and the bow still
 * widen and narrow it the usual way unless Static is on.
 */
public class CustomFovModule extends Module {
	private final NumberSetting fov = add(new NumberSetting("FOV", "Field of view in degrees", 100, 10, 170, 1));
	private final BoolSetting staticFov = add(new BoolSetting("Static", "Ignore sprinting, speed and bow zoom", false));

	public CustomFovModule() {
		super("Custom FOV", "Changes your field of view", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return fov.getInt() + "°";
	}

	/** Called from the camera's world FOV (not the hand's): swaps the options' FOV for ours. */
	public static float apply(float worldFov) {
		if (FemboyWare.getModules() == null) return worldFov;
		CustomFovModule module = FemboyWare.getModules().get(CustomFovModule.class);
		if (!module.isEnabled()) return worldFov;
		if (module.staticFov.get()) return module.fov.getFloat();
		int base = Minecraft.getInstance().options.fov().get();
		return base <= 0 ? worldFov : worldFov * module.fov.getFloat() / base;
	}
}
