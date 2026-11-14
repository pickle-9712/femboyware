package dev.femboyware.module.client;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns femboyware into a larp and visuals client: every cheat module (combat, movement, ESP, and
 * the automation and world tools) is switched off and can't be turned back on, and with Hide
 * Cheats their tabs disappear from the ClickGUI. Larp, cosmetics, effects, skies, themes and HUD
 * all stay. Whatever cheats were on come back when you turn this off.
 */
public class LarpOnlyModule extends Module {
	/** Visual tabs that are still cheats: these by name. */
	private static final Set<String> CHEATY_VISUALS = Set.of("Spinbot", "Trajectories", "Full Bright");
	/** Utility modules that are only for show or for you, so they stay. */
	private static final Set<String> HARMLESS_UTILITY = Set.of("Waypoints", "Zoom", "No Render", "Fake Player");

	private final BoolSetting hide = add(new BoolSetting("Hide Cheats", "Hide the cheat modules and their tabs from the ClickGUI too", true));

	/**
	 * Cheats that were on (or got turned on from a config or keybind) while this was on, to bring
	 * back after: saved with the config, so they come back even after a restart.
	 */
	private final StringSetting paused = add(new StringSetting("Paused", "Cheats to turn back on afterwards", "")).visibleWhen(() -> false);

	private Set<String> pausedNames() {
		Set<String> names = new LinkedHashSet<>();
		for (String name : paused.get().split(",")) if (!name.isBlank()) names.add(name);
		return names;
	}

	private void pause(String name) {
		Set<String> names = pausedNames();
		if (names.add(name)) paused.set(String.join(",", names));
	}

	public LarpOnlyModule() {
		super("Larp Only", "Turns off every cheat and keeps only larp, cosmetics and effects", Category.THEME);
	}

	@Override
	public String getInfo() {
		int off = pausedNames().size();
		return off == 0 ? null : off + " off";
	}

	/** Whether {@code module} counts as a cheat. */
	public static boolean isCheat(Module module) {
		if (module instanceof LarpOnlyModule) return false;
		Category category = module.getCategory();
		return switch (category.getGroup()) {
			case PVP, MOVEMENT -> true;
			case VISUAL -> category == Category.ESP || CHEATY_VISUALS.contains(module.getName());
			case UTILITY -> category != Category.MISC && !HARMLESS_UTILITY.contains(module.getName());
			case CUSTOMIZE, HUD -> false;
		};
	}

	private static LarpOnlyModule get() {
		if (FemboyWare.getModules() == null) return null;
		try {
			return FemboyWare.getModules().get(LarpOnlyModule.class);
		} catch (IllegalArgumentException notYet) {
			// Modules turning on while the list is still being made, before this one is in it
			return null;
		}
	}

	/**
	 * Called from Module.setEnabled before a module turns on: false stops it, and it's kept to
	 * turn on again once Larp Only's off.
	 */
	public static boolean allows(Module module) {
		LarpOnlyModule larpOnly = get();
		if (larpOnly == null || !larpOnly.isEnabled() || !isCheat(module)) return true;
		larpOnly.pause(module.getName());
		return false;
	}

	/** Whether the ClickGUI should leave {@code module} out. */
	public static boolean hides(Module module) {
		LarpOnlyModule larpOnly = get();
		return larpOnly != null && larpOnly.isEnabled() && larpOnly.hide.get() && isCheat(module);
	}

	@Override
	protected void onEnable() {
		for (Module module : FemboyWare.getModules().getModules()) {
			if (module.isEnabled() && isCheat(module)) {
				pause(module.getName());
				module.setEnabled(false);
			}
		}
	}

	@Override
	protected void onDisable() {
		Set<String> names = pausedNames();
		paused.set("");
		for (String name : names) {
			Module module = FemboyWare.getModules().get(name);
			if (module != null && !module.isEnabled()) module.setEnabled(true);
		}
	}
}
