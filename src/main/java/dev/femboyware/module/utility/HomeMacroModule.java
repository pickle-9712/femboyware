package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;

/**
 * Moves a home to where you're standing: deletes it, then sets it again a moment later. Bind it to
 * a key; it turns itself off when it's done.
 */
public class HomeMacroModule extends Module {
	private final NumberSetting home = add(new NumberSetting("Home", "Which numbered home", 1, 1, 20, 1));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Seconds between deleting and setting it", 1, 0.2, 5, 0.1));
	private final StringSetting delCommand = add(new StringSetting("Delete Command", "The command to delete it; {n} is the number", "delhome {n}"));
	private final StringSetting setCommand = add(new StringSetting("Set Command", "The command to set it; {n} is the number", "sethome {n}"));

	private int ticks;

	public HomeMacroModule() {
		super("Home Macro", "Delete a numbered home and set it again where you are", Category.MISC);
	}

	@Override
	protected void onEnable() {
		if (mc.getConnection() == null) {
			setEnabled(false);
			return;
		}
		mc.getConnection().sendCommand(delCommand.get().replace("{n}", Integer.toString(home.getInt())).replaceFirst("^/", ""));
		ticks = (int) Math.round(delay.get() * 20);
	}

	@Override
	public void onTick() {
		if (mc.getConnection() == null || --ticks > 0) return;
		mc.getConnection().sendCommand(setCommand.get().replace("{n}", Integer.toString(home.getInt())).replaceFirst("^/", ""));
		setEnabled(false);
	}
}
