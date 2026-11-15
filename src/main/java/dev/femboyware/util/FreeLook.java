package dev.femboyware.util;

import dev.femboyware.FemboyWare;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Free look for modules that really turn your head (Scaffold's and Trap's Real Look): while a module
 * holds it, the player's actual rotation is the module's aim, so the server, other players and any
 * anti-cheat see you look where you build, while the camera follows the mouse on its own and WASD
 * walks where the camera points. When the module lets go, the head turns back to the camera before
 * the camera takes over again, so there's no snap.
 */
public final class FreeLook {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final int RETURN_PRIORITY = -1000;
	private static final float RETURN_SPEED = 40f;

	private static boolean active, held, returning;
	private static float yaw, pitch;
	private static CameraType previousCamera;

	private FreeLook() {
	}

	/**
	 * Keep free look on for this tick. Call every pre-motion while the module turns your head.
	 *
	 * @param thirdPerson switch to third person while it lasts
	 */
	public static void hold(boolean thirdPerson) {
		if (mc.player == null) return;
		if (!active) {
			active = true;
			yaw = mc.player.getYRot();
			pitch = mc.player.getXRot();
			previousCamera = null;
		}
		if (thirdPerson && previousCamera == null && mc.options.getCameraType().isFirstPerson()) {
			previousCamera = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		held = true;
		returning = false;
	}

	/** Start of the player tick, before the modules: lets go when nobody held it last tick. */
	public static void tick() {
		if (!active) return;
		if (mc.player == null) {
			end();
			return;
		}
		if (!held) returning = true;
		held = false;
		if (!returning) return;
		Rotation camera = new Rotation(yaw, pitch);
		Rotation player = new Rotation(mc.player.getYRot(), mc.player.getXRot());
		if (player.distanceTo(camera) < 1f) {
			// Lined up: the camera can follow the player again without a jump
			mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
			mc.player.setXRot(pitch);
			end();
			return;
		}
		FemboyWare.getRotations().request(camera, RETURN_PRIORITY, RETURN_SPEED, true);
	}

	private static void end() {
		active = held = returning = false;
		if (previousCamera != null) mc.options.setCameraType(previousCamera);
		previousCamera = null;
	}

	public static boolean active() {
		return active;
	}

	/** Mouse movement, in the units vanilla uses for {@code Entity.turn}. */
	public static void turn(double cursorDeltaX, double cursorDeltaY) {
		yaw += (float) cursorDeltaX * 0.15f;
		pitch = Mth.clamp(pitch + (float) cursorDeltaY * 0.15f, -90f, 90f);
	}

	/** Where the camera looks: the way the player means to go. */
	public static float yaw() {
		return active ? yaw : mc.player.getYRot();
	}

	public static float pitch() {
		return active ? pitch : mc.player.getXRot();
	}
}
