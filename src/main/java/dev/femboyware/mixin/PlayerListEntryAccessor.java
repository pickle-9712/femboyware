package dev.femboyware.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerInfo.class)
public interface PlayerListEntryAccessor {
	@Invoker("setLatency")
	void femboyware$setLatency(int latency);
}
