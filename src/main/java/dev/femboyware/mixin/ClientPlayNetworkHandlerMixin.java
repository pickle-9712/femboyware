package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.AntiKnockBackModule;
import dev.femboyware.module.utility.NoRotateModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/** Fires block updates on the main thread after the client world has applied them. */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
	/** Chat starting with "." is a femboyware command: handled here, never sent to the server. */
	@Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
	private void femboyware$command(String message, CallbackInfo ci) {
		if (FemboyWare.getCommands() != null && FemboyWare.getCommands().handle(message)) ci.cancel();
	}

	@Inject(method = "onBlockUpdate", at = @At("TAIL"))
	private void femboyware$onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
		FemboyWare.getModules().onBlockUpdate(packet.getPos(), packet.getState());
	}

	@Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
	private void femboyware$onChunkDeltaUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
		packet.visitUpdates((pos, state) -> FemboyWare.getModules().onBlockUpdate(pos.toImmutable(), state));
	}

	@ModifyExpressionValue(method = "onEntityVelocityUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/s2c/play/EntityVelocityUpdateS2CPacket;getVelocity()Lnet/minecraft/util/math/Vec3d;"))
	private Vec3d femboyware$antiKnockBack(Vec3d velocity, @Local(argsOnly = true) EntityVelocityUpdateS2CPacket packet) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || packet.getEntityId() != mc.player.getId()) return velocity;
		return FemboyWare.getModules().get(AntiKnockBackModule.class).modifyVelocity(velocity);
	}

	@ModifyExpressionValue(method = "onExplosion", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/s2c/play/ExplosionS2CPacket;playerKnockback()Ljava/util/Optional;"))
	private Optional<Vec3d> femboyware$antiExplosionKnockBack(Optional<Vec3d> knockback) {
		AntiKnockBackModule module = FemboyWare.getModules().get(AntiKnockBackModule.class);
		return knockback.map(module::modifyExplosion);
	}

	/** No Rotate. HEAD also runs once on the netty thread before vanilla reschedules the packet, so only save on the main thread. */
	@Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
	private void femboyware$beforePositionLook(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
		if (MinecraftClient.getInstance().isOnThread()) FemboyWare.getModules().get(NoRotateModule.class).beforeServerRotation();
	}

	@Inject(method = "onPlayerPositionLook", at = @At("RETURN"))
	private void femboyware$afterPositionLook(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
		FemboyWare.getModules().get(NoRotateModule.class).afterServerRotation();
	}
}
