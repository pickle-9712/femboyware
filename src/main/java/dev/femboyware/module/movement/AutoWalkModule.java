package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import net.minecraft.client.option.KeyBinding;

/**
 * Holds a movement key for you.
 * Ported from Meteor Client's AutoWalk (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 * Meteor's Smart mode needs Baritone and isn't included.
 */
public class AutoWalkModule extends Module {
	public enum Direction { FORWARDS, BACKWARDS, LEFT, RIGHT }

	private final EnumSetting<Direction> direction = add(new EnumSetting<>("Direction", "Which way to walk", Direction.FORWARDS));
	private final BoolSetting disableOnY = add(new BoolSetting("Disable On Y", "Turn off if you move up or down", false));

	private KeyBinding held;
	private double lastY;

	public AutoWalkModule() {
		super("Auto Walk", "Walks for you", Category.BASIC);
	}

	@Override
	protected void onEnable() {
		if (!nullCheck()) lastY = mc.player.getY();
	}

	@Override
	protected void onDisable() {
		release();
	}

	@Override
	public void onTick() {
		if (disableOnY.get() && mc.player.getY() != lastY) {
			setEnabled(false);
			return;
		}
		lastY = mc.player.getY();

		KeyBinding key = switch (direction.get()) {
			case FORWARDS -> mc.options.forwardKey;
			case BACKWARDS -> mc.options.backKey;
			case LEFT -> mc.options.leftKey;
			case RIGHT -> mc.options.rightKey;
		};
		if (key != held) release();
		key.setPressed(true);
		held = key;
	}

	private void release() {
		if (held != null) held.setPressed(false);
		held = null;
	}
}
