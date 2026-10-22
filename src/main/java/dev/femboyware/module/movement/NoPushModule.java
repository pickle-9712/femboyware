package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;

/** Stops entities, water currents and blocks from pushing you around. Applied by mixins. */
public class NoPushModule extends Module {
	private final BoolSetting entities = add(new BoolSetting("Entities", "Don't get pushed by other entities", true));
	private final BoolSetting liquids = add(new BoolSetting("Liquids", "Don't get pushed by flowing water and lava", true));
	private final BoolSetting blocks = add(new BoolSetting("Blocks", "Don't get pushed out of blocks you're inside", true));

	public NoPushModule() {
		super("No Push", "Prevents being pushed", Category.BASIC);
	}

	public boolean entities() {
		return isEnabled() && entities.get();
	}

	public boolean liquids() {
		return isEnabled() && liquids.get();
	}

	public boolean blocks() {
		return isEnabled() && blocks.get();
	}
}
