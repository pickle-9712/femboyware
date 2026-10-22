package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;

/** Client-side time of day and weather. The time is held by the client world mixin. */
public class WorldRendererModule extends Module {
	public enum Weather { UNCHANGED, CLEAR, RAIN, THUNDER }

	private final BoolSetting changeTime = add(new BoolSetting("Change Time", "Override the time of day", true));
	private final NumberSetting time = add(new NumberSetting("Time", "Time of day in ticks (6000 is noon, 18000 midnight)", 6000, 0, 24000, 100)).visibleWhen(changeTime::get);
	private final EnumSetting<Weather> weather = add(new EnumSetting<>("Weather", "Weather to show", Weather.CLEAR));

	public WorldRendererModule() {
		super("World Renderer", "Change the time and weather you see", Category.COSMETIC);
	}

	/** The time of day to show, or -1 to keep the server's. */
	public long getTimeOverride() {
		return isEnabled() && changeTime.get() ? time.getInt() : -1;
	}

	@Override
	public void onTick() {
		if (changeTime.get()) mc.world.setTime(mc.world.getTime(), time.getInt(), false);
		switch (weather.get()) {
			case UNCHANGED -> {
			}
			case CLEAR -> setWeather(0, 0);
			case RAIN -> setWeather(1, 0);
			case THUNDER -> setWeather(1, 1);
		}
	}

	private void setWeather(float rain, float thunder) {
		mc.world.setRainGradient(rain);
		mc.world.setThunderGradient(thunder);
	}
}
