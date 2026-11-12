package dev.femboyware.module.larp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** femboyware's own UI sounds for the phone, the Ledger and notifications, made for it (see sounds/ui). */
public final class LarpSounds {
	public static final SoundEvent LEDGER_NOTIFY = event("ledger_notify");
	public static final SoundEvent PHONE_TEXT = event("phone_text");
	public static final SoundEvent PHONE_UNLOCK = event("phone_unlock");
	public static final SoundEvent PHONE_TAP = event("phone_tap");
	public static final SoundEvent LEDGER_BEEP = event("ledger_beep");
	public static final SoundEvent LEDGER_SUCCESS = event("ledger_success");
	public static final SoundEvent SHUTTER = event("shutter");
	public static final SoundEvent CHAMPAGNE_POP = event("champagne_pop");
	public static final SoundEvent CHAMPAGNE_SPRAY = event("champagne_spray");
	public static final SoundEvent JET_ENGINE = event("jet_engine");
	public static final SoundEvent HELI_ROTOR = event("heli_rotor");
	public static final SoundEvent CAR_ENGINE = event("car_engine");
	public static final SoundEvent CAR_START = event("car_start");

	private LarpSounds() {
	}

	private static SoundEvent event(String name) {
		return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("femboyware", "ui." + name));
	}

	public static void play(SoundEvent sound, float volume) {
		play(sound, 1, volume);
	}

	public static void play(SoundEvent sound, float pitch, float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}
}
