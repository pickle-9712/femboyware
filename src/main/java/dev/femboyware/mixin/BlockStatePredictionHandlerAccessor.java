package dev.femboyware.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Server Larp: which blocks were changed client-side and are still waiting on the server. */
@Mixin(BlockStatePredictionHandler.class)
public interface BlockStatePredictionHandlerAccessor {
	@Accessor("serverVerifiedStates")
	Long2ObjectOpenHashMap<?> femboyware$getServerVerifiedStates();
}
