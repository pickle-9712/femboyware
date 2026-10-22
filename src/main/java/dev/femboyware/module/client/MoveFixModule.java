package dev.femboyware.module.client;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;

/**
 * While enabled, movement is computed with the server-side yaw during silent rotations. Without it,
 * anti-cheats that simulate movement (Grim and similar) see impossible motion and set you back,
 * which feels like freezing in place.
 */
public class MoveFixModule extends Module {
	public MoveFixModule() {
		super("Move Fix", "Moves relative to your server rotation so silent rotations don't cause setbacks", Category.BASIC);
		setEnabled(true);
	}
}
