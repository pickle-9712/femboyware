package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.movement.ElytraBoostModule;
import dev.femboyware.module.utility.AirPlaceModule;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps attacks on the fake player client-side so the server never sees an unknown entity id, and
 * lets modules take over block breaking.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
	@Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
	private void femboyware$onAttackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
		FemboyWare.getModules().onPreAttack(target);
		if (FemboyWare.getModules().get(FakePlayerModule.class).onAttack(player, target)) {
			ci.cancel();
			FemboyWare.getModules().onPostAttack(target);
		}
	}

	@Inject(method = "attackEntity", at = @At("TAIL"))
	private void femboyware$afterAttackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
		FemboyWare.getModules().onPostAttack(target);
	}

	@Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
	private void femboyware$onAttackBlock(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
		if (FemboyWare.getModules().onStartBreaking(pos, side)) cir.setReturnValue(false);
	}

	/** Elytra Boost: a rocket used while flying boosts client-side instead of being spent. */
	@Inject(method = "interactItem", at = @At("HEAD"), cancellable = true)
	private void femboyware$onInteractItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
		if (FemboyWare.getModules().get(ElytraBoostModule.class).onUseItem(hand)) cir.setReturnValue(ActionResult.PASS);
		else if (FemboyWare.getModules().get(AirPlaceModule.class).onUseItem(hand)) cir.setReturnValue(ActionResult.SUCCESS);
	}
}
