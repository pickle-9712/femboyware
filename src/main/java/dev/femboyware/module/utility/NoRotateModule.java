package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;

/**
 * Keeps your camera where it was when the server teleports you and tries to turn it. The work
 * happens in ClientPlayNetworkHandlerMixin around the position packet.
 * Ported from Meteor Client's NoRotate (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class NoRotateModule extends Module {
	private float savedYaw;
	private float savedPitch;

	public NoRotateModule() {
		super("No Rotate", "Stops the server from turning your camera", Category.PLAYER);
	}

	/** Before the server's position packet is applied. */
	public void beforeServerRotation() {
		if (!isEnabled() || mc.player == null) return;
		savedYaw = mc.player.getYaw();
		savedPitch = mc.player.getPitch();
	}

	/** After it: put the camera back. The tiny nudge makes the next movement packet carry our real look. */
	public void afterServerRotation() {
		if (!isEnabled() || mc.player == null) return;
		mc.player.setYaw(savedYaw + 0.000001f);
		mc.player.setPitch(savedPitch + 0.000001f);
	}
}
