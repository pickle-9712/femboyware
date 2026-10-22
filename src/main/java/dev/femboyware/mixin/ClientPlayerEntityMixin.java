package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.NoPushModule;
import dev.femboyware.module.movement.NoSlowModule;
import dev.femboyware.module.utility.FreeCamModule;
import dev.femboyware.module.utility.LiquidInteractModule;
import dev.femboyware.module.utility.NoMiningTraceModule;
import dev.femboyware.util.MoveFix;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void femboyware$preMotion(CallbackInfo ci) {
		FemboyWare.getModules().onPreMotion();
		FemboyWare.getRotations().prepare();
	}

	/**
	 * Remap the keys as soon as they're read, so the sprint logic, the speed factors and the
	 * input packet sent to the server all agree with the movement we actually make.
	 */
	@Inject(method = "tickMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/input/Input;tick()V", shift = At.Shift.AFTER))
	private void femboyware$fixMovementInput(CallbackInfo ci) {
		if (FemboyWare.getRotations().isMoveFixActive() && !FemboyWare.isEnabled(FreeCamModule.class)) {
			MoveFix.fixInput((ClientPlayerEntity) (Object) this, FemboyWare.getRotations().getServerYaw());
		}
	}

	@Inject(method = "tickMovementInput", at = @At("TAIL"))
	private void femboyware$movementInput(CallbackInfo ci) {
		FemboyWare.getModules().onMovementInput();
	}

	@Inject(method = "sendMovementPackets", at = @At("HEAD"))
	private void femboyware$beforePacket(CallbackInfo ci) {
		FemboyWare.getRotations().applyBeforePacket();
	}

	@Inject(method = "sendMovementPackets", at = @At("TAIL"))
	private void femboyware$postMotion(CallbackInfo ci) {
		FemboyWare.getRotations().restoreAfterPacket();
		FemboyWare.getModules().onPostMotion();
	}

	@Inject(method = "pushOutOfBlocks", at = @At("HEAD"), cancellable = true)
	private void femboyware$noBlockPush(double x, double z, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoPushModule.class).blocks()) ci.cancel();
	}

	@ModifyExpressionValue(method = "applyMovementSpeedFactors", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z"))
	private boolean femboyware$noItemSlow(boolean using) {
		return using && !FemboyWare.getModules().get(NoSlowModule.class).items();
	}

	@ModifyReturnValue(method = "isBlockedFromSprinting", at = @At("RETURN"))
	private boolean femboyware$sprintWhileUsing(boolean blocked) {
		return blocked && !FemboyWare.getModules().get(NoSlowModule.class).items();
	}

	/** Liquid Interact: when the crosshair finds nothing solid, look again counting water and lava. */
	@ModifyExpressionValue(method = "getCrosshairTarget(Lnet/minecraft/entity/Entity;DDF)Lnet/minecraft/util/hit/HitResult;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;raycast(DFZ)Lnet/minecraft/util/hit/HitResult;"))
	private static HitResult femboyware$liquidInteract(HitResult hit, @Local(argsOnly = true) Entity camera,
			@Local(argsOnly = true, ordinal = 0) double blockRange, @Local(argsOnly = true, ordinal = 1) double entityRange, @Local(argsOnly = true) float tickDelta) {
		if (hit.getType() != HitResult.Type.MISS || !FemboyWare.isEnabled(LiquidInteractModule.class)) return hit;
		return camera.raycast(Math.max(blockRange, entityRange), tickDelta, true);
	}

	/** No Mining Trace: look past entities to the block behind them. */
	@ModifyReturnValue(method = "getCrosshairTarget(Lnet/minecraft/entity/Entity;DDF)Lnet/minecraft/util/hit/HitResult;", at = @At("RETURN"))
	private static HitResult femboyware$noMiningTrace(HitResult hit, @Local(argsOnly = true) Entity camera,
			@Local(argsOnly = true, ordinal = 0) double blockRange, @Local(argsOnly = true) float tickDelta) {
		if (!(hit instanceof EntityHitResult entityHit) || FemboyWare.getModules() == null) return hit;
		if (!FemboyWare.getModules().get(NoMiningTraceModule.class).shouldSkip(entityHit.getEntity())) return hit;
		HitResult block = camera.raycast(blockRange, tickDelta, false);
		return block.getType() == HitResult.Type.BLOCK ? block : hit;
	}
}
