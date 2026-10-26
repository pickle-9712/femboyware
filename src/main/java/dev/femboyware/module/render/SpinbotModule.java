package dev.femboyware.module.render;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Rotation;

import java.util.Random;

/**
 * Spins your player for everyone else to see. By default only the rotation sent to the server
 * spins, so your camera and walking stay normal; Spin Camera turns your own view too.
 * Combat modules rotate at a higher priority, so they take over while they need to aim.
 */
public class SpinbotModule extends Module {
	/** Below every combat module, so aiming always wins over spinning. */
	private static final int ROTATION_PRIORITY = 0;

	public enum PitchMode { NORMAL, DOWN, UP, JITTER }

	private final NumberSetting speed = add(new NumberSetting("Speed", "Degrees turned per tick", 30, 1, 180, 1));
	private final BoolSetting reverse = add(new BoolSetting("Reverse", "Spin the other way", false));
	private final EnumSetting<PitchMode> pitchMode = add(new EnumSetting<>("Pitch", "Normal keeps your own pitch, Down and Up look straight down or up, Jitter bobs at random", PitchMode.NORMAL));
	private final BoolSetting spinCamera = add(new BoolSetting("Spin Camera", "Turn your own camera too, not just what others see", false));

	private final Random random = new Random();
	private float yaw;

	public SpinbotModule() {
		super("Spinbot", "Spins your player's head", Category.COSMETIC);
	}

	@Override
	protected void onEnable() {
		if (!nullCheck()) yaw = mc.player.getYaw();
	}

	@Override
	public void onPreMotion() {
		yaw += reverse.get() ? -speed.getFloat() : speed.getFloat();
		yaw %= 360f;
		float pitch = switch (pitchMode.get()) {
			case NORMAL -> mc.player.getPitch();
			case DOWN -> 90f;
			case UP -> -90f;
			case JITTER -> random.nextFloat() * 180f - 90f;
		};
		// Just for show: Move Fix stays out of it so walking isn't steered by the spin
		FemboyWare.getRotations().request(new Rotation(yaw, pitch), ROTATION_PRIORITY, 180, spinCamera.get(), false);
	}
}
