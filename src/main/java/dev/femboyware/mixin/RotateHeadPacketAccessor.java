package dev.femboyware.mixin;

import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which entity a head turn packet is for, without looking it up in the world from the network thread. */
@Mixin(ClientboundRotateHeadPacket.class)
public interface RotateHeadPacketAccessor {
	@Accessor("entityId")
	int femboyware$getEntityId();
}
