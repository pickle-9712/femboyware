package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.ElytraBoostModule;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Elytra Boost's client-side rockets: never explode, just disappear once spent. */
@Mixin(FireworkRocketEntity.class)
public class FireworkRocketEntityMixin {
	@Shadow
	private int life;
	@Shadow
	private int lifetime;

	@Inject(method = "tick", at = @At("TAIL"))
	private void femboyware$expire(CallbackInfo ci) {
		if (femboyware$isBoost() && life > lifetime) ((FireworkRocketEntity) (Object) this).discard();
	}

	@Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
	private void femboyware$onEntityHit(EntityHitResult hit, CallbackInfo ci) {
		if (!femboyware$isBoost()) return;
		((FireworkRocketEntity) (Object) this).discard();
		ci.cancel();
	}

	@Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
	private void femboyware$onBlockHit(BlockHitResult hit, CallbackInfo ci) {
		if (!femboyware$isBoost()) return;
		((FireworkRocketEntity) (Object) this).discard();
		ci.cancel();
	}

	@Unique
	private boolean femboyware$isBoost() {
		return FemboyWare.getModules() != null && FemboyWare.getModules().get(ElytraBoostModule.class).isOurFirework((FireworkRocketEntity) (Object) this);
	}
}
