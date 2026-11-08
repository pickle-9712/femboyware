package dev.femboyware.util;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.TimerModule;
import dev.femboyware.module.utility.TpsSyncModule;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

/**
 * Estimates the server tick rate from world time updates (sent once a second) and decides how
 * fast the client should tick.
 */
public final class TickRate {
	private static final int SAMPLES = 10;
	private static final float[] samples = new float[SAMPLES];
	private static int sampleCount;
	private static int nextSample;
	private static long lastUpdate = -1;

	private TickRate() {
	}

	/** Called on the netty thread for every received packet. */
	public static synchronized void onPacket(Packet<?> packet) {
		if (!(packet instanceof ClientboundSetTimePacket)) return;
		long now = System.nanoTime();
		if (lastUpdate >= 0) {
			float seconds = (now - lastUpdate) / 1e9f;
			samples[nextSample] = Math.min(20f, 20f / Math.max(seconds, 0.05f));
			nextSample = (nextSample + 1) % SAMPLES;
			sampleCount = Math.min(sampleCount + 1, SAMPLES);
		}
		lastUpdate = now;
	}

	public static synchronized float getTps() {
		if (sampleCount == 0) return 20f;
		float total = 0;
		for (int i = 0; i < sampleCount; i++) total += samples[i];
		return total / sampleCount;
	}

	/** How many times faster than normal the client should tick. */
	public static float getTimerMultiplier() {
		if (FemboyWare.getModules() == null) return 1f;
		TimerModule timer = FemboyWare.getModules().get(TimerModule.class);
		if (timer.isEnabled()) return timer.getMultiplier();
		if (FemboyWare.isEnabled(TpsSyncModule.class)) return Math.max(0.1f, getTps() / 20f);
		return 1f;
	}
}
