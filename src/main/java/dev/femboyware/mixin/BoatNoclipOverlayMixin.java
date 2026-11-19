package dev.femboyware.mixin;

import dev.femboyware.module.movement.BoatNoclipModule;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Boat Noclip: no in-wall overlay while flying through blocks. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(ScreenEffectRenderer.class)
public abstract class BoatNoclipOverlayMixin {
	@Inject(method = "getViewBlockingState", at = @At("HEAD"), cancellable = true, require = 0)
	private static void femboyware$noWallOverlay(Player player, CallbackInfoReturnable<BlockState> cir) {
		if (BoatNoclipModule.hidesWallOverlay(player)) cir.setReturnValue(null);
	}
}
