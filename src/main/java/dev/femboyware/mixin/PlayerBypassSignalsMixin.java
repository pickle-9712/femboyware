package dev.femboyware.mixin;

import dev.femboyware.util.playerbypass.PlayerBypassLightTracker;
import dev.femboyware.util.playerbypass.SimulationSignals;
import dev.femboyware.util.playerbypass.V;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Player Bypass: feeds packets to its trackers on the main thread, once the game has applied them.
 * Only reads what the server already sent you; sends nothing. Ported from the Anubis client
 * (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
@Mixin(ClientPacketListener.class)
public abstract class PlayerBypassSignalsMixin {
	private static final String MAIN = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

	@Inject(method = "handleLogin", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
		SimulationSignals.onLogin(packet.simulationDistance(), packet.chunkRadius(), packet.commonPlayerSpawnInfo().dimension());
	}

	@Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		SimulationSignals.onRespawn(packet.commonPlayerSpawnInfo().dimension());
	}

	@Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbMovePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		SimulationSignals.onTeleport();
	}

	@Inject(method = "handleSetSimulationDistance", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbSetSimulationDistance(ClientboundSetSimulationDistancePacket packet, CallbackInfo ci) {
		SimulationSignals.onSimulationDistance(packet.simulationDistance());
	}

	@Inject(method = "handleSetChunkCacheRadius", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbSetChunkCacheRadius(ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo ci) {
		SimulationSignals.onViewDistance(packet.getRadius());
	}

	@Inject(method = "handleMoveEntity", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
		SimulationSignals.onMove(packet);
	}

	@Inject(method = "handleEntityPositionSync", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbEntityPositionSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
		SimulationSignals.onPositionSync(packet);
	}

	@Inject(method = "handleRotateMob", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbRotateMob(ClientboundRotateHeadPacket packet, CallbackInfo ci) {
		SimulationSignals.onHeadRotation(packet);
	}

	@Inject(method = "handleSetEntityData", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbSetEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
		SimulationSignals.onEntityData(packet);
	}

	@Inject(method = "handleEntityEvent", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		SimulationSignals.onEntityEvent(packet);
	}

	@Inject(method = "handleAddEntity", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		SimulationSignals.onAddEntity(packet);
	}

	@Inject(method = "handleRemoveEntities", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		SimulationSignals.onRemoveEntities(packet.getEntityIds());
	}

	@Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		SimulationSignals.onBlockUpdate(packet);
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		SimulationSignals.onSectionUpdate(packet);
	}

	@Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbTakeItemEntity(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		SimulationSignals.onTakeItem(packet);
	}

	@Inject(method = "handleDamageEvent", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		SimulationSignals.onDamage(packet);
	}

	@Inject(method = "handleSetEquipment", at = @At(value = "INVOKE", target = MAIN, shift = At.Shift.AFTER), require = 0)
	private void femboyware$pbSetEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
		SimulationSignals.onEquipment(packet);
	}

	@Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"), require = 0)
	private void femboyware$pbChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		PlayerBypassLightTracker.onPacketApplied(packet);
		SimulationSignals.onChunk(V.x(packet), V.z(packet));
	}

	@Inject(method = "handleLightUpdatePacket", at = @At("TAIL"), require = 0)
	private void femboyware$pbLight(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
		PlayerBypassLightTracker.onPacketApplied(packet);
	}
}
