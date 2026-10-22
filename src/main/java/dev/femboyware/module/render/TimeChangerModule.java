package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;

/**
 * Shows the world at a time of day you pick. Client-side only.
 * Ported from Meteor Client's TimeChanger (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class TimeChangerModule extends Module {
	private final NumberSetting time = add(new NumberSetting("Time", "Time of day in ticks: 1000 morning, 6000 noon, 13000 night, 18000 midnight", 6000, 0, 24000, 100));

	/** The server's real time of day, kept so it comes back when we turn off. */
	private long serverTimeOfDay = -1;
	private boolean serverTicksTime = true;

	public TimeChangerModule() {
		super("Time Changer", "Changes the time of day you see", Category.COSMETIC);
	}

	@Override
	protected void onEnable() {
		if (!nullCheck()) serverTimeOfDay = mc.world.getTimeOfDay();
	}

	@Override
	protected void onDisable() {
		if (nullCheck() || serverTimeOfDay < 0) return;
		mc.world.setTime(mc.world.getTime(), serverTimeOfDay, serverTicksTime);
		serverTimeOfDay = -1;
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof WorldTimeUpdateS2CPacket packet)) return;
		event.cancel();
		// Keep the game clock in sync, but remember the server's time of day instead of showing it
		mc.execute(() -> {
			if (nullCheck()) return;
			serverTimeOfDay = packet.timeOfDay();
			serverTicksTime = packet.tickDayTime();
			mc.world.setTime(packet.time(), time.get().longValue(), false);
		});
	}

	@Override
	public void onTick() {
		if (serverTimeOfDay < 0) serverTimeOfDay = mc.world.getTimeOfDay();
		mc.world.setTime(mc.world.getTime(), time.get().longValue(), false);
	}
}
