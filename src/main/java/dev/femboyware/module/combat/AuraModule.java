/*
 * Target priority, fail swing and auto block are ported from LiquidBounce
 * (https://github.com/CCBlueX/LiquidBounce): TargetTracker/TargetPriority, KillAuraFailSwing and
 * KillAuraAutoBlock. Copyright (c) 2015 - 2026 CCBlueX, GNU General Public License v3 or later.
 * Modified for femboyware: ported from Kotlin to Java and cut down to a shield auto block and an
 * air swing on a miss.
 */
package dev.femboyware.module.combat;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.lb.ClickScheduler;
import dev.femboyware.lb.RotationSettings;
import dev.femboyware.module.utility.FriendsModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.Easing;
import dev.femboyware.util.Rotation;
import java.util.Random;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Melee aura with a humanised rotation engine: aim point movement, speed distributions, eased
 * acceleration, jitter, overshoot, head-up glances, 360 spins and post-hit holds.
 */
public class AuraModule extends Module {
	private static final int ROTATION_PRIORITY = 50;

	public enum Page { GENERAL, TARGET, CLICKING, BLOCKING, POINT, ROTATION, JITTER, SMOOTH, EXTRAS, PITCH, BYPASS }
	public enum Point { CENTER, EYES, CLOSEST, ROAMING }
	public enum PointJitter { NONE, RANDOM, ROAMING, SINE, DRUNK_SINE }
	public enum Distribution { FLAT, NORMAL }
	public enum Jitter { NONE, RANDOM, SINE, NOISE }
	public enum HoldTiming { AFTER_ATTACK, AFTER_CRIT }
	public enum Engine { FEMBOYWARE, LIQUIDBOUNCE }
	public enum ClickMode { COOLDOWN, CLICKER }
	public enum Priority { DISTANCE, HEALTH, DIRECTION, HURT_TIME, AGE }

	private final EnumSetting<Page> page = add(new EnumSetting<>("Page", "Settings page", Page.GENERAL));

	// General
	private final NumberSetting range = add(new NumberSetting("Range", "Attack range", 3.0, 1, 6, 0.05)).visibleWhen(() -> page.is(Page.GENERAL));
	private final NumberSetting aimRange = add(new NumberSetting("Aim Range", "Start rotating this far beyond attack range", 1.5, 0, 4, 0.1)).visibleWhen(() -> page.is(Page.GENERAL));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Attack once the swing is this charged", 0.95, 0.1, 1, 0.01)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting players = add(new BoolSetting("Players", "Target players", true)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting monsters = add(new BoolSetting("Monsters", "Target hostile mobs", false)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting animals = add(new BoolSetting("Animals", "Target animals", false)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "Attack targets you can't see", true)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting weaponOnly = add(new BoolSetting("Weapon Only", "Only attack while holding a sword or axe", false)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting clientLook = add(new BoolSetting("Client Look", "Turn your own camera too", false)).visibleWhen(() -> page.is(Page.GENERAL));

	// Point
	private final EnumSetting<Point> point = add(new EnumSetting<>("Point", "Where on the model to aim", Point.CLOSEST)).visibleWhen(() -> page.is(Page.POINT));
	private final EnumSetting<PointJitter> pointJitter = add(new EnumSetting<>("Point Jitter", "How the aim point moves", PointJitter.SINE)).visibleWhen(() -> page.is(Page.POINT));
	private final NumberSetting pointVRange = add(new NumberSetting("Point V Range", "Vertical amplitude in blocks", 0.4, 0.01, 1.8, 0.01)).visibleWhen(() -> page.is(Page.POINT));
	private final NumberSetting pointHRange = add(new NumberSetting("Point H Range", "Horizontal amplitude in blocks", 0.1, 0.01, 0.3, 0.01)).visibleWhen(() -> page.is(Page.POINT));
	private final NumberSetting pointHFreq = add(new NumberSetting("Point H Freq", "Horizontal oscillation frequency", 4, 1, 30, 0.5)).visibleWhen(() -> page.is(Page.POINT));
	private final NumberSetting pointVFreq = add(new NumberSetting("Point V Freq", "Vertical oscillation frequency", 3, 1, 30, 0.5)).visibleWhen(() -> page.is(Page.POINT));

