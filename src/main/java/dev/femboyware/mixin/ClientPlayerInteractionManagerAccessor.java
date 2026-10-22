package dev.femboyware.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
	@Invoker("syncSelectedSlot")
	void femboyware$syncSelectedSlot();

	@Accessor("currentBreakingProgress")
	float femboyware$getBreakingProgress();

	@Accessor("currentBreakingProgress")
	void femboyware$setBreakingProgress(float progress);

	@Accessor("currentBreakingPos")
	BlockPos femboyware$getBreakingPos();
}
