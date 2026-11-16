package dev.femboyware.test;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * Game tests run silent: master volume to 0 the moment the test client starts and held there every
 * tick (a test or a module could turn it back up), every sound slider with it, so nothing ever comes
 * out of the speakers.
 */
public class MuteTests implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(MuteTests::mute);
		ClientTickEvents.START_CLIENT_TICK.register(MuteTests::mute);
	}

	private static void mute(Minecraft mc) {
		if (mc.options == null) return;
		// Every slider, not just master, in case anything plays outside it
		for (SoundSource source : SoundSource.values()) {
			var volume = mc.options.getSoundSourceOptionInstance(source);
			if (volume.get() != 0.0) volume.set(0.0);
		}
	}
}
