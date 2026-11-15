/*
 * The Reversal, Strafe, Jump Reset, Reduce and Grim modes are ported from LiquidBounce
 * (https://github.com/CCBlueX/LiquidBounce): VelocityReversal, VelocityStrafe, VelocityJumpReset,
 * VelocityReduce and VelocityGrim2371, plus ModuleVelocity's Pause On Flag.
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified for femboyware: ported from Kotlin to Java and merged into femboyware's Anti Knock Back
 * (whose percentage scaling is the Modify mode). Reduce keeps its attack reduction but not its lag
 * part, Strafe's Only Facing uses Aura's target or the crosshair, and Grim holds incoming packets
 * in its own queue and stops your movement while it waits, rather than cancelling the player tick.
 */
package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.lb.TargetFinding;
import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.combat.AuraModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.MoveUtil;
import dev.femboyware.util.Rotation;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/** Stops or reshapes the knockback you take. */
public class AntiKnockBackModule extends Module {
	public enum Mode { MODIFY, REVERSAL, STRAFE, JUMP_RESET, REDUCE, GRIM }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Modify scales it; the others are LiquidBounce's anti-cheat friendly tricks", Mode.MODIFY));
	// Modify
	private final NumberSetting horizontal = add(new NumberSetting("Horizontal", "Percent of horizontal knockback to keep", 0, 0, 100, 1)).visibleWhen(() -> mode.is(Mode.MODIFY));
	private final NumberSetting vertical = add(new NumberSetting("Vertical", "Percent of vertical knockback to keep", 0, 0, 100, 1)).visibleWhen(() -> mode.is(Mode.MODIFY));
	private final BoolSetting hits = add(new BoolSetting("Hits", "Knockback from hits, arrows, fishing rods and anything else that shoves you", true)).visibleWhen(() -> mode.is(Mode.MODIFY));
	private final BoolSetting explosions = add(new BoolSetting("Explosions", "Knockback from crystals, anchors, TNT, beds and wind charges", true)).visibleWhen(() -> mode.is(Mode.MODIFY));
	// Reversal
	private final NumberSetting reversalDelay = add(new NumberSetting("Reversal Delay", "Ticks after the hit before turning the knock around", 2, 1, 5, 1)).visibleWhen(() -> mode.is(Mode.REVERSAL));
	private final NumberSetting reversalX = add(new NumberSetting("Reversal X", "How much of the X motion to send back", 0.5, 0, 1, 0.05)).visibleWhen(() -> mode.is(Mode.REVERSAL));
	private final NumberSetting reversalZ = add(new NumberSetting("Reversal Z", "How much of the Z motion to send back", 0.5, 0, 1, 0.05)).visibleWhen(() -> mode.is(Mode.REVERSAL));
	private final BoolSetting reversalMoving = add(new BoolSetting("Only Moving", "Only while you hold a movement key", false)).visibleWhen(() -> mode.is(Mode.REVERSAL));
	// Strafe
	private final NumberSetting strafeDelay = add(new NumberSetting("Strafe Delay", "Ticks after the hit before steering", 2, 0, 10, 1)).visibleWhen(() -> mode.is(Mode.STRAFE));
	private final NumberSetting strafeStrength = add(new NumberSetting("Strength", "Speed kept, steered where you walk", 1, 0.1, 2, 0.05)).visibleWhen(() -> mode.is(Mode.STRAFE));
	private final BoolSetting onlyFacing = add(new BoolSetting("Only Facing", "Only when you face an enemy", false)).visibleWhen(() -> mode.is(Mode.STRAFE));
	private final NumberSetting facingRange = add(new NumberSetting("Facing Range", "How close that enemy has to be", 3.5, 0.1, 6, 0.1)).visibleWhen(() -> mode.is(Mode.STRAFE) && onlyFacing.get());
	private final BoolSetting untilGround = add(new BoolSetting("Until Ground", "Keep steering until you land", false)).visibleWhen(() -> mode.is(Mode.STRAFE));
	// Jump reset
	private final NumberSetting jumpChance = add(new NumberSetting("Chance", "Percent of hits to jump on", 100, 0, 100, 1)).visibleWhen(() -> mode.is(Mode.JUMP_RESET));
	private final BoolSetting byHits = add(new BoolSetting("By Hits", "Only jump every few hits", false)).visibleWhen(() -> mode.is(Mode.JUMP_RESET));
	private final NumberSetting hitsMin = add(new NumberSetting("Hits Min", "Fewest hits between jumps", 2, 0, 10, 1)).visibleWhen(() -> mode.is(Mode.JUMP_RESET) && byHits.get());
	private final NumberSetting hitsMax = add(new NumberSetting("Hits Max", "Most hits between jumps", 2, 0, 10, 1)).visibleWhen(() -> mode.is(Mode.JUMP_RESET) && byHits.get());
	private final BoolSetting byDelay = add(new BoolSetting("By Delay", "Only jump after a few ticks since the last", true)).visibleWhen(() -> mode.is(Mode.JUMP_RESET) && !byHits.get());
	private final NumberSetting ticksMin = add(new NumberSetting("Ticks Min", "Fewest ticks between jumps", 2, 0, 20, 1)).visibleWhen(() -> mode.is(Mode.JUMP_RESET) && !byHits.get() && byDelay.get());
	private final NumberSetting ticksMax = add(new NumberSetting("Ticks Max", "Most ticks between jumps", 2, 0, 20, 1)).visibleWhen(() -> mode.is(Mode.JUMP_RESET) && !byHits.get() && byDelay.get());
	// Reduce
	private final NumberSetting attacksMin = add(new NumberSetting("Attacks Min", "Fewest hits back, each slowing you", 3, 0, 20, 1)).visibleWhen(() -> mode.is(Mode.REDUCE));
	private final NumberSetting attacksMax = add(new NumberSetting("Attacks Max", "Most hits back", 3, 0, 20, 1)).visibleWhen(() -> mode.is(Mode.REDUCE));
	private final NumberSetting reduceH = add(new NumberSetting("Reduce Horizontal", "Horizontal motion kept per hit back", 0.6, 0, 1, 0.01)).visibleWhen(() -> mode.is(Mode.REDUCE));
	private final NumberSetting reduceV = add(new NumberSetting("Reduce Vertical", "Vertical motion kept per hit back", 1, 0, 1, 0.01)).visibleWhen(() -> mode.is(Mode.REDUCE));
	private final NumberSetting reduceRange = add(new NumberSetting("Reduce Range", "How close the one you hit back has to be", 3, 1, 6, 0.1)).visibleWhen(() -> mode.is(Mode.REDUCE));
	// All
	private final NumberSetting pauseOnFlag = add(new NumberSetting("Pause On Flag", "Ticks to leave knockback alone after a setback", 0, 0, 20, 1)).visibleWhen(() -> !mode.is(Mode.MODIFY));

