package dev.femboyware.module.movement;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

/**
 * Jump much further than normal.
 * Ported from Meteor Client's LongJump (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 * Meteor's Vanilla mode and Timer option aren't included.
 */
public class LongJumpModule extends Module {
	/** Vanilla sprint-jump ground speed, the base Burst scales from. */
	private static final double BASE_SPEED = 0.2873;
	/** Horizontal speed per air tick in Glide mode, from Meteor. */
	private static final float[] GLIDE_MOTION = {0.4206065f, 0.4179245f, 0.41525924f, 0.41261f, 0.409978f, 0.407361f, 0.404761f, 0.402178f, 0.399611f, 0.39706f, 0.394525f, 0.392f, 0.3894f, 0.38644f, 0.383655f, 0.381105f, 0.37867f, 0.37625f, 0.37384f, 0.37145f, 0.369f, 0.3666f, 0.3642f, 0.3618f, 0.35945f, 0.357f, 0.354f, 0.351f, 0.348f, 0.345f, 0.342f, 0.339f, 0.336f, 0.333f, 0.33f, 0.327f, 0.324f, 0.321f, 0.318f, 0.315f, 0.312f, 0.309f, 0.307f, 0.305f, 0.303f, 0.3f, 0.297f, 0.295f, 0.293f, 0.291f, 0.289f, 0.287f, 0.285f, 0.283f, 0.281f, 0.279f, 0.277f, 0.275f, 0.273f, 0.271f, 0.269f, 0.267f, 0.265f, 0.263f, 0.261f, 0.259f, 0.257f, 0.255f, 0.253f, 0.251f, 0.249f, 0.247f, 0.245f, 0.243f, 0.241f, 0.239f, 0.237f};
	/** Fall speed multipliers for the first air ticks after the peak in Glide mode, from Meteor. */
	private static final float[] GLIDE_FALL = {0.3425f, 0.5445f, 0.65425f, 0.685f, 0.675f, 0.2f, 0.895f, 0.719f, 0.76f};

	public enum Mode { BURST, GLIDE }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Burst launches you forward, Glide slows your fall", Mode.BURST));
	private final NumberSetting burstInitialSpeed = add(new NumberSetting("Initial Speed", "Starting speed multiplier", 6, 0, 20, 0.1)).visibleWhen(() -> mode.is(Mode.BURST));
	private final NumberSetting burstBoostFactor = add(new NumberSetting("Boost Factor", "Speed multiplier on take-off", 2.149, 0, 5, 0.01)).visibleWhen(() -> mode.is(Mode.BURST));
	private final BoolSetting onlyOnGround = add(new BoolSetting("Only On Ground", "Only start a jump from the ground", true)).visibleWhen(() -> mode.is(Mode.BURST));
	private final BoolSetting onJump = add(new BoolSetting("On Jump", "Only boost while holding jump", false)).visibleWhen(() -> mode.is(Mode.BURST));
	private final NumberSetting glideMultiplier = add(new NumberSetting("Glide Multiplier", "How strong the glide is", 1, 0, 3, 0.05)).visibleWhen(() -> mode.is(Mode.GLIDE));
	private final BoolSetting autoDisable = add(new BoolSetting("Auto Disable", "Turn off after one jump", true));
	private final BoolSetting disableOnRubberband = add(new BoolSetting("Disable On Rubberband", "Turn off if the server teleports you back", true));

	private int stage;
	private double moveSpeed;
	private boolean jumping;
	private boolean jumped;
	private int airTicks;
	private int groundTicks;
	private Vec3 lastPos;

	public LongJumpModule() {
		super("Long Jump", "Jump further", Category.RAGE);
	}

	@Override
	protected void onEnable() {
		stage = 0;
		jumping = false;
		jumped = false;
		airTicks = 0;
		groundTicks = -5;
		lastPos = nullCheck() ? null : mc.player.position();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (event.getPacket() instanceof ClientboundPlayerPositionPacket && disableOnRubberband.get()) mc.execute(() -> setEnabled(false));
	}

	@Override
	public void onMovementInput() {
		if (mode.is(Mode.BURST)) burst();
		else glide();
		lastPos = mc.player.position();
	}

	private void burst() {
		if (stage != 0 && !mc.player.onGround() && autoDisable.get()) jumping = true;
		if (jumping && mc.player.getY() - Math.floor(mc.player.getY()) < 0.01) {
			setEnabled(false);
			return;
		}
		if (onlyOnGround.get() && !mc.player.onGround() && stage == 0) return;
		if (!MoveUtil.isMoving() || (onJump.get() && !mc.options.keyJump.isDown()) || mc.player.isInLava() || mc.player.isInWater()) return;

		double lastDist = lastPos == null ? 0 : Math.hypot(mc.player.getX() - lastPos.x, mc.player.getZ() - lastPos.z);
		double y = mc.player.getDeltaMovement().y;
		switch (stage) {
			case 0 -> moveSpeed = moveSpeed() * burstInitialSpeed.get();
			case 1 -> {
				y = 0.42;
				moveSpeed *= burstBoostFactor.get();
			}
			case 2 -> moveSpeed = moveSpeed();
			default -> moveSpeed = lastDist - lastDist / 159;
		}
		Vec3 horizontal = MoveUtil.strafe(moveSpeed);
		mc.player.setDeltaMovement(horizontal.x, y, horizontal.z);
		stage++;
	}

	private void glide() {
		if (!MoveUtil.isMoving()) return;
		double yaw = Math.toRadians(mc.player.getYRot() + 90);
		double forward = Math.signum(mc.player.input.getMoveVector().y);
		double cos = Math.cos(yaw), sin = Math.sin(yaw);
		double multiplier = glideMultiplier.get();

		if (!mc.player.verticalCollision && !mc.player.onGround()) {
			jumped = true;
			airTicks++;
			groundTicks = -5;
			double y = mc.player.getDeltaMovement().y;
			if (airTicks - 6 >= 0 && airTicks - 6 < GLIDE_FALL.length) y = y * GLIDE_FALL[airTicks - 6] * multiplier;
			if (y < -0.2 && y > -0.24) y *= 0.7 * multiplier;
			else if (y < -0.25 && y > -0.32) y *= 0.8 * multiplier;
			else if (y < -0.35 && y > -0.8) y *= 0.98 * multiplier;
			double speed = airTicks - 1 < GLIDE_MOTION.length ? GLIDE_MOTION[airTicks - 1] * 3 * multiplier : 0;
			mc.player.setDeltaMovement(forward * speed * cos, y, forward * speed * sin);
			return;
		}

		if (autoDisable.get() && jumped) {
			setEnabled(false);
			return;
		}
		airTicks = 0;
		groundTicks++;
		// Creep for a couple of ticks to settle, then hop
		if (groundTicks <= 2) mc.player.setDeltaMovement(forward * 0.01 * cos * multiplier, mc.player.getDeltaMovement().y, forward * 0.01 * sin * multiplier);
		else mc.player.setDeltaMovement(forward * 0.3 * cos * multiplier, 0.424, forward * 0.3 * sin * multiplier);
	}

	private double moveSpeed() {
		MobEffectInstance speed = mc.player.getEffect(MobEffects.SPEED);
		return speed == null ? BASE_SPEED : BASE_SPEED * (1 + 0.2 * (speed.getAmplifier() + 1));
	}
}
