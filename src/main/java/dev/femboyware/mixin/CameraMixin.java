package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.LegitAuraModule;
import dev.femboyware.module.render.FreeLookModule;
import dev.femboyware.module.render.NoCameraClipModule;
import dev.femboyware.module.utility.FreeCamModule;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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
	protected abstract void setPosition(Vec3 pos);

	/** Free Look: the camera turns with the free look angles instead of the player's. */
	//? if >=26.1 {
	/*@ModifyExpressionValue(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"), require = 0)
	private float femboyware$freeLookYaw(float yaw) {
		Entity focusedEntity = ((Camera) (Object) this).entity();
	*///?} else {
	@ModifyExpressionValue(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"), require = 0)
	private float femboyware$freeLookYaw(float yaw, @Local(argsOnly = true) Entity focusedEntity) {
	//?}
		if (isFreeLook(focusedEntity)) return FemboyWare.getModules().get(FreeLookModule.class).getYaw();
		// Legit Aura's side view: turned a quarter round, the third person camera sits beside you instead of behind
		return isSideView(focusedEntity) ? yaw - 90 : yaw;
	}

	//? if >=26.1 {
	/*@ModifyExpressionValue(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"), require = 0)
	private float femboyware$freeLookPitch(float pitch) {
		Entity focusedEntity = ((Camera) (Object) this).entity();
	*///?} else {
	@ModifyExpressionValue(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"), require = 0)
	private float femboyware$freeLookPitch(float pitch, @Local(argsOnly = true) Entity focusedEntity) {
	//?}
		if (isFreeLook(focusedEntity)) return FemboyWare.getModules().get(FreeLookModule.class).getPitch();
		// From the side, a steady slight downward look keeps both of you in view whichever way you aim
		return isSideView(focusedEntity) ? 12 : pitch;
	}

	//? if >=26.1 {
	/*@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float femboyware$zoom(float fov) {
		return FemboyWare.getModules().get(dev.femboyware.module.utility.ZoomModule.class).modifyFov(fov);
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void femboyware$freeCam(net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!FemboyWare.isEnabled(FreeCamModule.class)) return;
		float tickProgress = ((Camera) (Object) this).getCameraEntityPartialTicks(deltaTracker);
	*///?} else {
	@Inject(method = "setup", at = @At("TAIL"))
	private void femboyware$freeCam(Level area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
		if (!FemboyWare.isEnabled(FreeCamModule.class)) return;
	//?}
		FreeCamModule freeCam = FemboyWare.getModules().get(FreeCamModule.class);
		setRotation(freeCam.getYaw(), freeCam.getPitch());
		setPosition(freeCam.getPos(tickProgress));
	}

	/** Free Cam renders your body, which vanilla only does in third person. */
	@ModifyReturnValue(method = "isDetached", at = @At("RETURN"))
	private boolean femboyware$freeCamThirdPerson(boolean thirdPerson) {
		return thirdPerson || FemboyWare.isEnabled(FreeCamModule.class);
	}

	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
	private void femboyware$noCameraClip(float distance, CallbackInfoReturnable<Float> cir) {
		if (FemboyWare.isEnabled(NoCameraClipModule.class)) cir.setReturnValue(FemboyWare.getModules().get(NoCameraClipModule.class).getDistance());
	}

	private static boolean isSideView(Entity focusedEntity) {
		return focusedEntity == Minecraft.getInstance().player && FemboyWare.getModules() != null
			&& FemboyWare.getModules().get(LegitAuraModule.class).sideView();
	}

	private static boolean isFreeLook(Entity focusedEntity) {
		return focusedEntity == Minecraft.getInstance().player && FemboyWare.isEnabled(FreeLookModule.class);
	}
}
