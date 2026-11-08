package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.module.combat.BodyguardModule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Joining a world, the game keeps the loading screen up until the chunks around you have been drawn.
 * Bodyguard's low power never draws the world, so guards sat on that screen until it timed out.
 * In low power it's enough that the chunk you're standing in has arrived.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public class ClientChunkLoadProgressMixin {
	@Shadow
	@Final
	private LocalPlayer player;
	@Shadow
	@Final
	private ClientLevel level;

	@ModifyReturnValue(method = "isReady", at = @At("RETURN"))
	private boolean femboyware$lowPowerReady(boolean ready) {
		if (ready || !BodyguardModule.lowPower()) return ready;
		BlockPos pos = player.blockPosition();
		return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
	}
}
