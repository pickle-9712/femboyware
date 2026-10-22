package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.SafeWalkModule;
import dev.femboyware.module.utility.ReachModule;
import dev.femboyware.module.utility.SpeedMineModule;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerEntity.class)
public class PlayerEntityMixin {
	@ModifyReturnValue(method = "getBlockBreakingSpeed", at = @At("RETURN"))
	private float femboyware$speedMine(float speed, BlockState state) {
		if ((Object) this != MinecraftClient.getInstance().player || FemboyWare.getModules() == null) return speed;
		return speed * FemboyWare.getModules().get(SpeedMineModule.class).speedMultiplier();
	}

	@ModifyReturnValue(method = "clipAtLedge", at = @At("RETURN"))
	private boolean femboyware$safeWalk(boolean clip) {
		if (clip || (Object) this != MinecraftClient.getInstance().player || FemboyWare.getModules() == null) return clip;
		return FemboyWare.getModules().get(SafeWalkModule.class).shouldClip();
	}

	@ModifyReturnValue(method = "getBlockInteractionRange", at = @At("RETURN"))
	private double femboyware$blockReach(double range) {
		if ((Object) this != MinecraftClient.getInstance().player || FemboyWare.getModules() == null) return range;
		return Math.max(0, range + FemboyWare.getModules().get(ReachModule.class).blockReach());
	}

	@ModifyReturnValue(method = "getEntityInteractionRange", at = @At("RETURN"))
	private double femboyware$entityReach(double range) {
		if ((Object) this != MinecraftClient.getInstance().player || FemboyWare.getModules() == null) return range;
		return Math.max(0, range + FemboyWare.getModules().get(ReachModule.class).entityReach());
	}
}
