package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

/** Makes every grounded hit a critical by faking a tiny hop with position packets. */
public class CriticalsModule extends Module {
	public enum Mode {
		/** One small hop, enough for vanilla servers. */
		PACKET,
		/** The classic NCP offsets. */
		STRICT
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Which offsets to send", Mode.PACKET));

	public CriticalsModule() {
		super("Criticals", "Turns every hit into a critical hit", Category.ATTACK);
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	@Override
	public void onPreAttack(Entity target) {
		if (!(target instanceof LivingEntity) || target instanceof EndCrystal) return;
		if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable() || mc.player.isPassenger()) return;

		double[] offsets = mode.is(Mode.STRICT) ? new double[]{0.11, 0.1100013579, 0.0000013579} : new double[]{0.0625, 0};
		for (double offset : offsets) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
				mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(), false, mc.player.horizontalCollision));
		}
	}
}
