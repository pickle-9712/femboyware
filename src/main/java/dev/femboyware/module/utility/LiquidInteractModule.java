package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;

/**
 * Lets your crosshair target water and lava, so you can place blocks on them. The raycast change
 * is in ClientPlayerEntityMixin.
 * Ported from Meteor Client's LiquidInteract (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class LiquidInteractModule extends Module {
	public LiquidInteractModule() {
		super("Liquid Interact", "Lets you target and place on liquids", Category.PLAYER);
	}
}