	private volatile boolean velocityArrived;
	private volatile boolean damaged;
	private volatile int pause;
	// Reversal / Strafe
	private boolean handling;
	private int velocityTicks;
	private boolean applyStrafe;
	// Jump reset
	private int limitUntilJump, hitsUntilJump = 2, ticksUntilJump = 2;
	// Reduce
	private Entity reduceTarget;
	private int remainingAttacks;
	private boolean forwardThisTick;
	// Grim
	private volatile boolean cancelNext, delay, needClick, waitForPing, waitForUpdate, shouldSkip;
	private int freezeTicks;
	private final ConcurrentLinkedQueue<Packet<?>> held = new ConcurrentLinkedQueue<>();

	public AntiKnockBackModule() {
		super("Anti Knock Back", "Stops knockback from moving you; at 0% you don't budge at all", Category.BASIC);
	}

	@Override
	public String getInfo() {
		if (mode.is(Mode.MODIFY)) return horizontal.getInt() + "% " + vertical.getInt() + "%";
		return switch (mode.get()) {
			case JUMP_RESET -> "Jump Reset";
			case GRIM -> "Grim";
			default -> mode.get().name().charAt(0) + mode.get().name().substring(1).toLowerCase();
		};
	}

	@Override
	protected void onDisable() {
		flush();
		handling = applyStrafe = false;
		remainingAttacks = 0;
		reduceTarget = null;
		cancelNext = delay = needClick = waitForPing = waitForUpdate = false;
	}

