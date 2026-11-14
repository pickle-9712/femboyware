package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * Speed that Grim lets through: brushing up against other entities is allowed to push you a little
 * each tick, so every entity you're touching adds a small push the way you're walking.
 */
public class GrimSpeedModule extends Module {
	private final NumberSetting boost = add(new NumberSetting("Boost", "Push per entity you're touching, in blocks per tick", 0.08, 0.01, 0.08, 0.005));
	private final NumberSetting reach = add(new NumberSetting("Reach", "How close an entity counts as touching", 1.5, 0.5, 2, 0.1));
	private final NumberSetting most = add(new NumberSetting("Max Entities", "Most entities to count at once", 3, 1, 10, 1));
	private final BoolSetting onlyLiving = add(new BoolSetting("Only Living", "Only count players and mobs", true));

	public GrimSpeedModule() {
		super("Grim Speed", "Speeds you up past Grim using nearby entities", Category.RAGE);
	}

	@Override
	public void onTick() {
		var p = mc.player;
		if (!MoveUtil.isMoving() || p.isFallFlying() || p.isPassenger()) return;
		int touching = 0;
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(reach.get() - 1 + 0.5))) {
			if (onlyLiving.get() && (!(e instanceof LivingEntity) || e instanceof ArmorStand)) continue;
			if (!e.isAlive() || e.isSpectator()) continue;
			if (++touching >= most.getInt()) break;
		}
		if (touching == 0) return;
		Vec3 push = MoveUtil.strafe(boost.get() * touching);
		p.setDeltaMovement(p.getDeltaMovement().add(push.x, 0, push.z));
	}
}
