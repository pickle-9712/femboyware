package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;

/** Turns off distracting overlays and effects. Each toggle is checked by a render mixin. */
public class NoRenderModule extends Module {
	private final BoolSetting hurtCam = add(new BoolSetting("Hurt Cam", "No camera shake when hurt", true));
	private final BoolSetting fire = add(new BoolSetting("Fire", "No fire overlay", true));
	private final BoolSetting liquid = add(new BoolSetting("Liquid", "No underwater overlay", true));
	private final BoolSetting inWall = add(new BoolSetting("In Wall", "No overlay while your head is inside a block", true));
	private final BoolSetting pumpkin = add(new BoolSetting("Pumpkin", "No pumpkin and powder snow overlays", true));
	private final BoolSetting vignette = add(new BoolSetting("Vignette", "No darkened screen edges", false));
	private final BoolSetting portal = add(new BoolSetting("Portal", "No nether portal and nausea overlays", true));
	private final BoolSetting bossBar = add(new BoolSetting("Boss Bar", "Hide boss bars", false));
	private final BoolSetting scoreboard = add(new BoolSetting("Scoreboard", "Hide the sidebar scoreboard", false));
	private final BoolSetting explosions = add(new BoolSetting("Explosions", "No explosion particles", true));

	public NoRenderModule() {
		super("No Render", "Removes overlays and effects", Category.WORLD);
	}

	public boolean hurtCam() {
		return isEnabled() && hurtCam.get();
	}

	public boolean fire() {
		return isEnabled() && fire.get();
	}

	public boolean liquid() {
		return isEnabled() && liquid.get();
	}

	public boolean inWall() {
		return isEnabled() && inWall.get();
	}

	public boolean pumpkin() {
		return isEnabled() && pumpkin.get();
	}

	public boolean vignette() {
		return isEnabled() && vignette.get();
	}

	public boolean portal() {
		return isEnabled() && portal.get();
	}

	public boolean bossBar() {
		return isEnabled() && bossBar.get();
	}

	public boolean scoreboard() {
		return isEnabled() && scoreboard.get();
	}

	public boolean explosions() {
		return isEnabled() && explosions.get();
	}
}
