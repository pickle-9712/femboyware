package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.movement.ElytraBoostModule;
import dev.femboyware.module.utility.AirPlaceModule;
import dev.femboyware.module.utility.ServerLarpModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
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
		if (FemboyWare.getModules().get(ServerLarpModule.class).onAttack(target)
			|| FemboyWare.getModules().get(FakePlayerModule.class).onAttack(player, target)) {
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
		if (FemboyWare.getModules().get(ServerLarpModule.class).onUseItem(hand)) cir.setReturnValue(InteractionResult.SUCCESS);
		else if (FemboyWare.getModules().get(ElytraBoostModule.class).onUseItem(hand)) cir.setReturnValue(InteractionResult.PASS);
		else if (FemboyWare.getModules().get(AirPlaceModule.class).onUseItem(hand)) cir.setReturnValue(InteractionResult.SUCCESS);
	}

	/** Server Larp: chests, barrels and shulkers open locally while larping. */
	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void femboyware$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (FemboyWare.getModules().get(ServerLarpModule.class).onUseItemOn(player, hand, hit)) cir.setReturnValue(InteractionResult.SUCCESS);
	}

	/**
	 * Server Larp: inventory clicks are applied exactly as vanilla does, minus building the click packet,
	 * which would only be blocked and whose item hashing can choke on larp items.
	 */
	@Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true)
	private void femboyware$larpClick(int containerId, int slotId, int button, net.minecraft.world.inventory.ClickType clickType, Player player, CallbackInfo ci) {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(ServerLarpModule.class).isLarping()) return;
		net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
		if (containerId == menu.containerId) menu.clicked(slotId, button, clickType, player);
		ci.cancel();
	}

	/** Server Larp: middle-click picking is done by the server since 1.21.4, so while larping it's done here. */
	@Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
	private void femboyware$larpPickBlock(BlockPos pos, boolean withData, CallbackInfo ci) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(ServerLarpModule.class).pickBlock(pos, withData)) ci.cancel();
	}

	@Inject(method = "handlePickItemFromEntity", at = @At("HEAD"), cancellable = true)
	private void femboyware$larpPickEntity(Entity entity, boolean withData, CallbackInfo ci) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(ServerLarpModule.class).pickEntity(entity)) ci.cancel();
	}
}