	/**
	 * Velocity the server set on us from a hit. At 0% the knockback is ignored outright, so we keep
	 * moving exactly as we were (standing still stays standing still) instead of being stopped dead.
	 */
	public Vec3 modifyVelocity(Vec3 velocity) {
		if (!isEnabled() || !mode.is(Mode.MODIFY) || !hits.get()) return velocity;
		Vec3 current = mc.player.getDeltaMovement();
		double h = horizontal.get() / 100.0;
		double v = vertical.get() / 100.0;
		return new Vec3(Mth.lerp(h, current.x, velocity.x), Mth.lerp(v, current.y, velocity.y), Mth.lerp(h, current.z, velocity.z));
	}

	/** Velocity added to us by an explosion. */
	public Vec3 modifyExplosion(Vec3 knockback) {
		if (!isEnabled() || !mode.is(Mode.MODIFY) || !explosions.get()) return knockback;
		double h = horizontal.get() / 100.0;
		double v = vertical.get() / 100.0;
		return new Vec3(knockback.x * h, knockback.y * v, knockback.z * h);
	}

	private boolean isOwnVelocity(Packet<?> packet) {
		if (!(packet instanceof ClientboundSetEntityMotionPacket motion) || mc.player == null) return false;
		//? if >=26.1 {
		/*return motion.id() == mc.player.getId();
		*///?} else {
		return motion.getId() == mc.player.getId();
		//?}
	}

	private boolean isOwnDamage(Packet<?> packet) {
		return packet instanceof ClientboundDamageEventPacket damage && mc.player != null && damage.entityId() == mc.player.getId();
	}

	// ---------------------------------------------------------------- packets (network thread)

	@Override
	public void onPacketReceive(PacketEvent event) {
		Packet<?> packet = event.getPacket();
		// In singleplayer the integrated server's connection comes through here too
		if (packet.type().flow() != PacketFlow.CLIENTBOUND) return;
		if (packet instanceof ClientboundPlayerPositionPacket) pause = pauseOnFlag.getInt();
		if (mode.is(Mode.GRIM)) {
			grimReceive(event, packet);
			return;
		}
		if (pause > 0 || mode.is(Mode.MODIFY)) return;
		if (isOwnDamage(packet)) damaged = true;
		if (isOwnVelocity(packet)) velocityArrived = true;
	}

