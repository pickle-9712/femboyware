package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;

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
		if (mc.player.isGliding() || mc.player.hasVehicle() || mc.player.getAbilities().flying || mc.player.isClimbing()) return;
		if (mc.player.isOnGround()) {
			if (autoJump.get() && LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST) && mc.player.input.hasForwardMovement()) mc.player.jump();
			return;
		}
		if (mode.is(Mode.JUMP_HELD) && !mc.options.jumpKey.isPressed()) return;
		if (mc.player.checkGliding()) {
			mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
		}
	}
}
