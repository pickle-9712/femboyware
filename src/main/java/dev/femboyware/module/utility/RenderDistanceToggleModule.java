package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;

/**
 * Drops your render distance for half a second when you go below a Y level, then puts it back, so
 * the server sends every chunk again (fresh light and block data for Player Bypass, Prime Chunk
 * Finder and Quantum to compare). Only changes your own video setting; nothing goes to the server.
 * Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class RenderDistanceToggleModule extends Module {
	private static final int RESTORE_AFTER_TICKS = 10;
	private static final int REARM_MARGIN = 2;
	private final NumberSetting lowDistance = add(new NumberSetting("Low Distance", "Render distance to drop to for a moment", 2, 2, 32, 1));
	private final NumberSetting resetY = add(new NumberSetting("Reset Y", "Reload chunks when you go down to this height", -1, -64, 320, 1));
	private boolean armed = true;
	private boolean restoring;
	private int timer;
	private int savedDistance = -1;

	public RenderDistanceToggleModule() {
		super("Render Distance Toggle", "Reloads chunks when you go below a Y level", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		armed = true;
		restoring = false;
		timer = 0;
		savedDistance = -1;
	}

	@Override
	protected void onDisable() {
		if (restoring) restore();
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null) {
			if (restoring) restore();
			return;
		}
		int y = mc.player.getBlockY();
		if (!restoring && y > resetY.getInt() + REARM_MARGIN) armed = true;
		if (armed && y <= resetY.getInt()) {
			armed = false;
			restoring = true;
			timer = 0;
			savedDistance = mc.options.renderDistance().get();
			apply(lowDistance.getInt());
		} else if (restoring && ++timer >= RESTORE_AFTER_TICKS) {
			restore();
		}
	}

	private void restore() {
		if (savedDistance >= 0) apply(savedDistance);
		restoring = false;
	}

	private void apply(int chunks) {
		mc.options.renderDistance().set(chunks);
		mc.options.save();
	}
}