	private void grimReceive(PacketEvent event, Packet<?> packet) {
		if (packet instanceof ClientboundBlockUpdatePacket update && waitForUpdate && mc.player != null && update.getPos().equals(mc.player.blockPosition())) {
			waitForPing = true;
			needClick = false;
			return;
		}
		if (waitForUpdate) return;
		if (delay) {
			// Everything else waits until the click is out, so the server sees it before we react to anything
			if (packet instanceof ClientboundKeepAlivePacket) return;
			event.cancel();
			held.add(packet);
			return;
		}
		if (pause > 0) return;
		if (isOwnDamage(packet)) {
			cancelNext = true;
		} else if (cancelNext && isOwnVelocity(packet)) {
			event.cancel();
			freezeTicks = 0;
			delay = true;
			cancelNext = false;
			needClick = true;
		}
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!mode.is(Mode.GRIM)) return;
		Packet<?> packet = event.getPacket();
		if (packet.type().flow() != PacketFlow.SERVERBOUND) return;
		if (packet instanceof ServerboundInteractPacket || packet instanceof ServerboundUseItemOnPacket) shouldSkip = true;
		else if (packet instanceof ServerboundMovePlayerPacket move && move.hasPosition() && waitForUpdate) event.cancel();
		else if (packet instanceof ServerboundPongPacket && waitForPing) {
			waitForUpdate = false;
			waitForPing = false;
		}
	}

	@SuppressWarnings("unchecked")
	private void flush() {
		if (mc.getConnection() == null) {
			held.clear();
			return;
		}
		PacketListener listener = mc.getConnection().getConnection().getPacketListener();
		Packet<?> packet;
		while ((packet = held.poll()) != null) {
			if (listener == null) continue;
			try {
				((Packet<PacketListener>) packet).handle(listener);
			} catch (Exception e) {
				FemboyWare.LOGGER.warn("Anti Knock Back couldn't play out {}", packet.getClass().getSimpleName(), e);
			}
		}
	}

	// ---------------------------------------------------------------- ticks

	@Override
	public void onPreMotion() {
		if (nullCheck()) return;
		if (pause > 0) pause--;
		boolean arrived = velocityArrived;
		velocityArrived = false;
		switch (mode.get()) {
			case REVERSAL -> reversal(arrived);
			case STRAFE -> strafe(arrived);
			case REDUCE -> reduce(arrived);
			case GRIM -> grimTick();
			default -> {
			}
		}
	}

	private void reversal(boolean arrived) {
		if (arrived && !(reversalMoving.get() && !MoveUtil.isMoving())) {
			handling = true;
			velocityTicks = 0;
		}
		if (!handling) return;
		Vec3 motion = mc.player.getDeltaMovement();
		if (motion.lengthSqr() == 0) {
			handling = false;
		} else if (velocityTicks++ >= reversalDelay.getInt()) {
			mc.player.setDeltaMovement(motion.x * -reversalX.get(), motion.y, motion.z * -reversalZ.get());
			handling = false;
		}
	}

	private boolean facingEnemy() {
		AuraModule aura = FemboyWare.getModules().get(AuraModule.class);
		LivingEntity target = aura.isEnabled() ? aura.getTarget() : null;
		if (target != null) {
			Vec3 eyes = mc.player.getEyePosition();
			Vec3 end = eyes.add(FemboyWare.getRotations().getServerRotation().toVector().scale(facingRange.get()));
			return target.getBoundingBox().clip(eyes, end).isPresent();
		}
		return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity
			&& hit.getEntity().distanceTo(mc.player) <= facingRange.get();
	}

	private void strafe(boolean arrived) {
		if (arrived && !(onlyFacing.get() && !facingEnemy())) {
			handling = true;
			velocityTicks = 0;
		}
		if (handling && velocityTicks++ >= strafeDelay.getInt()) {
			steer();
			handling = false;
			if (untilGround.get()) applyStrafe = true;
		}
		if (mc.player.onGround()) applyStrafe = false;
		else if (applyStrafe) steer();
	}

	private void steer() {
		Vec3 steered = MoveUtil.strafe(MoveUtil.horizontalSpeed() * strafeStrength.get());
		mc.player.setDeltaMovement(steered.x, mc.player.getDeltaMovement().y, steered.z);
	}

	private static int randomInt(NumberSetting a, NumberSetting b) {
		int lo = Math.min(a.getInt(), b.getInt()), hi = Math.max(a.getInt(), b.getInt());
		return lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
	}

	private Entity reduceTarget() {
		AuraModule aura = FemboyWare.getModules().get(AuraModule.class);
		Entity target = aura.isEnabled() ? aura.getTarget() : null;
		if (target == null && mc.hitResult instanceof EntityHitResult hit) target = hit.getEntity();
		if (target == null || !target.isAlive() || target.distanceTo(mc.player) > reduceRange.get()) return null;
		return target;
	}

	private void reduce(boolean arrived) {
		forwardThisTick = false;
		if (arrived && damaged) {
			damaged = false;
			if (!mc.player.isUsingItem() && !FemboyWare.isEnabled(ScaffoldModule.class) && mc.player.isSprinting()) {
				reduceTarget = reduceTarget();
				if (reduceTarget != null) remainingAttacks = randomInt(attacksMin, attacksMax);
			}
		}
		if (remainingAttacks <= 0) return;
		if (reduceTarget == null || !reduceTarget.isAlive() || reduceTarget.distanceTo(mc.player) > reduceRange.get()) {
			remainingAttacks = 0;
			reduceTarget = null;
			return;
		}
		// A hit while sprinting slows you to 60%; one hit per tick, each with sprint stopped first
		mc.player.setSprinting(false);
		mc.gameMode.attack(mc.player, reduceTarget);
		mc.player.swing(InteractionHand.MAIN_HAND);
		forwardThisTick = true;
		Vec3 m = mc.player.getDeltaMovement();
		mc.player.setDeltaMovement(m.x * reduceH.get(), m.y * reduceV.get(), m.z * reduceH.get());
		if (--remainingAttacks == 0) reduceTarget = null;
	}

	private void grimTick() {
		BlockHitResult hit = null;
		if (needClick && !shouldSkip && !mc.player.isUsingItem()) {
			Rotation down = new Rotation(FemboyWare.getRotations().getServerRotation().yaw(), 90f);
			BlockHitResult trace = TargetFinding.traceFromPlayer(down, mc.player.blockInteractionRange());
			if (trace.getType() == HitResult.Type.BLOCK && trace.getBlockPos().relative(trace.getDirection()).equals(mc.player.blockPosition())) hit = trace;
		}
		if (hit != null) {
			delay = false;
			flush();
			if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
			if (FemboyWare.getRotations().getServerRotation().pitch() != 90f) {
				mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), 90f, mc.player.onGround(), mc.player.horizontalCollision));
			} else {
				mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(mc.player.onGround(), mc.player.horizontalCollision));
			}
			freezeTicks = 0;
			waitForUpdate = true;
			needClick = false;
		} else if (delay && !needClick) {
			delay = false;
			flush();
		}
		if (waitForUpdate && ++freezeTicks > 20) {
			// Never heard back: stop waiting
			waitForUpdate = waitForPing = needClick = false;
		}
		if (delay && freezeTicks++ > 20) {
			delay = needClick = false;
			flush();
		}
		shouldSkip = false;
	}

	// ---------------------------------------------------------------- input

	@Override
	public void onInput() {
		if (nullCheck()) return;
		Input keys = mc.player.input.keyPresses;
		if (mode.is(Mode.JUMP_RESET)) jumpReset(keys);
		else if (mode.is(Mode.REDUCE) && forwardThisTick) {
			mc.player.input.keyPresses = new Input(true, false, keys.left(), keys.right(), keys.jump(), keys.shift(), keys.sprint());
			((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(mc.player.input.getMoveVector().x, 1f));
		} else if (mode.is(Mode.GRIM) && waitForUpdate) {
			// LiquidBounce skips the whole player tick here; standing still keeps the held position honest
			mc.player.input.keyPresses = new Input(false, false, false, false, false, keys.shift(), false);
			((InputAccessor) mc.player.input).femboyware$setMovementVector(Vec2.ZERO);
			mc.player.setDeltaMovement(0, mc.player.getDeltaMovement().y, 0);
		}
	}

	private void jumpReset(Input keys) {
		boolean chance = ThreadLocalRandom.current().nextFloat() * 100f < jumpChance.getFloat();
		if (mc.player.hurtTime != 9 || !mc.player.onGround() || !mc.player.isSprinting() || !cooldownOver() || !chance) {
			if (byHits.get()) {
				if (mc.player.hurtTime == 9) limitUntilJump++;
			} else {
				limitUntilJump++;
			}
			return;
		}
		mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
		limitUntilJump = 0;
		hitsUntilJump = randomInt(hitsMin, hitsMax);
		ticksUntilJump = randomInt(ticksMin, ticksMax);
	}

	private boolean cooldownOver() {
		if (byHits.get()) return limitUntilJump >= hitsUntilJump;
		if (byDelay.get()) return limitUntilJump >= ticksUntilJump;
		return true;
	}
}
