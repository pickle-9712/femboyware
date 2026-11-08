package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.SoundBlockerModule;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public class SoundSystemMixin {
	/** Sound Blocker. Delayed and looping sounds come through here too when they start. */
	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"), cancellable = true)
	private void femboyware$soundBlocker(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(SoundBlockerModule.class).shouldBlock(sound.getIdentifier())) {
			cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
		}
	}
}
