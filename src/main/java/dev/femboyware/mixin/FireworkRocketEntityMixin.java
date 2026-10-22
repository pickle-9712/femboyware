package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.ElytraBoostModule;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
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
	private int lifeTime;

	@Inject(method = "tick", at = @At("TAIL"))
	private void femboyware$expire(CallbackInfo ci) {
		if (femboyware$isBoost() && life > lifeTime) ((FireworkRocketEntity) (Object) this).discard();
	}

	@Inject(method = "onEntityHit", at = @At("HEAD"), cancellable = true)
	private void femboyware$onEntityHit(EntityHitResult hit, CallbackInfo ci) {
		if (!femboyware$isBoost()) return;
		((FireworkRocketEntity) (Object) this).discard();
		ci.cancel();
	}

	@Inject(method = "onBlockHit", at = @At("HEAD"), cancellable = true)
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
