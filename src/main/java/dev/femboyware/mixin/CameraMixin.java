package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.FreeLookModule;
import dev.femboyware.module.render.NoCameraClipModule;
import dev.femboyware.module.utility.FreeCamModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPos(Vec3d pos);

	/** Free Look: the camera turns with the free look angles instead of the player's. */
	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw(F)F"), require = 0)
	private float femboyware$freeLookYaw(float yaw, @Local(argsOnly = true) Entity focusedEntity) {
		return isFreeLook(focusedEntity) ? FemboyWare.getModules().get(FreeLookModule.class).getYaw() : yaw;
	}

	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getPitch(F)F"), require = 0)
	private float femboyware$freeLookPitch(float pitch, @Local(argsOnly = true) Entity focusedEntity) {
		return isFreeLook(focusedEntity) ? FemboyWare.getModules().get(FreeLookModule.class).getPitch() : pitch;
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void femboyware$freeCam(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
		if (!FemboyWare.isEnabled(FreeCamModule.class)) return;
		FreeCamModule freeCam = FemboyWare.getModules().get(FreeCamModule.class);
		setRotation(freeCam.getYaw(), freeCam.getPitch());
		setPos(freeCam.getPos(tickProgress));
	}

	/** Free Cam renders your body, which vanilla only does in third person. */
	@ModifyReturnValue(method = "isThirdPerson", at = @At("RETURN"))
	private boolean femboyware$freeCamThirdPerson(boolean thirdPerson) {
		return thirdPerson || FemboyWare.isEnabled(FreeCamModule.class);
	}

	@Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
	private void femboyware$noCameraClip(float distance, CallbackInfoReturnable<Float> cir) {
		if (FemboyWare.isEnabled(NoCameraClipModule.class)) cir.setReturnValue(FemboyWare.getModules().get(NoCameraClipModule.class).getDistance());
	}

	private static boolean isFreeLook(Entity focusedEntity) {
		return focusedEntity == MinecraftClient.getInstance().player && FemboyWare.isEnabled(FreeLookModule.class);
	}
}
