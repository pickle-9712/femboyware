package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.LegitAuraModule;
import dev.femboyware.module.larp.CinematicCameraModule;
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

	@Shadow
	@org.spongepowered.asm.mixin.Final
	private org.joml.Vector3f forwards;

	@Shadow
	@org.spongepowered.asm.mixin.Final
	private org.joml.Vector3f up;

	@Shadow
	@org.spongepowered.asm.mixin.Final
	private org.joml.Vector3f left;

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
		float tickProgress = ((Camera) (Object) this).getCameraEntityPartialTicks(deltaTracker);
		if (!FemboyWare.isEnabled(FreeCamModule.class)) {
			cinematic(tickProgress);
			return;
		}
	*///?} else {
	@Inject(method = "setup", at = @At("TAIL"))
	private void femboyware$freeCam(Level area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
		if (!FemboyWare.isEnabled(FreeCamModule.class)) {
			cinematic(tickProgress);
			return;
		}
	//?}
		FreeCamModule freeCam = FemboyWare.getModules().get(FreeCamModule.class);
		setRotation(freeCam.getYaw(), freeCam.getPitch());
		setPosition(freeCam.getPos(tickProgress));
	}

	/** Free Cam renders your body, which vanilla only does in third person. */
	@ModifyReturnValue(method = "isDetached", at = @At("RETURN"))
	private boolean femboyware$freeCamThirdPerson(boolean thirdPerson) {
		return thirdPerson || FemboyWare.isEnabled(FreeCamModule.class) || FemboyWare.isEnabled(CinematicCameraModule.class);
	}

	/** Cinematic Camera: puts the camera where the shot wants it. */
	private void cinematic(float tickProgress) {
		if (FemboyWare.getModules() == null) return;
		// Other people's captains, placed in their seats for this exact frame
		FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).placeFrame();
		// You in your car's seat, exactly where it is this frame
		FemboyWare.getModules().get(dev.femboyware.module.larp.CarsModule.class).placeFrame();
		CinematicCameraModule.View jet = FemboyWare.getModules().get(dev.femboyware.module.larp.JetModule.class).view(tickProgress);
		org.joml.Quaternionf turn = FemboyWare.getModules().get(dev.femboyware.module.larp.JetModule.class).cameraRotation();
		CinematicCameraModule.View heli = FemboyWare.getModules().get(dev.femboyware.module.larp.HelicopterModule.class).view(tickProgress);
		if (jet == null && heli != null) {
			jet = heli;
			turn = FemboyWare.getModules().get(dev.femboyware.module.larp.HelicopterModule.class).cameraRotation();
		}
		// In a car: the camera goes exactly where the car is this frame
		CinematicCameraModule.View car = FemboyWare.getModules().get(dev.femboyware.module.larp.CarsModule.class).view(tickProgress);
		if (jet == null && car != null) {
			jet = car;
			turn = null;
		}
		if (jet != null) {
			setRotation(jet.yaw(), jet.pitch());
			setPosition(jet.pos());
			// Flying in first person the camera tips and rolls with the jet
			if (turn != null) {
				((Camera) (Object) this).rotation().set(turn);
				forwards.set(0, 0, -1).rotate(turn);
				up.set(0, 1, 0).rotate(turn);
				left.set(-1, 0, 0).rotate(turn);
			}
			return;
		}
		CinematicCameraModule.View view = FemboyWare.getModules().get(CinematicCameraModule.class).view(tickProgress);
		if (view == null) {
			// Up on the Maybach's bonnet for its bounce emote, so the camera rides up there with you
			float lift = FemboyWare.getModules().get(dev.femboyware.module.render.EmotesModule.class).cameraLift(tickProgress);
			if (lift > 0) setPosition(((Camera) (Object) this).position().add(0, lift, 0));
			return;
		}
		setRotation(view.yaw(), view.pitch());
		setPosition(view.pos());
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
