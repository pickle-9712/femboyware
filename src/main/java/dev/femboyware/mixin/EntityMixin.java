package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.movement.AntiWebModule;
import dev.femboyware.module.movement.NoPushModule;
import dev.femboyware.module.movement.NoSlowModule;
import dev.femboyware.module.render.FreeLookModule;
import dev.femboyware.module.render.PopChamsModule;
import dev.femboyware.module.utility.FreeCamModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {
	/**
	 * The cars, jet, helicopter and hangar: their boxes join what you bump into while Server Larp is
	 * on, and what larp-dropped items land on. Optional, in case a version moves this call.
	 */
	@ModifyExpressionValue(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"), require = 0)
	private java.util.List<net.minecraft.world.phys.shapes.VoxelShape> femboyware$carCollisions(java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes) {
		Entity self = (Entity) (Object) this;
		if (!dev.femboyware.module.larp.LarpProps.solidFor(self)) return shapes;
		net.minecraft.world.phys.AABB area = self.getBoundingBox().expandTowards(self.getDeltaMovement()).inflate(1);
		java.util.List<net.minecraft.world.phys.shapes.VoxelShape> cars = dev.femboyware.module.larp.LarpProps.shapes(self, area);
		if (cars.isEmpty()) return shapes;
		java.util.List<net.minecraft.world.phys.shapes.VoxelShape> all = new java.util.ArrayList<>(shapes);
		all.addAll(cars);
		return all;
	}

	/** Turns movement input into velocity using the server yaw while a silent rotation is active. */
	@ModifyExpressionValue(method = "moveRelative", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getYRot()F"))
	private float femboyware$moveFixYaw(float yaw) {
		if ((Object) this != Minecraft.getInstance().player || FemboyWare.getRotations() == null) return yaw;
		return FemboyWare.getRotations().isMoveFixActive() ? FemboyWare.getRotations().getServerYaw() : yaw;
	}

	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void femboyware$noEntityPush(Entity entity, CallbackInfo ci) {
		if (isLocalPlayer() && FemboyWare.getModules().get(NoPushModule.class).entities()) ci.cancel();
		else if (FemboyWare.getModules() != null && FemboyWare.getModules().get(FakePlayerModule.class).ignoresPush((Entity) (Object) this, entity)) ci.cancel();
		else if (FemboyWare.getModules() != null && (FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).isActor((Entity) (Object) this)
			|| FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).isActor(entity))) ci.cancel();
	}

	@ModifyReturnValue(method = "isPushedByFluid", at = @At("RETURN"))
	private boolean femboyware$noFluidPush(boolean pushed) {
		return pushed && !(isLocalPlayer() && FemboyWare.getModules().get(NoPushModule.class).liquids());
	}

	@Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true)
	private void femboyware$antiWeb(BlockState state, Vec3 multiplier, CallbackInfo ci) {
		if (isLocalPlayer() && FemboyWare.getModules().get(AntiWebModule.class).ignores(state)) ci.cancel();
	}

	@ModifyReturnValue(method = "getBlockSpeedFactor", at = @At("RETURN"))
	private float femboyware$noBlockSlow(float multiplier) {
		return isLocalPlayer() && FemboyWare.getModules().get(NoSlowModule.class).blocks() ? Math.max(multiplier, 1f) : multiplier;
	}

	/** Free Look and Free Cam take the mouse instead of the player. */
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
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

	@ModifyReturnValue(method = "getTeamColor", at = @At("RETURN"))
	private int femboyware$popChamsColor(int color) {
		if (!FemboyWare.isEnabled(PopChamsModule.class)) return color;
		PopChamsModule popChams = FemboyWare.getModules().get(PopChamsModule.class);
		return popChams.isGhost((Entity) (Object) this) ? popChams.getColor() : color;
	}

	private boolean isLocalPlayer() {
		return FemboyWare.getModules() != null && (Object) this == Minecraft.getInstance().player;
	}
}
