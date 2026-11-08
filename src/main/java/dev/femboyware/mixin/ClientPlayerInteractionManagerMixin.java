package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.movement.ElytraBoostModule;
import dev.femboyware.module.utility.AirPlaceModule;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps attacks on the fake player client-side so the server never sees an unknown entity id, and
 * lets modules take over block breaking.
 */
@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void femboyware$onAttackEntity(Player player, Entity target, CallbackInfo ci) {
		FemboyWare.getModules().onPreAttack(target);
		if (FemboyWare.getModules().get(FakePlayerModule.class).onAttack(player, target)) {
			ci.cancel();
			FemboyWare.getModules().onPostAttack(target);
		}
	}

	@Inject(method = "attack", at = @At("TAIL"))
	private void femboyware$afterAttackEntity(Player player, Entity target, CallbackInfo ci) {
		FemboyWare.getModules().onPostAttack(target);
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void femboyware$onAttackBlock(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
		if (FemboyWare.getModules().onStartBreaking(pos, side)) cir.setReturnValue(false);
	}

	/** Elytra Boost: a rocket used while flying boosts client-side instead of being spent. */
	@Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
	private void femboyware$onInteractItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (FemboyWare.getModules().get(ElytraBoostModule.class).onUseItem(hand)) cir.setReturnValue(InteractionResult.PASS);
		else if (FemboyWare.getModules().get(AirPlaceModule.class).onUseItem(hand)) cir.setReturnValue(InteractionResult.SUCCESS);
	}
}
