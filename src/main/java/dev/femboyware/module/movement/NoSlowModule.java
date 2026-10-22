package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;

/** Removes slowdowns from using items and from slow blocks. Applied by mixins. */
public class NoSlowModule extends Module {
	private final BoolSetting items = add(new BoolSetting("Items", "Full speed while eating, blocking or drawing a bow", true));
	private final BoolSetting blocks = add(new BoolSetting("Blocks", "Full speed on soul sand and honey", true));

	public NoSlowModule() {
		super("No Slow", "Removes item and block slowdowns", Category.RAGE);
	}

	public boolean items() {
		return isEnabled() && items.get();
	}

	public boolean blocks() {
		return isEnabled() && blocks.get();
	}
}
