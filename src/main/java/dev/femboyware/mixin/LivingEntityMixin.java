package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.utility.AutoMoanModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import dev.femboyware.module.movement.ElytraFreezeModule;
import dev.femboyware.module.movement.ElytraPlusModule;
import dev.femboyware.module.movement.HighJumpModule;
import dev.femboyware.module.movement.LevitationControlModule;
import dev.femboyware.module.render.SwingAnimationsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
	/** Tells Bodyguard who hit whom, so it knows who attacked the principal or a guard, and Auto Moan who hit us. */
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void femboyware$bodyguardDamage(DamageSource source, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		FemboyWare.getModules().get(BodyguardModule.class).onDamaged((LivingEntity) (Object) this, source);
		FemboyWare.getModules().get(AutoMoanModule.class).onDamaged((LivingEntity) (Object) this, source);
	}

	/** Sprint-jump boost goes in the direction of the server yaw, like the server expects. */
	@ModifyExpressionValue(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
	private float femboyware$moveFixJumpYaw(float yaw) {
		if ((Object) this != Minecraft.getInstance().player || FemboyWare.getRotations() == null) return yaw;
		return FemboyWare.getRotations().isMoveFixActive() ? FemboyWare.getRotations().getServerYaw() : yaw;
	}

	@ModifyExpressionValue(method = "travelFallFlying", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;updateFallFlyingMovement(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 femboyware$elytraVelocity(Vec3 velocity) {
		if (!isLocalPlayer()) return velocity;
		velocity = FemboyWare.getModules().get(ElytraPlusModule.class).modifyGliding(velocity);
		return FemboyWare.getModules().get(ElytraFreezeModule.class).modifyGliding(velocity);
	}

	@Inject(method = "travelInAir", at = @At("TAIL"))
	private void femboyware$levitationControl(Vec3 movementInput, CallbackInfo ci) {
		if (isLocalPlayer()) FemboyWare.getModules().get(LevitationControlModule.class).afterTravel();
	}

	@ModifyReturnValue(method = "getJumpPower(F)F", at = @At("RETURN"))
	private float femboyware$highJump(float velocity) {
		if (!isLocalPlayer()) return velocity;
		return velocity * FemboyWare.getModules().get(HighJumpModule.class).multiplier();
	}

	//? if >=26.3 {
	/*@ModifyReturnValue(method = "getModifiedSwingDuration", at = @At("RETURN"))
	*///?} else {
	@ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"))
	//?}
	private int femboyware$swingDuration(int duration) {
		if (!isLocalPlayer() || !FemboyWare.isEnabled(SwingAnimationsModule.class)) return duration;
		return FemboyWare.getModules().get(SwingAnimationsModule.class).getDuration();
	}

	//? if >=26.3 {
	/*/^* Server Larp: items thrown out of a screen land here; on the client vanilla only swings the arm. ^/
	@org.spongepowered.asm.mixin.injection.Inject(method = "drop", at = @At("HEAD"))
	private void femboyware$larpThrow(net.minecraft.world.item.ItemStack stack, boolean thrownFromHand, net.minecraft.util.Prediction prediction,
									  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.entity.item.ItemEntity> cir) {
		if ((Object) this != Minecraft.getInstance().player || FemboyWare.getModules() == null) return;
		FemboyWare.getModules().get(dev.femboyware.module.utility.ServerLarpModule.class).dropLocally(stack, thrownFromHand);
	}
	*///?}

	private boolean isLocalPlayer() {
		return FemboyWare.getModules() != null && (Object) this == Minecraft.getInstance().player;
	}
}
