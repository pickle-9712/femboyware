package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.femboyware.module.world.AntiGhostBlockModule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anti Ghost Block: a held break counts as answered once the server sends that block. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(ClientLevel.class)
public abstract class AntiGhostBlockLevelMixin {
	@WrapOperation(method = "setServerVerifiedBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/prediction/BlockStatePredictionHandler;updateKnownServerState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"), require = 0)
	private boolean femboyware$serverAnswer(BlockStatePredictionHandler handler, BlockPos pos, BlockState state, Operation<Boolean> original) {
		boolean predicted = original.call(handler, pos, state);
		return predicted && !AntiGhostBlockModule.serverAnswered((ClientLevel) (Object) this, pos);
	}

	@Inject(method = "handleBlockChangedAck", at = @At("TAIL"), require = 0)
	private void femboyware$acknowledged(int sequence, CallbackInfo ci) {
		AntiGhostBlockModule.acknowledged((ClientLevel) (Object) this, sequence);
	}
}
