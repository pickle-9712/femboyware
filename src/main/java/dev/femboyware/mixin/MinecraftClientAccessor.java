package dev.femboyware.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {
	@Accessor("rightClickDelay")
	int femboyware$getItemUseCooldown();

	@Accessor("rightClickDelay")
	void femboyware$setItemUseCooldown(int cooldown);

	/** Exactly what a left click runs: swing, then attack or start breaking whatever is under the crosshair. */
	@Invoker("startAttack")
	boolean femboyware$leftClick();

	/** Exactly what a right click runs: use the held item on whatever is under the crosshair. */
	@Invoker("startUseItem")
	void femboyware$rightClick();
}
