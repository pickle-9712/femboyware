package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.Toasts;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;

/**
 * A toast when it starts raining (good to know on DonutSMP for AFK fishing and farms). Ported from
 * the Anubis client's Weather Notifier (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class WeatherNotifierModule extends Module {
	private final BoolSetting sound = add(new BoolSetting("Sound", "Play a chime with it", true));
	private final BoolSetting stops = add(new BoolSetting("When It Stops", "Also tell you when the rain stops", false));

	private ClientLevel level;
	private boolean raining;
	private int changedTicks;

	public WeatherNotifierModule() {
		super("Weather Notifier", "A toast when it starts raining", Category.MISC);
	}

	@Override
	protected void onEnable() {
		level = null;
	}

	@Override
	public void onTick() {
		if (mc.level != level) {
			level = mc.level;
			raining = level != null && level.isRaining();
			changedTicks = 0;
		}
		if (level == null) return;
		// Wait a second so a flicker on join or a quick change doesn't count
		if (level.isRaining() == raining) {
			changedTicks = 0;
			return;
		}
		if (++changedTicks < 20) return;
		raining = !raining;
		changedTicks = 0;
		if (!raining && !stops.get()) return;
		Toasts.show("Weather Changed!", raining ? (level.isThundering() ? "A thunderstorm started." : "It started raining.") : "The rain stopped.");
		if (sound.get() && mc.player != null) mc.player.playSound(SoundEvents.TRIDENT_THUNDER.value(), 0.6f, 1.2f);
	}
}
