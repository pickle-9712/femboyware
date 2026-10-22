package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;

/**
 * Adds to how far you can reach blocks and entities. Servers check reach too, so on most of them
 * only small amounts work, and Paper rejects all of it.
 * Ported from Meteor Client's Reach (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class ReachModule extends Module {
	private final NumberSetting blockReach = add(new NumberSetting("Block Reach", "Blocks added to block reach", 0.5, 0, 5, 0.1));
	private final NumberSetting entityReach = add(new NumberSetting("Entity Reach", "Blocks added to entity reach", 0.5, 0, 5, 0.1));

	public ReachModule() {
		super("Reach", "Lets you reach further", Category.PLAYER);
	}

	public double blockReach() {
		return isEnabled() ? blockReach.get() : 0;
	}

	public double entityReach() {
		return isEnabled() ? entityReach.get() : 0;
	}
}
