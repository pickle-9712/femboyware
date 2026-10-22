package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.PotionSaverModule;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StatusEffectInstance.class)
public class StatusEffectInstanceMixin {
	@Shadow
	@Final
	private RegistryEntry<StatusEffect> type;

	/** Potion Saver: frozen effects don't count down. */
	@Inject(method = "updateDuration", at = @At("HEAD"), cancellable = true)
	private void femboyware$potionSaver(CallbackInfo ci) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(PotionSaverModule.class).shouldFreeze(type)) ci.cancel();
	}
}
