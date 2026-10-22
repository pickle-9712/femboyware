package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;

/** Restyles end crystals: size, spin speed and a coloured outline. Applied by the crystal renderer mixin. */
public class CrystalChamsModule extends Module {
	private final NumberSetting scale = add(new NumberSetting("Scale", "Crystal size", 1, 0.1, 3, 0.05));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Spin and bounce speed", 1, 0, 5, 0.05));
	private final BoolSetting outline = add(new BoolSetting("Outline", "Draw a glowing outline", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Outline colour", 0xFFFF77C8)).visibleWhen(outline::get);

	public CrystalChamsModule() {
		super("Crystal Chams", "Changes how end crystals look", Category.COSMETIC);
	}

	public float getScale() {
		return isEnabled() ? scale.getFloat() : 1f;
	}

	public float modifyAge(float age) {
		return isEnabled() ? age * speed.getFloat() : age;
	}

	/** Outline colour, or {@code original} when disabled. */
	public int modifyOutline(int original) {
		return isEnabled() && outline.get() ? color.get() | 0xFF000000 : original;
	}
}
