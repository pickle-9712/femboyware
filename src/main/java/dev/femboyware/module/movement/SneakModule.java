package dev.femboyware.module.movement;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;

/**
 * Sneaks for you. Vanilla sneaks for real; Packet only tells the server you're sneaking, so you
 * keep full speed while others see you crouch and you don't fall off edges server-side.
 * Ported from Meteor Client's Sneak (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class SneakModule extends Module {
	public enum Mode { VANILLA, PACKET }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Vanilla sneaks for real, Packet only on the server", Mode.VANILLA));

	public SneakModule() {
		super("Sneak", "Sneaks for you", Category.BASIC);
	}

	@Override
	protected void onEnable() {
		if (!nullCheck() && mode.is(Mode.PACKET)) mc.getNetworkHandler().sendPacket(new PlayerInputC2SPacket(withSneak(mc.player.input.playerInput, true)));
	}

	@Override
	protected void onDisable() {
		// Tell the server what we're really doing again
		if (!nullCheck() && mode.is(Mode.PACKET)) mc.getNetworkHandler().sendPacket(new PlayerInputC2SPacket(mc.player.input.playerInput));
	}

	@Override
	public void onMovementInput() {
		if (!mode.is(Mode.VANILLA) || mc.player.getAbilities().flying) return;
		mc.player.input.playerInput = withSneak(mc.player.input.playerInput, true);
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!mode.is(Mode.PACKET) || mc.player.getAbilities().flying) return;
		if (!(event.getPacket() instanceof PlayerInputC2SPacket packet) || packet.input().sneak()) return;
		event.cancel();
		mc.getNetworkHandler().sendPacket(new PlayerInputC2SPacket(withSneak(packet.input(), true)));
	}

	private static PlayerInput withSneak(PlayerInput input, boolean sneak) {
		return new PlayerInput(input.forward(), input.backward(), input.left(), input.right(), input.jump(), sneak, input.sprint());
	}
}
