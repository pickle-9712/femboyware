package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.NoPushModule;
import dev.femboyware.module.movement.PhaseModule;
import dev.femboyware.module.movement.NoSlowModule;
import dev.femboyware.module.utility.FreeCamModule;
import dev.femboyware.module.utility.LiquidInteractModule;
import dev.femboyware.module.utility.NoMiningTraceModule;
import dev.femboyware.util.MoveFix;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
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
	@Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER))
	private void femboyware$fixMovementInput(CallbackInfo ci) {
		FemboyWare.getModules().onInput();
		if (FemboyWare.getRotations().isMoveFixActive() && !FemboyWare.isEnabled(FreeCamModule.class)) {
			MoveFix.fixInput((LocalPlayer) (Object) this, FemboyWare.getRotations().getServerYaw());
		}
	}

	@Inject(method = "applyInput", at = @At("TAIL"))
	private void femboyware$movementInput(CallbackInfo ci) {
		FemboyWare.getModules().onMovementInput();
	}

	@Inject(method = "sendPosition", at = @At("HEAD"))
	private void femboyware$beforePacket(CallbackInfo ci) {
		FemboyWare.getRotations().applyBeforePacket();
	}

	@Inject(method = "sendPosition", at = @At("TAIL"))
	private void femboyware$postMotion(CallbackInfo ci) {
		FemboyWare.getRotations().restoreAfterPacket();
		FemboyWare.getModules().onPostMotion();
	}

	@Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
	private void femboyware$noBlockPush(double x, double z, CallbackInfo ci) {
		// No Push, or Phase holding us in the block we pearled into
		if (FemboyWare.getModules().get(NoPushModule.class).blocks() || FemboyWare.getModules().get(PhaseModule.class).holdInBlock()) ci.cancel();
	}

	@ModifyExpressionValue(method = "modifyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
	private boolean femboyware$noItemSlow(boolean using) {
		return using && !FemboyWare.getModules().get(NoSlowModule.class).items();
	}

	@ModifyReturnValue(method = "isSlowDueToUsingItem", at = @At("RETURN"))
	private boolean femboyware$sprintWhileUsing(boolean blocked) {
		return blocked && !FemboyWare.getModules().get(NoSlowModule.class).items();
	}

	/** Liquid Interact: when the crosshair finds nothing solid, look again counting water and lava. */
	@ModifyExpressionValue(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"))
	private static HitResult femboyware$liquidInteract(HitResult hit, @Local(argsOnly = true) Entity camera,
			@Local(argsOnly = true, ordinal = 0) double blockRange, @Local(argsOnly = true, ordinal = 1) double entityRange, @Local(argsOnly = true) float tickDelta) {
		if (hit.getType() != HitResult.Type.MISS || !FemboyWare.isEnabled(LiquidInteractModule.class)) return hit;
		return camera.pick(Math.max(blockRange, entityRange), tickDelta, true);
	}

	/** No Mining Trace: look past entities to the block behind them. */
	@ModifyReturnValue(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;", at = @At("RETURN"))
	private static HitResult femboyware$noMiningTrace(HitResult hit, @Local(argsOnly = true) Entity camera,
			@Local(argsOnly = true, ordinal = 0) double blockRange, @Local(argsOnly = true) float tickDelta) {
		if (!(hit instanceof EntityHitResult entityHit) || FemboyWare.getModules() == null) return hit;
		if (!FemboyWare.getModules().get(NoMiningTraceModule.class).shouldSkip(entityHit.getEntity())) return hit;
		HitResult block = camera.pick(blockRange, tickDelta, false);
		return block.getType() == HitResult.Type.BLOCK ? block : hit;
	}

	/** Server Larp: what Q drops lands here instead of only being sent to a server that isn't listening. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "drop(Z)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;removeFromSelected(Z)Lnet/minecraft/world/item/ItemStack;"))
	private net.minecraft.world.item.ItemStack femboyware$larpDrop(net.minecraft.world.item.ItemStack dropped) {
		if (dev.femboyware.FemboyWare.getModules() != null) dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.utility.ServerLarpModule.class).dropLocally(dropped, false);
		return dropped;
	}
}
