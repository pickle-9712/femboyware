package dev.femboyware.test;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.sounds.SoundSource;

/** Game tests run silent: master volume to 0 as soon as the test client starts (its run folder is new each time). */
public class MuteTests implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
	}
}
