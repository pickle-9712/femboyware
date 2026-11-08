package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.AntiKnockBackModule;
import dev.femboyware.module.utility.NoRotateModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;

/** Fires block updates on the main thread after the client world has applied them. */
@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {
	/** Chat starting with "." is a femboyware command: handled here, never sent to the server. */
	@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
	private void femboyware$command(String message, CallbackInfo ci) {
		if (FemboyWare.getCommands() != null && FemboyWare.getCommands().handle(message)) ci.cancel();
	}

	@Inject(method = "handleBlockUpdate", at = @At("TAIL"))
	private void femboyware$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		FemboyWare.getModules().onBlockUpdate(packet.getPos(), packet.getBlockState());
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
	private void femboyware$onChunkDeltaUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		packet.runUpdates((pos, state) -> FemboyWare.getModules().onBlockUpdate(pos.immutable(), state));
	}

	//? if >=26.1 {
	/*@ModifyExpressionValue(method = "handleSetEntityMotion", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundSetEntityMotionPacket;movement()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 femboyware$antiKnockBack(Vec3 velocity, @Local(argsOnly = true) ClientboundSetEntityMotionPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || packet.id() != mc.player.getId()) return velocity;
	*///?} else {
	@ModifyExpressionValue(method = "handleSetEntityMotion", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundSetEntityMotionPacket;getMovement()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 femboyware$antiKnockBack(Vec3 velocity, @Local(argsOnly = true) ClientboundSetEntityMotionPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || packet.getId() != mc.player.getId()) return velocity;
	//?}
		return FemboyWare.getModules().get(AntiKnockBackModule.class).modifyVelocity(velocity);
	}

	@ModifyExpressionValue(method = "handleExplosion", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;"))
	private Optional<Vec3> femboyware$antiExplosionKnockBack(Optional<Vec3> knockback) {
		AntiKnockBackModule module = FemboyWare.getModules().get(AntiKnockBackModule.class);
		return knockback.map(module::modifyExplosion);
	}

	/** No Rotate. HEAD also runs once on the netty thread before vanilla reschedules the packet, so only save on the main thread. */
	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void femboyware$beforePositionLook(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread()) FemboyWare.getModules().get(NoRotateModule.class).beforeServerRotation();
	}

	@Inject(method = "handleMovePlayer", at = @At("RETURN"))
	private void femboyware$afterPositionLook(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		FemboyWare.getModules().get(NoRotateModule.class).afterServerRotation();
	}
}
