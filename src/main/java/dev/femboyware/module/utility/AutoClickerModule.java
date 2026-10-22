package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;

/**
 * Clicks or holds the attack and use buttons for you.
 * Ported from Meteor Client's AutoClicker (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoClickerModule extends Module {
	public enum Mode { DISABLED, HOLD, PRESS }

	private final BoolSetting inScreens = add(new BoolSetting("In Screens", "Keep clicking while a screen is open", true));
	private final EnumSetting<Mode> leftMode = add(new EnumSetting<>("Left Click", "Hold keeps the button down, Press clicks on a timer", Mode.PRESS));
	private final NumberSetting leftDelay = add(new NumberSetting("Left Delay", "Ticks between left clicks", 2, 0, 60, 1)).visibleWhen(() -> leftMode.is(Mode.PRESS));
	private final EnumSetting<Mode> rightMode = add(new EnumSetting<>("Right Click", "Hold keeps the button down, Press clicks on a timer", Mode.PRESS));
	private final NumberSetting rightDelay = add(new NumberSetting("Right Delay", "Ticks between right clicks", 2, 0, 60, 1)).visibleWhen(() -> rightMode.is(Mode.PRESS));

	private int leftTimer;
	private int rightTimer;

	public AutoClickerModule() {
		super("Auto Clicker", "Clicks for you", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		leftTimer = 0;
		rightTimer = 0;
		release();
	}

	@Override
	protected void onDisable() {
		release();
	}

	@Override
	public void onTick() {
		if (!inScreens.get() && mc.currentScreen != null) return;
		leftTimer = click(mc.options.attackKey, leftMode.get(), leftDelay.getInt(), leftTimer);
		rightTimer = click(mc.options.useKey, rightMode.get(), rightDelay.getInt(), rightTimer);
	}

	/** Runs one key for this tick and returns its updated timer. Presses queue a click vanilla handles this tick. */
	private static int click(KeyBinding key, Mode mode, int delay, int timer) {
		switch (mode) {
			case DISABLED -> {
				return timer;
			}
			case HOLD -> {
				key.setPressed(true);
				return timer;
			}
			default -> {
				if (++timer <= delay) return timer;
				KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(key));
				return 0;
			}
		}
	}

	private void release() {
		if (nullCheck()) return;
		mc.options.attackKey.setPressed(false);
		mc.options.useKey.setPressed(false);
	}
}