	// Rotation
	private final EnumSetting<Engine> engine = add(new EnumSetting<>("Engine", "femboyware's own smoothing, or LiquidBounce's angle smoothing", Engine.FEMBOYWARE)).visibleWhen(() -> page.is(Page.ROTATION));
	private final RotationSettings lbRotations = new RotationSettings(s -> add(s), () -> page.is(Page.ROTATION) && engine.is(Engine.LIQUIDBOUNCE), 180f);
	private final EnumSetting<Distribution> speedDistribution = add(new EnumSetting<>("Speed Distribution", "How speeds are sampled between min and max", Distribution.NORMAL)).visibleWhen(() -> page.is(Page.ROTATION) && engine.is(Engine.FEMBOYWARE));
	private final NumberSetting minYawSpeed = add(new NumberSetting("Min Yaw Speed", "Degrees per tick", 25, 0.1, 180, 0.1)).visibleWhen(() -> page.is(Page.ROTATION) && engine.is(Engine.FEMBOYWARE));
	private final NumberSetting maxYawSpeed = add(new NumberSetting("Max Yaw Speed", "Degrees per tick", 45, 0.1, 180, 0.1)).visibleWhen(() -> page.is(Page.ROTATION) && engine.is(Engine.FEMBOYWARE));
	private final NumberSetting minPitchSpeed = add(new NumberSetting("Min Pitch Speed", "Degrees per tick", 10, 0.1, 180, 0.1)).visibleWhen(() -> page.is(Page.ROTATION) && engine.is(Engine.FEMBOYWARE));
	private final NumberSetting maxPitchSpeed = add(new NumberSetting("Max Pitch Speed", "Degrees per tick", 25, 0.1, 180, 0.1)).visibleWhen(() -> page.is(Page.ROTATION) && engine.is(Engine.FEMBOYWARE));

	// Jitter
	private final EnumSetting<Distribution> jitterDistribution = add(new EnumSetting<>("Jitter Distribution", "How random jitter is sampled", Distribution.NORMAL)).visibleWhen(() -> page.is(Page.JITTER));
	private final EnumSetting<Jitter> yawJitter = add(new EnumSetting<>("Yaw Jitter", "Yaw jitter type", Jitter.NOISE)).visibleWhen(() -> page.is(Page.JITTER));
	private final EnumSetting<Jitter> pitchJitter = add(new EnumSetting<>("Pitch Jitter", "Pitch jitter type", Jitter.NOISE)).visibleWhen(() -> page.is(Page.JITTER));
	private final NumberSetting yawAmpl = add(new NumberSetting("Yaw Ampl", "Amplitude in tenths of a degree", 4, 1, 30, 0.5)).visibleWhen(() -> page.is(Page.JITTER));
	private final NumberSetting pitchAmpl = add(new NumberSetting("Pitch Ampl", "Amplitude in tenths of a degree", 3, 1, 30, 0.5)).visibleWhen(() -> page.is(Page.JITTER));
	private final NumberSetting yawFreq = add(new NumberSetting("Yaw Freq", "Jitter frequency", 6, 1, 30, 0.5)).visibleWhen(() -> page.is(Page.JITTER));
	private final NumberSetting pitchFreq = add(new NumberSetting("Pitch Freq", "Jitter frequency", 4, 1, 30, 0.5)).visibleWhen(() -> page.is(Page.JITTER));

	// Smooth
	private final NumberSetting smoothHbScale = add(new NumberSetting("Smooth HB Scale", "Hitbox scale inside which rotation slows down", 1.5, 0.1, 5, 0.05)).visibleWhen(() -> page.is(Page.SMOOTH));
	private final NumberSetting yawAcceleration = add(new NumberSetting("Yaw Acceleration", "How quickly yaw speed builds up", 0.6, 0.1, 1, 0.01)).visibleWhen(() -> page.is(Page.SMOOTH));
	private final NumberSetting pitchAcceleration = add(new NumberSetting("Pitch Acceleration", "How quickly pitch speed builds up", 0.5, 0.1, 1, 0.01)).visibleWhen(() -> page.is(Page.SMOOTH));
	private final NumberSetting yawDeceleration = add(new NumberSetting("Yaw Deceleration", "How much yaw slows inside the smooth hitbox", 0.5, 0, 1, 0.01)).visibleWhen(() -> page.is(Page.SMOOTH));
	private final NumberSetting pitchDeceleration = add(new NumberSetting("Pitch Deceleration", "How much pitch slows inside the smooth hitbox", 0.6, 0, 1, 0.01)).visibleWhen(() -> page.is(Page.SMOOTH));
	private final EnumSetting<Easing> yawSmoother = add(new EnumSetting<>("Yaw Smoother", "Easing applied to remaining yaw distance", Easing.SINE_OUT)).visibleWhen(() -> page.is(Page.SMOOTH));
	private final EnumSetting<Easing> pitchSmoother = add(new EnumSetting<>("Pitch Smoother", "Easing applied to remaining pitch distance", Easing.QUAD_OUT)).visibleWhen(() -> page.is(Page.SMOOTH));
	private final NumberSetting yawAccelerationMin = add(new NumberSetting("Yaw Accel Min", "Lower bound of the eased yaw speed factor", 0.2, 0, 1, 0.01)).visibleWhen(() -> page.is(Page.SMOOTH));
	private final NumberSetting pitchAccelerationMin = add(new NumberSetting("Pitch Accel Min", "Lower bound of the eased pitch speed factor", 0.2, 0, 1, 0.01)).visibleWhen(() -> page.is(Page.SMOOTH));

