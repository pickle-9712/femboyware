package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.module.combat.BodyguardModule;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Joining a world, the game keeps the loading screen up until the chunks around you have been drawn.
 * Bodyguard's low power never draws the world, so guards sat on that screen until it timed out.
 * In low power it's enough that the chunk you're standing in has arrived.
 */
@Mixin(targets = "net.minecraft.client.world.ClientChunkLoadProgress$LoadChunks")
public class ClientChunkLoadProgressMixin {
	@Shadow
	@Final
	private ClientPlayerEntity player;
	@Shadow
	@Final
	private ClientWorld world;

	@ModifyReturnValue(method = "isReady", at = @At("RETURN"))
	private boolean femboyware$lowPowerReady(boolean ready) {
		if (ready || !BodyguardModule.lowPower()) return ready;
		BlockPos pos = player.getBlockPos();
		return world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
	}
}
