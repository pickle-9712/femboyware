package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;

/**
 * Turns the camera with the mouse without turning your player, so you can look around while
 * running in a straight line. Applied by the camera and entity mixins.
 */
public class FreeLookModule extends Module {
	private final BoolSetting thirdPerson = add(new BoolSetting("Third Person", "Switch to third person while active", true));

	private float yaw;
	private float pitch;
	private Perspective previousPerspective;

	public FreeLookModule() {
		super("Free Look", "Look around without turning", Category.COSMETIC);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) {
			setEnabled(false);
			return;
		}
		yaw = mc.player.getYaw();
		pitch = mc.player.getPitch();
		previousPerspective = mc.options.getPerspective();
		if (thirdPerson.get() && previousPerspective.isFirstPerson()) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
	}

	@Override
	protected void onDisable() {
		if (previousPerspective != null) mc.options.setPerspective(previousPerspective);
		previousPerspective = null;
	}

	/** Mouse movement, in the same units vanilla uses for {@code changeLookDirection}. */
	public void changeLook(double cursorDeltaX, double cursorDeltaY) {
		yaw += (float) cursorDeltaX * 0.15f;
		pitch = MathHelper.clamp(pitch + (float) cursorDeltaY * 0.15f, -90f, 90f);
	}

	public float getYaw() {
		return yaw;
	}

	public float getPitch() {
		return pitch;
	}
}