	// Extras: overshoot, head up, click jitter, 360, hold
	private final BoolSetting overShoot = add(new BoolSetting("Overshoot", "Fly past the target on big flicks", false)).visibleWhen(() -> page.is(Page.EXTRAS));
	private final NumberSetting overshootTrigger = add(new NumberSetting("Overshoot Trigger", "Minimum angle that counts as a big movement", 45, 20, 180, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && overShoot.get());
	private final NumberSetting overshootStreak = add(new NumberSetting("Overshoot Streak", "Big movements in a row before overshooting", 2, 1, 10, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && overShoot.get());
	private final NumberSetting overshootValue = add(new NumberSetting("Overshoot Value", "Overshoot size in tenths of a degree", 60, 20, 180, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && overShoot.get());
	private final NumberSetting overshootMinTicks = add(new NumberSetting("Overshoot Min Ticks", "Shortest overshoot", 1, 0, 10, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && overShoot.get());
	private final NumberSetting overshootMaxTicks = add(new NumberSetting("Overshoot Max Ticks", "Longest overshoot", 3, 0, 10, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && overShoot.get());
	private final BoolSetting headUp = add(new BoolSetting("Head Up", "Occasionally glance upwards", false)).visibleWhen(() -> page.is(Page.EXTRAS));
	private final NumberSetting huIntervalMin = add(new NumberSetting("HU Interval Min", "Min ticks between glances", 60, 5, 400, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && headUp.get());
	private final NumberSetting huIntervalMax = add(new NumberSetting("HU Interval Max", "Max ticks between glances", 160, 5, 400, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && headUp.get());
	private final NumberSetting huTicksMin = add(new NumberSetting("HU Ticks Min", "Shortest glance", 2, 1, 20, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && headUp.get());
	private final NumberSetting huTicksMax = add(new NumberSetting("HU Ticks Max", "Longest glance", 5, 1, 20, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && headUp.get());
	private final NumberSetting huSpeedMin = add(new NumberSetting("HU Speed Min", "Min glance speed in degrees per tick", 5, 1, 90, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && headUp.get());
	private final NumberSetting huSpeedMax = add(new NumberSetting("HU Speed Max", "Max glance speed in degrees per tick", 12, 1, 90, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && headUp.get());
	private final BoolSetting clickJitter = add(new BoolSetting("Click Jitter", "Small kick in aim when attacking", false)).visibleWhen(() -> page.is(Page.EXTRAS));
	private final BoolSetting yaw360 = add(new BoolSetting("360 Rotation", "Sometimes spin a full turn after a hit", false)).visibleWhen(() -> page.is(Page.EXTRAS));
	private final NumberSetting yaw360Chance = add(new NumberSetting("360 Chance", "Percent chance per hit", 5, 1, 100, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && yaw360.get());
	private final BoolSetting holdAfterHit = add(new BoolSetting("Hold After Hit", "Freeze aim briefly after a hit", false)).visibleWhen(() -> page.is(Page.EXTRAS));
	private final NumberSetting holdMin = add(new NumberSetting("Hold Min", "Shortest hold in ticks", 1, 0, 15, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && holdAfterHit.get());
	private final NumberSetting holdMax = add(new NumberSetting("Hold Max", "Longest hold in ticks", 3, 0, 15, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && holdAfterHit.get());
	private final EnumSetting<HoldTiming> holdTiming = add(new EnumSetting<>("Hold Timing", "Which hits trigger a hold", HoldTiming.AFTER_ATTACK)).visibleWhen(() -> page.is(Page.EXTRAS) && holdAfterHit.get());
	private final NumberSetting holdChance = add(new NumberSetting("Hold Chance", "Percent chance per hit", 50, 1, 100, 1)).visibleWhen(() -> page.is(Page.EXTRAS) && holdAfterHit.get());

	// Pitch bounds
	private final NumberSetting pitchMin = add(new NumberSetting("Pitch Min", "Lowest pitch while aiming", -90, -90, 90, 1)).visibleWhen(() -> page.is(Page.PITCH));
	private final NumberSetting pitchMax = add(new NumberSetting("Pitch Max", "Highest pitch while aiming", 90, -90, 90, 1)).visibleWhen(() -> page.is(Page.PITCH));
	private final NumberSetting pitchITMin = add(new NumberSetting("Pitch IT Min", "Lowest pitch while on target", -90, -90, 90, 1)).visibleWhen(() -> page.is(Page.PITCH));
	private final NumberSetting pitchITMax = add(new NumberSetting("Pitch IT Max", "Highest pitch while on target", 90, -90, 90, 1)).visibleWhen(() -> page.is(Page.PITCH));

	// Target (LiquidBounce's target tracker)
	private final EnumSetting<Priority> priority = add(new EnumSetting<>("Priority", "Who to fight first (players always before mobs)", Priority.DISTANCE)).visibleWhen(() -> page.is(Page.TARGET));
	private final BoolSetting ignoreShield = add(new BoolSetting("Ignore Shield", "Also go for players holding up a shield (off: skip them unless you hold an axe)", true)).visibleWhen(() -> page.is(Page.TARGET));

	// Clicking (LiquidBounce's clicker and fail swing)
	private final EnumSetting<ClickMode> clickMode = add(new EnumSetting<>("Click Mode", "Cooldown hits when the swing is charged; Clicker clicks at a CPS like LiquidBounce (still waits for Cooldown)", ClickMode.COOLDOWN)).visibleWhen(() -> page.is(Page.CLICKING));
	private final ClickScheduler clicker = new ClickScheduler(s -> add(s), () -> page.is(Page.CLICKING) && clickMode.is(ClickMode.CLICKER));
	private final BoolSetting failSwing = add(new BoolSetting("Fail Swing", "Swing at the air when the target is just out of reach, like a real miss", false)).visibleWhen(() -> page.is(Page.CLICKING));
	private final NumberSetting failRangeMin = add(new NumberSetting("Fail Range Min", "Least extra reach to swing within", 2.5, 0, 10, 0.1)).visibleWhen(() -> page.is(Page.CLICKING) && failSwing.get());
	private final NumberSetting failRangeMax = add(new NumberSetting("Fail Range Max", "Most extra reach to swing within", 3, 0, 10, 0.1)).visibleWhen(() -> page.is(Page.CLICKING) && failSwing.get());

	// Blocking (LiquidBounce's auto block)
	private final BoolSetting autoBlock = add(new BoolSetting("Auto Block", "Hold up your shield between hits", false)).visibleWhen(() -> page.is(Page.BLOCKING));
	private final NumberSetting reblockTicks = add(new NumberSetting("Reblock", "Ticks after a hit before blocking again", 0, 0, 3, 1)).visibleWhen(() -> page.is(Page.BLOCKING) && autoBlock.get());
	private final NumberSetting pauseOnUnblock = add(new NumberSetting("Pause On Unblock", "Ticks to wait after lowering the shield before hitting", 0, 0, 3, 1)).visibleWhen(() -> page.is(Page.BLOCKING) && autoBlock.get());
	private final NumberSetting blockChance = add(new NumberSetting("Block Chance", "Percent of the time to block", 100, 0, 100, 1)).visibleWhen(() -> page.is(Page.BLOCKING) && autoBlock.get());
	private final BoolSetting onlyInDanger = add(new BoolSetting("Only In Danger", "Only block while the target is looking at you within reach", false)).visibleWhen(() -> page.is(Page.BLOCKING) && autoBlock.get());

	// Bypass
	private final BoolSetting noMultiActions = add(new BoolSetting("No Multi Actions", "Don't attack while using an item or with a container open, which Grim flags", true)).visibleWhen(() -> page.is(Page.BYPASS));
	private final NumberSetting switchDelay = add(new NumberSetting("Switch Delay", "Ticks to wait after changing hotbar slot before attacking", 0, 0, 10, 1)).visibleWhen(() -> page.is(Page.BYPASS));
	private final BoolSetting grim = addGrim(() -> page.is(Page.BYPASS));
	private final BoolSetting packetRotate = add(new BoolSetting("Packet Rotate", "If the aim isn't on the target when the swing is ready, send the exact rotation in its own packet and hit anyway (can trip Grim's timer)", false)).visibleWhen(() -> page.is(Page.BYPASS));

	private final Random random = new Random();

	private LivingEntity target;
	private Rotation current;
	private float yawVelocity;
	private float pitchVelocity;
	private long ticks;

	private Vec3 roamOffset = Vec3.ZERO;
	private Vec3 roamGoal = Vec3.ZERO;
	private double hPhase;
	private double vPhase;
	private double yawNoise;
	private double pitchNoise;

	private int overshootStreakCount;
	private int overshootTicksLeft;
	private float overshootYaw;

	private int headUpCountdown = 100;
	private int headUpTicksLeft;
	private float headUpSpeed;
	private float headUpOffset;

	private float spinRemaining;
	private int holdTicks;

	private int pauseTicks, ticksSinceAttack;
	private boolean blocking;
	private double failRange = 2.75;

	private int lastSlot = -1;
	private int ticksSinceSwitch;

	public AuraModule() {
		super("Aura", "Attacks nearby entities with humanised rotations", Category.ATTACK);
	}

	@Override
	public String getInfo() {
		return target == null ? null : target.getName().getString();
	}

	/** Who Aura is fighting, or null; for the Target HUD. */
	public LivingEntity getTarget() {
		return isEnabled() ? target : null;
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		target = null;
		if (blocking && mc.player != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
		blocking = false;
	}

	private void reset() {
		current = null;
		yawVelocity = 0;
		pitchVelocity = 0;
		overshootStreakCount = 0;
		overshootTicksLeft = 0;
		spinRemaining = 0;
		holdTicks = 0;
	}

	@Override
	public void onPreMotion() {
		ticks++;
		int slot = mc.player.getInventory().getSelectedSlot();
		if (slot != lastSlot) {
			lastSlot = slot;
			ticksSinceSwitch = 0;
		} else {
			ticksSinceSwitch++;
		}
		target = trapBusy() ? null : findTarget();
		if (target == null) {
			reset();
			return;
		}
		if (current == null) current = FemboyWare.getRotations().getServerRotation();
		updateHeadUp();

		if (holdTicks > 0) {
			holdTicks--;
			FemboyWare.getRotations().request(current, ROTATION_PRIORITY, 180f, clientLook.get());
			return;
		}

		Vec3 eyes = mc.player.getEyePosition();
		Rotation desired = Rotation.between(eyes, computeAimPoint(target, eyes));
		desired = new Rotation(desired.yaw(), desired.pitch() - headUpOffset);
		desired = applyOvershoot(desired);

		if (engine.is(Engine.LIQUIDBOUNCE)) {
			// LiquidBounce's angle smooth does the turning; the jitter and pitch limits still apply to the aim
			float yaw = desired.yaw() + jitter(yawJitter.get(), yawAmpl.get(), yawFreq.get(), true);
			float pitch = desired.pitch() + jitter(pitchJitter.get(), pitchAmpl.get(), pitchFreq.get(), false);
			pitch = Mth.clamp(pitch, Math.min(pitchMin.getFloat(), pitchMax.getFloat()), Math.max(pitchMin.getFloat(), pitchMax.getFloat()));
			FemboyWare.getRotations().request(new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)), ROTATION_PRIORITY, lbRotations, clientLook.get());
			current = FemboyWare.getRotations().getServerRotation();
			return;
		}

		float yawDelta = Mth.wrapDegrees(desired.yaw() - current.yaw());
		float pitchDelta = desired.pitch() - current.pitch();
		boolean inSmoothBox = rayHits(current, scaledBox(target.getBoundingBox(), smoothHbScale.get()), range.get() + aimRange.get());

		yawVelocity = smoothAxis(yawDelta, yawVelocity, sample(minYawSpeed.get(), maxYawSpeed.get(), speedDistribution.get()),
			180f, yawSmoother.get(), yawAccelerationMin.get(), yawAcceleration.get(), inSmoothBox ? yawDeceleration.get() : 0);
		pitchVelocity = smoothAxis(pitchDelta, pitchVelocity, sample(minPitchSpeed.get(), maxPitchSpeed.get(), speedDistribution.get()),
			90f, pitchSmoother.get(), pitchAccelerationMin.get(), pitchAcceleration.get(), inSmoothBox ? pitchDeceleration.get() : 0);

		float yaw = current.yaw() + yawVelocity + jitter(yawJitter.get(), yawAmpl.get(), yawFreq.get(), true);
		float pitch = current.pitch() + pitchVelocity + jitter(pitchJitter.get(), pitchAmpl.get(), pitchFreq.get(), false);

		if (spinRemaining > 0) {
			float step = Math.min(spinRemaining, 40f);
			yaw += step;
			spinRemaining -= step;
		}

		boolean onTarget = rayHits(new Rotation(yaw, pitch), target.getBoundingBox(), range.get());
		float lower = onTarget ? pitchITMin.getFloat() : pitchMin.getFloat();
		float upper = onTarget ? pitchITMax.getFloat() : pitchMax.getFloat();
		pitch = Mth.clamp(pitch, Math.min(lower, upper), Math.max(lower, upper));

		current = new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f));
		FemboyWare.getRotations().request(current, ROTATION_PRIORITY, 180f, clientLook.get());
	}

	/** Trap's legit mode has the look and the clicks right now: Aura waits its turn. */
	private static boolean trapBusy() {
		return FemboyWare.getModules().get(TrapModule.class).isBusy();
	}

	@Override
	public void onPostMotion() {
		if (clickMode.is(ClickMode.CLICKER)) clicker.tick();
		if (trapBusy()) {
			// Lower the shield: placing with it up is two actions at once
			if (blocking && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
			blocking = false;
			return;
		}
		if (pauseTicks > 0) {
			pauseTicks--;
			return;
		}
		tickAutoBlock();
		if (target == null || !target.isAlive()) return;
		if (clickMode.is(ClickMode.CLICKER) && clicker.clicksLeft() == 0) return;
		if (mc.player.getAttackStrengthScale(0.5f) < cooldown.get()) return;
		if (blocking && mc.player.isUsingItem()) {
			// LiquidBounce unblocks to hit
			mc.gameMode.releaseUsingItem(mc.player);
			blocking = false;
			// Grim: vanilla can't lower a shield and swing in the same tick
			int pause = dev.femboyware.util.Grim.on() ? Math.max(1, pauseOnUnblock.getInt()) : pauseOnUnblock.getInt();
			if (pause > 0) {
				pauseTicks = pause;
				return;
			}
		}
		if (weaponOnly.get() && !(mc.player.getMainHandItem().is(ItemTags.SWORDS) || mc.player.getMainHandItem().is(ItemTags.AXES))) return;

		if ((noMultiActions.get() || dev.femboyware.util.Grim.on()) && (mc.player.isUsingItem() || Mc.screen() instanceof AbstractContainerScreen<?>)) return;
		if (ticksSinceSwitch < switchDelay.getInt()) return;
		if (!throughWalls.get() && !isVisible(target, mc.player.getEyePosition())) return;

		AABB box = target.getBoundingBox();
		if (!rayHits(FemboyWare.getRotations().getServerRotation(), box, dev.femboyware.util.Grim.reach(range.get()))) {
			if (!packetRotate.get() || dev.femboyware.util.Grim.on()) {
				failSwing();
				return;
			}
			Vec3 eyes = mc.player.getEyePosition();
			if (eyes.distanceTo(BlockUtil.closestPoint(box, eyes)) > range.get()) {
				failSwing();
				return;
			}
			FemboyWare.getRotations().sendPacketRotation(Rotation.between(eyes, BlockUtil.closestPoint(box.deflate(0.05), eyes)));
			// Carry on smoothing from where the server now thinks we look
			current = FemboyWare.getRotations().getServerRotation();
			if (!rayHits(FemboyWare.getRotations().getServerRotation(), box, dev.femboyware.util.Grim.reach(range.get()))) return;
		}

		boolean crit = mc.player.fallDistance > 0 && !mc.player.onGround();
		if (clickMode.is(ClickMode.CLICKER)) clicker.take();
		mc.gameMode.attack(mc.player, target);
		mc.player.swing(InteractionHand.MAIN_HAND);
		onAttack(crit);
		ticksSinceAttack = 0;
		failRange = randomRange(failRangeMin.get(), failRangeMax.get());
	}

	private double randomRange(double a, double b) {
		double lo = Math.min(a, b), hi = Math.max(a, b);
		return lo + random.nextDouble() * (hi - lo);
	}

	/** LiquidBounce's fail swing: a target just out of reach gets a swing at the air, as a real miss would. */
	private void failSwing() {
		if (!failSwing.get() || target == null) return;
		Vec3 eyes = mc.player.getEyePosition();
		if (eyes.distanceTo(BlockUtil.closestPoint(target.getBoundingBox(), eyes)) > range.get() + failRange) return;
		if (mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) return;
		if (clickMode.is(ClickMode.CLICKER)) clicker.take();
		// What vanilla does on a miss: the swing resets the charge too
		mc.player.resetAttackStrengthTicker();
		mc.player.swing(InteractionHand.MAIN_HAND);
		failRange = randomRange(failRangeMin.get(), failRangeMax.get());
	}

	private InteractionHand shieldHand() {
		if (mc.player.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem) return InteractionHand.OFF_HAND;
		if (mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.ShieldItem) return InteractionHand.MAIN_HAND;
		return null;
	}

	private boolean inDanger() {
		if (target == null) return false;
		double reach = range.get();
		Vec3 eyes = target.getEyePosition();
		Vec3 end = eyes.add(target.getLookAngle().scale(reach));
		return mc.player.getBoundingBox().inflate(0.3).clip(eyes, end).isPresent();
	}

	/** LiquidBounce's auto block, with a shield: raise it between hits, lowered again before the next one. */
	private void tickAutoBlock() {
		ticksSinceAttack++;
		if (!autoBlock.get() || target == null) {
			if (blocking) {
				if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
				blocking = false;
			}
			return;
		}
		InteractionHand hand = shieldHand();
		if (hand == null || blocking || mc.player.isUsingItem()) return;
		if (ticksSinceAttack < reblockTicks.getInt()) return;
		// A hit is going out this tick anyway: blocking now would only be lowered again straight away
		Vec3 eyes = mc.player.getEyePosition();
		if (mc.player.getAttackStrengthScale(0.5f) >= cooldown.get() && eyes.distanceTo(BlockUtil.closestPoint(target.getBoundingBox(), eyes)) <= range.get()) return;
		if (onlyInDanger.get() && !inDanger()) return;
		if (random.nextInt(100) >= blockChance.getInt()) return;
		if (mc.gameMode.useItem(mc.player, hand).consumesAction()) blocking = true;
	}

	private void onAttack(boolean crit) {
		if (clickJitter.get()) {
			yawVelocity += (float) (random.nextGaussian() * 1.5);
			pitchVelocity += (float) (random.nextGaussian() * 1.0);
		}
		if (yaw360.get() && random.nextInt(100) < yaw360Chance.getInt()) spinRemaining = 360f;
		if (holdAfterHit.get() && (holdTiming.is(HoldTiming.AFTER_ATTACK) || crit) && random.nextInt(100) < holdChance.getInt()) {
			holdTicks = randomInt(holdMin.getInt(), holdMax.getInt());
		}
	}

	private LivingEntity findTarget() {
		double maxRange = range.get() + aimRange.get();
		Vec3 eyes = mc.player.getEyePosition();
		LivingEntity best = null;
		java.util.Comparator<LivingEntity> order = comparator(eyes);
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || entity == mc.player || !living.isAlive()) continue;
			if (!isValidType(living) || FriendsModule.protects(living)) continue;
			double distance = eyes.distanceTo(BlockUtil.closestPoint(living.getBoundingBox(), eyes));
			if (distance > maxRange) continue;
			if (!ignoreShield.get() && living instanceof Player p && p.isBlocking() && !mc.player.getMainHandItem().is(ItemTags.AXES)) continue;
			// Otherwise we'd lock onto someone behind a wall and never swing at anyone
			if (!throughWalls.get() && !isVisible(living, eyes)) continue;
			if (best == null || order.compare(living, best) < 0) best = living;
		}
		return best;
	}

	/** LiquidBounce's target priorities, players first. */
	private java.util.Comparator<LivingEntity> comparator(Vec3 eyes) {
		java.util.Comparator<LivingEntity> type = java.util.Comparator.comparingInt(e -> e instanceof Player ? 0 : e instanceof Enemy ? 1 : 2);
		java.util.Comparator<LivingEntity> distance = java.util.Comparator.comparingDouble(e -> eyes.distanceTo(BlockUtil.closestPoint(e.getBoundingBox(), eyes)));
		Vec3 look = FemboyWare.getRotations().getServerRotation().toVector();
		java.util.Comparator<LivingEntity> then = switch (priority.get()) {
			case DISTANCE -> distance;
			case HEALTH -> java.util.Comparator.comparingDouble(e -> e.getHealth() + e.getAbsorptionAmount());
			case DIRECTION -> java.util.Comparator.comparingDouble(e -> -look.dot(e.getBoundingBox().getCenter().subtract(eyes).normalize()));
			case HURT_TIME -> java.util.Comparator.comparingInt(e -> e.hurtTime);
			case AGE -> java.util.Comparator.comparingInt(e -> -e.tickCount);
		};
		return type.thenComparing(then).thenComparing(distance);
	}

	private static boolean isVisible(LivingEntity entity, Vec3 eyes) {
		AABB box = entity.getBoundingBox();
		Vec3 center = box.getCenter();
		return BlockUtil.canSee(eyes, center)
			|| BlockUtil.canSee(eyes, new Vec3(center.x, entity.getEyeY(), center.z))
			|| BlockUtil.canSee(eyes, new Vec3(center.x, box.minY + 0.1, center.z));
	}

	private boolean isValidType(LivingEntity entity) {
		if (entity instanceof Player player) return players.get() && !player.isSpectator() && !player.isCreative();
		if (entity instanceof Enemy) return monsters.get();
		if (entity instanceof Animal) return animals.get();
		return false;
	}

	// ---------------------------------------------------------------- aim point

	private Vec3 computeAimPoint(LivingEntity entity, Vec3 eyes) {
		AABB box = entity.getBoundingBox();
		Vec3 center = box.getCenter();
		Vec3 base = switch (point.get()) {
			case CENTER -> center;
			case EYES -> new Vec3(center.x, entity.getEyeY(), center.z);
			case CLOSEST -> BlockUtil.closestPoint(box.deflate(0.05), eyes);
			case ROAMING -> center.add(roam(box.getXsize() / 2, box.getYsize() / 2, 0.08));
		};

		double h = pointHRange.get();
		double v = pointVRange.get() / 2;
		Vec3 offset = switch (pointJitter.get()) {
			case NONE -> Vec3.ZERO;
			case RANDOM -> new Vec3(randomRange(h), randomRange(v), randomRange(h));
			case ROAMING -> roam(h, v, 0.25);
			case SINE -> {
				hPhase += pointHFreq.get() * 0.02;
				vPhase += pointVFreq.get() * 0.02;
				yield new Vec3(Math.sin(hPhase) * h, Math.sin(vPhase) * v, Math.cos(hPhase) * h);
			}
			case DRUNK_SINE -> {
				hPhase += pointHFreq.get() * 0.02 * (0.4 + random.nextDouble() * 1.2);
				vPhase += pointVFreq.get() * 0.02 * (0.4 + random.nextDouble() * 1.2);
				yield new Vec3(Math.sin(hPhase) * h, Math.sin(vPhase) * v + Math.sin(vPhase * 2.3) * v * 0.3, Math.cos(hPhase * 0.7) * h);
			}
		};
		return BlockUtil.closestPoint(box.deflate(0.02), base.add(offset));
	}

	/** Smooth random walk of the aim offset within ±h horizontally and ±v vertically. */
	private Vec3 roam(double h, double v, double speed) {
		if (roamOffset.distanceToSqr(roamGoal) < 0.0025) {
			roamGoal = new Vec3(randomRange(h), randomRange(v), randomRange(h));
		}
		roamOffset = roamOffset.lerp(roamGoal, speed);
		return new Vec3(
			Mth.clamp(roamOffset.x, -h, h),
			Mth.clamp(roamOffset.y, -v, v),
			Mth.clamp(roamOffset.z, -h, h));
	}

	// ---------------------------------------------------------------- rotation shaping

	/**
	 * Velocity for one axis. The eased fraction of the remaining distance scales the sampled
	 * speed, acceleration blends the previous velocity towards it, and deceleration damps it
	 * while the crosshair is inside the smooth hitbox.
	 */
	private float smoothAxis(float delta, float velocity, double speed, float fullRange, Easing easing,
							 double accelerationMin, double acceleration, double deceleration) {
		double eased = Math.max(accelerationMin, easing.apply(Math.abs(delta) / fullRange));
		double targetVelocity = Math.copySign(Math.min(Math.abs(delta), speed * eased), delta);
		targetVelocity *= 1.0 - deceleration;
		double blend = Math.abs(targetVelocity) > Math.abs(velocity) || Math.signum(targetVelocity) != Math.signum(velocity) ? acceleration : 1.0;
		double next = velocity + (targetVelocity - velocity) * blend;
		// Never step past the target on our own; overshoot is handled separately
		if (Math.abs(next) > Math.abs(delta)) next = delta;
		return (float) next;
	}

	private Rotation applyOvershoot(Rotation desired) {
		if (!overShoot.get()) return desired;
		float yawDelta = Mth.wrapDegrees(desired.yaw() - current.yaw());
		if (overshootTicksLeft > 0) {
			overshootTicksLeft--;
			return new Rotation(desired.yaw() + overshootYaw, desired.pitch());
		}
		if (Math.abs(yawDelta) >= overshootTrigger.get()) overshootStreakCount++;
		else overshootStreakCount = 0;

		if (overshootStreakCount >= overshootStreak.getInt()) {
			overshootStreakCount = 0;
			overshootTicksLeft = randomInt(overshootMinTicks.getInt(), overshootMaxTicks.getInt());
			overshootYaw = (float) (Math.signum(yawDelta) * overshootValue.get() * 0.1 * (0.5 + random.nextDouble() * 0.5));
			return new Rotation(desired.yaw() + overshootYaw, desired.pitch());
		}
		return desired;
	}

	private float jitter(Jitter type, double amplitudeTenths, double frequency, boolean yawAxis) {
		double amplitude = amplitudeTenths * 0.1;
		return (float) switch (type) {
			case NONE -> 0.0;
			case RANDOM -> sample(-amplitude, amplitude, jitterDistribution.get());
			case SINE -> Math.sin(ticks * frequency * 0.05 + (yawAxis ? 0 : 1.7)) * amplitude;
			case NOISE -> {
				// Brownian drift pulled back towards zero
				double step = sample(-amplitude, amplitude, jitterDistribution.get()) * Math.min(1, frequency / 10);
				if (yawAxis) {
					yawNoise = Mth.clamp(yawNoise * 0.85 + step, -amplitude, amplitude);
					yield yawNoise;
				}
				pitchNoise = Mth.clamp(pitchNoise * 0.85 + step, -amplitude, amplitude);
				yield pitchNoise;
			}
		};
	}

	private void updateHeadUp() {
		if (!headUp.get()) {
			headUpOffset = 0;
			return;
		}
		if (headUpTicksLeft > 0) {
			headUpTicksLeft--;
			headUpOffset = Math.min(60f, headUpOffset + headUpSpeed);
			return;
		}
		headUpOffset = Math.max(0f, headUpOffset - headUpSpeed);
		if (--headUpCountdown <= 0) {
			headUpCountdown = randomInt(huIntervalMin.getInt(), huIntervalMax.getInt());
			headUpTicksLeft = randomInt(huTicksMin.getInt(), huTicksMax.getInt());
			headUpSpeed = (float) (huSpeedMin.get() + random.nextDouble() * Math.max(0, huSpeedMax.get() - huSpeedMin.get()));
		}
	}

	// ---------------------------------------------------------------- helpers

	private boolean rayHits(Rotation rotation, AABB box, double reach) {
		Vec3 eyes = mc.player.getEyePosition();
		if (box.contains(eyes)) return true;
		return box.clip(eyes, eyes.add(rotation.toVector().scale(reach))).isPresent();
	}

	private static AABB scaledBox(AABB box, double scale) {
		Vec3 center = box.getCenter();
		double hx = box.getXsize() * scale / 2, hy = box.getYsize() * scale / 2, hz = box.getZsize() * scale / 2;
		return new AABB(center.x - hx, center.y - hy, center.z - hz, center.x + hx, center.y + hy, center.z + hz);
	}

	private double sample(double min, double max, Distribution distribution) {
		double low = Math.min(min, max), high = Math.max(min, max);
		if (distribution == Distribution.FLAT) return low + random.nextDouble() * (high - low);
		double gaussian = Mth.clamp(random.nextGaussian() / 3.0, -1, 1);
		return (low + high) / 2 + gaussian * (high - low) / 2;
	}

	private double randomRange(double amplitude) {
		return (random.nextDouble() * 2 - 1) * amplitude;
	}

	private int randomInt(int min, int max) {
		int low = Math.min(min, max), high = Math.max(min, max);
		return low + random.nextInt(high - low + 1);
	}
}
