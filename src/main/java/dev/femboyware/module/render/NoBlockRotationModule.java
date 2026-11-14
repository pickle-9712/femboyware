package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.util.Mc;

/**
 * Stops blocks turning at random: the game gives grass, sand, stone and the like a random rotation
 * per block to break up the tiling; with this on they all line up, for a clean, uniform look.
 */
public class NoBlockRotationModule extends Module {
	/** Read on the chunk building threads, so a plain flag rather than a module lookup. */
	public static volatile boolean on;

	public NoBlockRotationModule() {
		super("No Block Rotation", "Blocks don't turn at random: grass, sand and stone all line up", Category.COSMETIC);
	}

	@Override
	protected void onEnable() {
		on = true;
		Mc.reloadChunks();
	}

	@Override
	protected void onDisable() {
		on = false;
		Mc.reloadChunks();
	}
}
