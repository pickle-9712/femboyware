package dev.femboyware.mixin;

import dev.femboyware.module.world.AntiGhostBlockModule;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Anti Ghost Block: hold a break until the server answers, and don't re-mine a block that's waiting. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(value = MultiPlayerGameMode.class, priority = 1100)
public abstract class AntiGhostBlockGameModeMixin {
	@Shadow
	private int destroyDelay;

	@Shadow
	public abstract void stopDestroyBlock();

	@Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyware$holdBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (AntiGhostBlockModule.holdBreak(pos)) cir.setReturnValue(false);
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyware$waitBeforeStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (AntiGhostBlockModule.waiting(pos)) {
			stopDestroyBlock();
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyware$waitBeforeContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (AntiGhostBlockModule.waiting(pos)) {
			if (destroyDelay > 0) destroyDelay--;
			stopDestroyBlock();
			cir.setReturnValue(false);
		}
	}
}
