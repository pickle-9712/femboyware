package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.utility.AutoMoanModule;
import dev.femboyware.module.movement.ElytraFreezeModule;
import dev.femboyware.module.movement.ElytraPlusModule;
import dev.femboyware.module.movement.HighJumpModule;
import dev.femboyware.module.movement.LevitationControlModule;
import dev.femboyware.module.render.SwingAnimationsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
	/** Tells Bodyguard who hit whom, so it knows who attacked the principal or a guard, and Auto Moan who hit us. */
	@Inject(method = "onDamaged", at = @At("HEAD"))
	private void femboyware$bodyguardDamage(DamageSource source, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		FemboyWare.getModules().get(BodyguardModule.class).onDamaged((LivingEntity) (Object) this, source);
		FemboyWare.getModules().get(AutoMoanModule.class).onDamaged((LivingEntity) (Object) this, source);
	}

	/** Sprint-jump boost goes in the direction of the server yaw, like the server expects. */
	@ModifyExpressionValue(method = "jump", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getYaw()F"))
	private float femboyware$moveFixJumpYaw(float yaw) {
		if ((Object) this != MinecraftClient.getInstance().player || FemboyWare.getRotations() == null) return yaw;
		return FemboyWare.getRotations().isMoveFixActive() ? FemboyWare.getRotations().getServerYaw() : yaw;
	}

	@ModifyExpressionValue(method = "travelGliding", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;calcGlidingVelocity(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;"))
	private Vec3d femboyware$elytraVelocity(Vec3d velocity) {
		if (!isLocalPlayer()) return velocity;
		velocity = FemboyWare.getModules().get(ElytraPlusModule.class).modifyGliding(velocity);
		return FemboyWare.getModules().get(ElytraFreezeModule.class).modifyGliding(velocity);
	}

	@Inject(method = "travelMidAir", at = @At("TAIL"))
	private void femboyware$levitationControl(Vec3d movementInput, CallbackInfo ci) {
		if (isLocalPlayer()) FemboyWare.getModules().get(LevitationControlModule.class).afterTravel();
	}

	@ModifyReturnValue(method = "getJumpVelocity(F)F", at = @At("RETURN"))
	private float femboyware$highJump(float velocity) {
		if (!isLocalPlayer()) return velocity;
		return velocity * FemboyWare.getModules().get(HighJumpModule.class).multiplier();
	}

	@ModifyReturnValue(method = "getHandSwingDuration", at = @At("RETURN"))
	private int femboyware$swingDuration(int duration) {
		if (!isLocalPlayer() || !FemboyWare.isEnabled(SwingAnimationsModule.class)) return duration;
		return FemboyWare.getModules().get(SwingAnimationsModule.class).getDuration();
	}

	private boolean isLocalPlayer() {
		return FemboyWare.getModules() != null && (Object) this == MinecraftClient.getInstance().player;
	}
}
