package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;

/** Changes your arm swing speed and removes the re-equip bob. Applied by mixins. */
public class SwingAnimationsModule extends Module {
	private final NumberSetting duration = add(new NumberSetting("Duration", "Ticks per swing (vanilla is 6)", 10, 1, 30, 1));
	private final BoolSetting noEquip = add(new BoolSetting("No Equip", "Hold items steady after switching or attacking", true));

	public SwingAnimationsModule() {
		super("Swing Animations", "Custom arm swing animations", Category.COSMETIC);
	}

	public int getDuration() {
		return duration.getInt();
	}

	public boolean noEquip() {
		return isEnabled() && noEquip.get();
	}
}
