package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * Re-deploys your elytra the moment you stop gliding, and can jump off the ground straight away,
 * which lets you bounce along at elytra speed.
 */
public class ElytraRecastModule extends Module {
	public enum Mode {
		/** Recast whenever you're airborne. */
		ALWAYS,
		/** Recast while holding jump. */
		JUMP_HELD
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "When to recast", Mode.ALWAYS));
	private final BoolSetting autoJump = add(new BoolSetting("Auto Jump", "Jump as soon as you land while moving forward", true));

	public ElytraRecastModule() {
		super("Elytra Recast", "Automatically restarts elytra flight", Category.BASIC);
	}

	@Override
	public void onTick() {
		if (mc.player.isFallFlying() || mc.player.isPassenger() || mc.player.getAbilities().flying || mc.player.onClimbable()) return;
		if (mc.player.onGround()) {
			if (autoJump.get() && LivingEntity.canGlideUsing(mc.player.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST) && mc.player.input.hasForwardImpulse()) mc.player.jumpFromGround();
			return;
		}
		if (mode.is(Mode.JUMP_HELD) && !mc.options.keyJump.isDown()) return;
		if (mc.player.tryToStartFallFlying()) {
			mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		}
	}
}
