package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.movement.SafeWalkModule;
import dev.femboyware.module.utility.ReachModule;
import dev.femboyware.module.utility.SpeedMineModule;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public class PlayerEntityMixin {
	@ModifyReturnValue(method = "getDestroySpeed", at = @At("RETURN"))
	private float femboyware$speedMine(float speed, BlockState state) {
		if ((Object) this != Minecraft.getInstance().player || FemboyWare.getModules() == null) return speed;
		return speed * FemboyWare.getModules().get(SpeedMineModule.class).speedMultiplier();
	}

	@ModifyReturnValue(method = "isStayingOnGroundSurface", at = @At("RETURN"))
	private boolean femboyware$safeWalk(boolean clip) {
		if (clip || (Object) this != Minecraft.getInstance().player || FemboyWare.getModules() == null) return clip;
		return FemboyWare.getModules().get(SafeWalkModule.class).shouldClip();
	}

	@ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
	private double femboyware$blockReach(double range) {
		if ((Object) this != Minecraft.getInstance().player || FemboyWare.getModules() == null) return range;
		return Math.max(0, range + FemboyWare.getModules().get(ReachModule.class).blockReach());
	}

	@ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
	private double femboyware$entityReach(double range) {
		if ((Object) this != Minecraft.getInstance().player || FemboyWare.getModules() == null) return range;
		return Math.max(0, range + FemboyWare.getModules().get(ReachModule.class).entityReach());
	}

	/** Fake Player ranks: a fake player's nametag shows its staff or media rank. */
	@ModifyReturnValue(method = "getDisplayName", at = @At("RETURN"))
	private Component femboyware$fakeRank(Component name) {
		if (FemboyWare.getModules() == null) return name;
		return FemboyWare.getModules().get(FakePlayerModule.class).nametag((Player) (Object) this, name);
	}

	//? if <26.3 {
	/** Server Larp: items thrown out of a screen land here; on the client vanilla only swings the arm. */
	@org.spongepowered.asm.mixin.injection.Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"))
	private void femboyware$larpThrow(net.minecraft.world.item.ItemStack stack, boolean randomly,
									  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.entity.item.ItemEntity> cir) {
		if ((Object) this != Minecraft.getInstance().player || FemboyWare.getModules() == null) return;
		FemboyWare.getModules().get(dev.femboyware.module.utility.ServerLarpModule.class).dropLocally(stack, randomly);
	}
	//?}
}
