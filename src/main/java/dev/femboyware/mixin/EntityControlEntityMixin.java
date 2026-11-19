package dev.femboyware.mixin;

import dev.femboyware.util.VehicleControl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Entity Control and Boat Noclip: steer the vehicle you're driving. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(Entity.class)
public abstract class EntityControlEntityMixin {
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, require = 0)
	private Vec3 femboyware$vehicleMove(Vec3 movement, MoverType type) {
		return VehicleControl.onEntityMove((Entity) (Object) this, type, movement);
	}
}
