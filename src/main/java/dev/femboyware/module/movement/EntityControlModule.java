package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Ride and steer mounts without a saddle, with full horse jumps and the vehicle facing where you
 * look. Ported from the Anubis client's Entity Control (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public class EntityControlModule extends Module {
	private final BoolSetting boats = add(new BoolSetting("Boats", "Steer boats where you look", true));
	private final BoolSetting horses = add(new BoolSetting("Horses", "Horses, donkeys, mules", true));
	private final BoolSetting camels = add(new BoolSetting("Camels", "Camels", true));
	private final BoolSetting pigs = add(new BoolSetting("Pigs", "Pigs", true));
	private final BoolSetting striders = add(new BoolSetting("Striders", "Striders", true));
	private final BoolSetting nautiluses = add(new BoolSetting("Nautiluses", "Nautiluses", true));
	private final BoolSetting happyGhasts = add(new BoolSetting("Happy Ghasts", "Happy ghasts", true));
	private final BoolSetting spoofSaddle = add(new BoolSetting("Spoof Saddle", "Treat every mount as saddled", false));
	private final BoolSetting maxJump = add(new BoolSetting("Max Jump", "Horse jumps are always fully charged", true));
	private final BoolSetting lockYaw = add(new BoolSetting("Lock Yaw", "The mount faces where you look", true));
	private final BoolSetting cancelServer = add(new BoolSetting("Cancel Server Packets", "Ignore the server moving your vehicle back", false));

	public EntityControlModule() {
		super("Entity Control", "Control mounts without a saddle", Category.BASIC);
	}

	public static EntityControlModule active() {
		if (FemboyWare.getModules() == null) return null;
		EntityControlModule module = FemboyWare.getModules().get(EntityControlModule.class);
		return module != null && module.isEnabled() ? module : null;
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (cancelServer.get() && event.getPacket() instanceof ClientboundMoveVehiclePacket) event.cancel();
	}

	public static boolean spoofSaddle() {
		EntityControlModule module = active();
		return module != null && module.spoofSaddle.get();
	}

	public static boolean maxJump() {
		EntityControlModule module = active();
		return module != null && module.maxJump.get();
	}

	public boolean locksYaw(Entity vehicle) {
		return lockYaw.get() && isTarget(vehicle);
	}

	private boolean isTarget(Entity entity) {
		if (entity instanceof AbstractBoat) return boats.get();
		EntityType<?> type = entity.getType();
		if (type == EntityType.HORSE || type == EntityType.DONKEY || type == EntityType.MULE || type == EntityType.SKELETON_HORSE || type == EntityType.ZOMBIE_HORSE) return horses.get();
		if (type == EntityType.CAMEL || type == EntityType.CAMEL_HUSK) return camels.get();
		if (type == EntityType.PIG) return pigs.get();
		if (type == EntityType.STRIDER) return striders.get();
		if (type == EntityType.NAUTILUS || type == EntityType.ZOMBIE_NAUTILUS) return nautiluses.get();
		return type == EntityType.HAPPY_GHAST && happyGhasts.get();
	}
}
