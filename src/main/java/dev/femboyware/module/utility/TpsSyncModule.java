package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.util.TickRate;

/**
 * Slows your client down to the server's tick rate when the server lags, so attack cooldowns,
 * eating and item use line up with what the server sees. Applied through {@link TickRate}.
 */
public class TpsSyncModule extends Module {
	public TpsSyncModule() {
		super("Tps Sync", "Matches your tick rate to the server's", Category.WORLD);
	}

	@Override
	public String getInfo() {
		return String.format("%.1f", TickRate.getTps());
	}
}
