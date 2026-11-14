package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

/**
 * Crystals you hit are removed on your side straight away instead of when the server says so, so
 * you can place the next one into the same spot a ping sooner.
 */
public class CrystalOptimizerModule extends Module {
	public CrystalOptimizerModule() {
		super("Crystal Optimizer", "Crystals you break disappear at once client-side for faster placement", Category.ATTACK);
	}

	@Override
	public void onPostAttack(Entity target) {
		if (!(target instanceof EndCrystal crystal) || crystal.isRemoved()) return;
		// With Weakness and no weapon the hit does nothing, so the crystal stays; don't hide it then
		if (mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) <= 0) return;
		mc.level.removeEntity(crystal.getId(), Entity.RemovalReason.KILLED);
	}
}
