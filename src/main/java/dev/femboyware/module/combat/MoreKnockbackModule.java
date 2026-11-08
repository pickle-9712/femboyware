package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Resends the sprint state before every hit. The server stops your sprint after a sprint hit, so
 * without this only the first hit of a combo gets the extra knockback.
 */
public class MoreKnockbackModule extends Module {
	public MoreKnockbackModule() {
		super("More Knockback", "Every hit deals sprint knockback", Category.ATTACK);
	}

	@Override
	public void onPreAttack(Entity target) {
		if (!(target instanceof LivingEntity) || mc.player.input.getMoveVector().lengthSquared() == 0) return;
		if (mc.player.isSprinting()) {
			mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
		}
		mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
		mc.player.setSprinting(true);
	}
}
