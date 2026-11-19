package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.module.movement.BoatNoclipModule;
import dev.femboyware.util.VehicleControl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Entity Control and Boat Noclip: no A/D turning while the boat follows your view, and no collisions in noclip. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(AbstractBoat.class)
public abstract class EntityControlBoatMixin {
	@ModifyExpressionValue(method = "controlBoat", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;inputLeft:Z"), require = 0)
	private boolean femboyware$inputLeft(boolean original) {
		return original && !VehicleControl.locksBoatTurning((AbstractBoat) (Object) this);
	}

	@ModifyExpressionValue(method = "controlBoat", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;inputRight:Z"), require = 0)
	private boolean femboyware$inputRight(boolean original) {
		return original && !VehicleControl.locksBoatTurning((AbstractBoat) (Object) this);
	}

	@ModifyReturnValue(method = "canCollideWith", at = @At("RETURN"), require = 0)
	private boolean femboyware$noclipCollide(boolean original) {
		if (!original || BoatNoclipModule.active() == null) return original;
		AbstractBoat boat = (AbstractBoat) (Object) this;
		return !boat.level().isClientSide() || boat.getControllingPassenger() != Minecraft.getInstance().player;
	}
}
