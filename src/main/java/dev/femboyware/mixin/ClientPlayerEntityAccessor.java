package dev.femboyware.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerEntity.class)
public interface ClientPlayerEntityAccessor {
	@Accessor("lastYawClient")
	void femboyware$setLastYaw(float yaw);

	@Accessor("lastPitchClient")
	void femboyware$setLastPitch(float pitch);
}
