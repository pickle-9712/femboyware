package dev.femboyware.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface ClientPlayerEntityAccessor {
	@Accessor("yRotLast")
	void femboyware$setLastYaw(float yaw);

	@Accessor("xRotLast")
	void femboyware$setLastPitch(float pitch);
}
