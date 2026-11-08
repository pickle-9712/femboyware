package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.PotionSaverModule;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEffectInstance.class)
public class StatusEffectInstanceMixin {
	@Shadow
	@Final
	private Holder<MobEffect> effect;

	/** Potion Saver: frozen effects don't count down. */
	@Inject(method = "tickDownDuration", at = @At("HEAD"), cancellable = true)
	private void femboyware$potionSaver(CallbackInfo ci) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(PotionSaverModule.class).shouldFreeze(effect)) ci.cancel();
	}
}
