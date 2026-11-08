package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public class ParticleManagerMixin {
	@Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
	private void femboyware$noRenderParticles(ParticleOptions parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ,
											  CallbackInfoReturnable<Particle> cir) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(NoRenderModule.class).hidesParticle(parameters.getType())) cir.setReturnValue(null);
	}
}
