package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;

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
		if (!(target instanceof LivingEntity) || mc.player.input.getMovementInput().lengthSquared() == 0) return;
		if (mc.player.isSprinting()) {
			mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
		}
		mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
		mc.player.setSprinting(true);
	}
}
