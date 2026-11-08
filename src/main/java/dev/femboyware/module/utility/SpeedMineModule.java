package dev.femboyware.module.utility;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.mixin.ClientPlayerInteractionManagerAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Mines blocks faster, by speed multiplier, fake Haste, or finishing blocks early.
 * Ported from Meteor Client's SpeedMine (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class SpeedMineModule extends Module {
	/** Damage mode finishes the block once this much of it is broken. */
	private static final float DAMAGE_THRESHOLD = 0.7f;

	public enum Mode { NORMAL, HASTE, DAMAGE }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Normal multiplies speed, Haste fakes the effect, Damage finishes blocks at 70%", Mode.DAMAGE));
	private final NumberSetting modifier = add(new NumberSetting("Modifier", "Speed multiplier, 0.2 extra is about one Haste level", 1.4, 1, 3, 0.05)).visibleWhen(() -> mode.is(Mode.NORMAL));
	private final NumberSetting hasteAmplifier = add(new NumberSetting("Haste Level", "Haste level to give yourself, above 2 not recommended", 2, 1, 5, 1)).visibleWhen(() -> mode.is(Mode.HASTE));
	private final BoolSetting grimBypass = add(new BoolSetting("Grim Bypass", "Abort a block above each one you finish, which gets past Grim's fast break check", false)).visibleWhen(() -> mode.is(Mode.DAMAGE));

	private Mode lastMode;

	public SpeedMineModule() {
		super("Speed Mine", "Mines blocks faster", Category.WORLD);
	}

	@Override
	protected void onDisable() {
		if (!nullCheck()) removeHaste();
		lastMode = null;
	}

	/** Multiplier applied to the local player's mining speed. */
	public float speedMultiplier() {
		return isEnabled() && mode.is(Mode.NORMAL) ? modifier.getFloat() : 1f;
	}

	@Override
	public void onTick() {
		if (lastMode != mode.get()) {
			removeHaste();
			lastMode = mode.get();
		}

		if (mode.is(Mode.HASTE)) {
			int amplifier = hasteAmplifier.getInt() - 1;
			MobEffectInstance haste = mc.player.getEffect(MobEffects.HASTE);
			if (haste == null || haste.getAmplifier() < amplifier) {
				mc.player.addEffect(new MobEffectInstance(MobEffects.HASTE, -1, amplifier, false, false, false));
			}
		} else if (mode.is(Mode.DAMAGE)) {
			ClientPlayerInteractionManagerAccessor manager = (ClientPlayerInteractionManagerAccessor) mc.gameMode;
			float progress = manager.femboyware$getBreakingProgress();
			BlockPos pos = manager.femboyware$getBreakingPos();
			if (pos == null || progress <= 0) return;
			float delta = mc.level.getBlockState(pos).getDestroyProgress(mc.player, mc.level, pos);
			if (progress + delta >= DAMAGE_THRESHOLD) manager.femboyware$setBreakingProgress(1f);
		}
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!mode.is(Mode.DAMAGE) || !grimBypass.get()) return;
		// https://github.com/GrimAnticheat/Grim/issues/1296
		if (event.getPacket() instanceof ServerboundPlayerActionPacket packet && packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
			mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, packet.getPos().above(), packet.getDirection()));
		}
	}

	/** Removes only the hidden Haste we gave ourselves, never a real one. */
	private void removeHaste() {
		MobEffectInstance haste = mc.player.getEffect(MobEffects.HASTE);
		if (haste != null && !haste.showIcon()) mc.player.removeEffect(MobEffects.HASTE);
	}
}
