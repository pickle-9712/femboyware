package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
	@Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
	private void femboyware$noRenderParticles(ParticleEffect parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ,
											  CallbackInfoReturnable<Particle> cir) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(NoRenderModule.class).hidesParticle(parameters.getType())) cir.setReturnValue(null);
	}
}
