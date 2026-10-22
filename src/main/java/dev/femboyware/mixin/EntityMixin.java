package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.AntiWebModule;
import dev.femboyware.module.movement.NoPushModule;
import dev.femboyware.module.movement.NoSlowModule;
import dev.femboyware.module.render.FreeLookModule;
import dev.femboyware.module.render.PopChamsModule;
import dev.femboyware.module.utility.FreeCamModule;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {
	/** Turns movement input into velocity using the server yaw while a silent rotation is active. */
	@ModifyExpressionValue(method = "updateVelocity", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw()F"))
	private float femboyware$moveFixYaw(float yaw) {
		if ((Object) this != MinecraftClient.getInstance().player || FemboyWare.getRotations() == null) return yaw;
		return FemboyWare.getRotations().isMoveFixActive() ? FemboyWare.getRotations().getServerYaw() : yaw;
	}

	@Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
	private void femboyware$noEntityPush(Entity entity, CallbackInfo ci) {
		if (isLocalPlayer() && FemboyWare.getModules().get(NoPushModule.class).entities()) ci.cancel();
	}

	@ModifyReturnValue(method = "isPushedByFluids", at = @At("RETURN"))
	private boolean femboyware$noFluidPush(boolean pushed) {
		return pushed && !(isLocalPlayer() && FemboyWare.getModules().get(NoPushModule.class).liquids());
	}

	@Inject(method = "slowMovement", at = @At("HEAD"), cancellable = true)
	private void femboyware$antiWeb(BlockState state, Vec3d multiplier, CallbackInfo ci) {
		if (isLocalPlayer() && FemboyWare.getModules().get(AntiWebModule.class).ignores(state)) ci.cancel();
	}

	@ModifyReturnValue(method = "getVelocityMultiplier", at = @At("RETURN"))
	private float femboyware$noBlockSlow(float multiplier) {
		return isLocalPlayer() && FemboyWare.getModules().get(NoSlowModule.class).blocks() ? Math.max(multiplier, 1f) : multiplier;
	}

	/** Free Look and Free Cam take the mouse instead of the player. */
	@Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
	private void femboyware$freeLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		if (!isLocalPlayer()) return;
		FreeCamModule freeCam = FemboyWare.getModules().get(FreeCamModule.class);
		if (freeCam.isEnabled()) {
			freeCam.changeLook(cursorDeltaX, cursorDeltaY);
			ci.cancel();
			return;
		}
		FreeLookModule freeLook = FemboyWare.getModules().get(FreeLookModule.class);
		if (freeLook.isEnabled()) {
			freeLook.changeLook(cursorDeltaX, cursorDeltaY);
			ci.cancel();
		}
	}

	@ModifyReturnValue(method = "getTeamColorValue", at = @At("RETURN"))
	private int femboyware$popChamsColor(int color) {
		if (!FemboyWare.isEnabled(PopChamsModule.class)) return color;
		PopChamsModule popChams = FemboyWare.getModules().get(PopChamsModule.class);
		return popChams.isGhost((Entity) (Object) this) ? popChams.getColor() : color;
	}

	private boolean isLocalPlayer() {
		return FemboyWare.getModules() != null && (Object) this == MinecraftClient.getInstance().player;
	}
}
