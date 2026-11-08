package dev.femboyware.module.utility;

import dev.femboyware.util.Mc;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import net.minecraft.client.gui.screens.DeathScreen;

/**
 * Respawns as soon as you die.
 * Ported from Meteor Client's AutoRespawn (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoRespawnModule extends Module {
	public AutoRespawnModule() {
		super("Auto Respawn", "Respawns automatically when you die", Category.AUTO);
	}

	@Override
	public void onTick() {
		if (!(Mc.screen() instanceof DeathScreen)) return;
		mc.player.respawn();
		Mc.setScreen(null);
	}
}
