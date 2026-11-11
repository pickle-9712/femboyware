package dev.femboyware.mixin;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which entity a move packet is for, without looking it up in the world from the network thread. */
@Mixin(ClientboundMoveEntityPacket.class)
public interface MoveEntityPacketAccessor {
	@Accessor("entityId")
	int femboyware$getEntityId();
}
