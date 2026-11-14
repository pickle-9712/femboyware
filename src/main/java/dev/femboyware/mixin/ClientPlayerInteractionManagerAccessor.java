package dev.femboyware.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface ClientPlayerInteractionManagerAccessor {
	@Invoker("ensureHasSentCarriedItem")
	void femboyware$syncSelectedSlot();

	@Accessor("destroyProgress")
	float femboyware$getBreakingProgress();

	@Accessor("destroyProgress")
	void femboyware$setBreakingProgress(float progress);

	@Accessor("destroyDelay")
	void femboyware$setDestroyDelay(int ticks);

	@Accessor("destroyBlockPos")
	BlockPos femboyware$getBreakingPos();
}
