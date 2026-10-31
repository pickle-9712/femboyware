package dev.femboyware.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
	@Accessor("itemUseCooldown")
	int femboyware$getItemUseCooldown();

	@Accessor("itemUseCooldown")
	void femboyware$setItemUseCooldown(int cooldown);

	/** Exactly what a left click runs: swing, then attack or start breaking whatever is under the crosshair. */
	@Invoker("doAttack")
	boolean femboyware$leftClick();

	/** Exactly what a right click runs: use the held item on whatever is under the crosshair. */
	@Invoker("doItemUse")
	void femboyware$rightClick();
}
