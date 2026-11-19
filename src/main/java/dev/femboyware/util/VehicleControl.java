package dev.femboyware.util;

import dev.femboyware.module.movement.BoatNoclipModule;
import dev.femboyware.module.movement.EntityControlModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;

/**
 * The vehicle movement hook behind Entity Control and Boat Noclip. Ported from the Anubis client's
 * EntityControlVehicleHook (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public final class VehicleControl {
	private static final double DIAGONAL = 1.0 / Math.sqrt(2.0);

	private VehicleControl() {
	}

	public static Vec3 onEntityMove(Entity entity, MoverType type, Vec3 movement) {
		if (type != MoverType.SELF || !entity.level().isClientSide()) return movement;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || player.getVehicle() != entity || entity.getControllingPassenger() != player) return movement;
		BoatNoclipModule noclip = BoatNoclipModule.active();
		if (noclip != null && entity instanceof AbstractBoat boat) {
			Vec3 velocity = entity.getDeltaMovement();
			Vec3 result = noclip.boatMovement(boat, player);
			entity.setYRot(player.getYRot());
			if (movement == velocity) entity.setDeltaMovement(result);
			return result;
		}
		EntityControlModule control = EntityControlModule.active();
		if (control != null && control.locksYaw(entity)) entity.setYRot(player.getYRot());
		return movement;
	}

	public static boolean locksBoatTurning(AbstractBoat boat) {
		if (!boat.level().isClientSide()) return false;
		if (BoatNoclipModule.active() != null) return true;
		EntityControlModule control = EntityControlModule.active();
		return control != null && control.locksYaw(boat);
	}

	public static Vec3 horizontalVelocity(LocalPlayer player, double bps) {
		float yaw = player.getYRot();
		Vec3 forward = Vec3.directionFromRotation(0, yaw), right = Vec3.directionFromRotation(0, yaw + 90);
		Input input = player.input.keyPresses;
		double x = 0, z = 0;
		boolean longitudinal = false, lateral = false;
		if (input.forward()) {
			x += forward.x / 20 * bps;
			z += forward.z / 20 * bps;
			longitudinal = true;
		}
		if (input.backward()) {
			x -= forward.x / 20 * bps;
			z -= forward.z / 20 * bps;
			longitudinal = true;
		}
		if (input.right()) {
			x += right.x / 20 * bps;
			z += right.z / 20 * bps;
			lateral = true;
		}
		if (input.left()) {
			x -= right.x / 20 * bps;
			z -= right.z / 20 * bps;
			lateral = true;
		}
		if (longitudinal && lateral) {
			x *= DIAGONAL;
			z *= DIAGONAL;
		}
		return new Vec3(x, 0, z);
	}

	public static boolean isOnAir(Entity entity) {
		return entity.level().getBlockStates(entity.getBoundingBox().inflate(0.0625).expandTowards(0, -0.55, 0)).allMatch(BlockBehaviour.BlockStateBase::isAir);
	}
}
