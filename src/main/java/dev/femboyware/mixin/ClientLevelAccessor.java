package dev.femboyware.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Server Larp: the level's block prediction, to save larp blocks and put them back reversibly. */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
	@Invoker("getBlockStatePredictionHandler")
	BlockStatePredictionHandler femboyware$getPredictionHandler();
}
