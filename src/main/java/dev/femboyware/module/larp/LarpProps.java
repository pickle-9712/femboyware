package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.utility.ServerLarpModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** The cars, jet, helicopter and hangar as solid shapes, and who bumps into them. */
public final class LarpProps {
	private LarpProps() {
	}

	/**
	 * Whether {@code entity} collides with the props. You do while Server Larp is on (otherwise the
	 * server, which can't see them, would snap you back). Fake players and items that only exist here
	 * (the ones dropped while larping) always do, since nothing else decides where they go.
	 */
	public static boolean solidFor(Entity entity) {
		if (FemboyWare.getModules() == null || entity == null) return false;
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player) return FemboyWare.getModules().get(ServerLarpModule.class).isLarping();
		if (entity instanceof ItemEntity) return entity.getId() < 0;
		// Valets walk on the hangar floor and the jet's stairs like anyone (they already walk round the cars)
		if (FemboyWare.getModules().get(CarsModule.class).isValet(entity)) return true;
		return FemboyWare.getModules().get(FakePlayerModule.class).isFake(entity.getId());
	}

	/** Every prop's boxes {@code entity} could hit in {@code area}; empty if it doesn't collide with them. */
	public static List<VoxelShape> shapes(Entity entity, AABB area) {
		if (!solidFor(entity)) return List.of();
		List<VoxelShape> shapes = new ArrayList<>(FemboyWare.getModules().get(CarsModule.class).collisions(entity, area));
		shapes.addAll(FemboyWare.getModules().get(JetModule.class).collisions(entity, area));
		shapes.addAll(FemboyWare.getModules().get(HangarModule.class).collisions(entity, area));
		shapes.addAll(FemboyWare.getModules().get(HelicopterModule.class).collisions(entity, area));
		shapes.addAll(FemboyWare.getModules().get(YachtModule.class).collisions(entity, area));
		shapes.addAll(FemboyWare.getModules().get(MansionModule.class).collisions(entity, area));
		// Other femboyware users' props too, from Client Sync
		shapes.addAll(FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).propCollisions(entity, area));
		return shapes;
	}

	/** Whether {@code box} is clear of every prop's boxes, for movement that checks spaces itself. */
	public static boolean clear(Entity entity, AABB box) {
		return shapes(entity, box.deflate(1e-7)).isEmpty();
	}
}
