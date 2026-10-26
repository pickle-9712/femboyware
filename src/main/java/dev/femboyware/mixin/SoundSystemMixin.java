package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.SoundBlockerModule;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundSystem.class)
public class SoundSystemMixin {
	/** Sound Blocker. Delayed and looping sounds come through here too when they start. */
	@Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;", at = @At("HEAD"), cancellable = true)
	private void femboyware$soundBlocker(SoundInstance sound, CallbackInfoReturnable<SoundSystem.PlayResult> cir) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(SoundBlockerModule.class).shouldBlock(sound.getId())) {
			cir.setReturnValue(SoundSystem.PlayResult.NOT_STARTED);
		}
	}
}
