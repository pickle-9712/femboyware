package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.KeyUtil;
import dev.femboyware.util.MouseTurner;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Fights like a player would: goes into third person, turns the camera onto the nearest player
 * the way a mouse does, walks and sprints over to them, jumps to land critical hits, and left
 * clicks when they're under the crosshair and the sword is charged.
 *
 * <p>Nothing here is faked. The camera only ever turns by whole mouse steps at your sensitivity,
 * easing in like a hand would; movement goes through the normal movement keys; and hits are the
 * same left click the mouse makes, only on whoever is actually under the crosshair.
 *
 * <p>On top of that it plays like someone who knows 1.9+ PvP: W-taps after sprint hits so the
 * next one knocks back too, sprint-hits instead of jumping while a combo is going, strafes so it's
 * harder to hit, jump-resets knockback, blocks with a shield between swings and breaks the
 * opponent's shield with an axe.
 */
public class LegitAuraModule extends Module {
	public enum View { FIRST_PERSON, THIRD_PERSON, SIDE }

	/** What to do right after a sprint hit to get sprint back for the next one. */
	public enum Tap { OFF, W_TAP, S_TAP }

	/** What we're using to get at a far-off target, if anything. */
	private enum Ranged { NONE, PEARL, BOW, CROSSBOW }

	/** Which weapon to fight with. With an axe every hit on a raised shield disables it. */
	public enum Weapon { SWORD, AXE }

	/** Best first: Legit Aura takes the strongest one in the hotbar. */
	private static final Item[] SWORDS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.COPPER_SWORD,
		Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD};
	private static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.COPPER_AXE,
		Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE};

	private final NumberSetting range = add(new NumberSetting("Range", "How far away a player can be to lock on", 8, 3, 100, 0.5));
	private final NumberSetting fov = add(new NumberSetting("FOV", "Only lock on to players within this many degrees of where you look", 360, 30, 360, 10));
	private final NumberSetting aimSpeed = add(new NumberSetting("Aim Speed", "How quickly the camera turns onto them", 7, 1, 20, 0.5));
	private final EnumSetting<View> view = add(new EnumSetting<>("View", "Camera while on: your own, behind you, or from the side to watch the fight", View.THIRD_PERSON));
	private final BoolSetting autoSword = add(new BoolSetting("Auto Weapon", "Switch to your best weapon when locking on", true));
	private final EnumSetting<Weapon> weapon = add(new EnumSetting<>("Weapon", "Fight with a sword (faster hits) or an axe (harder hits, and every hit on a raised shield disables it); falls back to the other if you have none", Weapon.SWORD)).visibleWhen(autoSword::get);
	private final BoolSetting chase = add(new BoolSetting("Chase", "Walk and sprint over to the target", true));
	private final BoolSetting crits = add(new BoolSetting("Crits", "Jump and hit on the way down for critical hits", true));
	private final BoolSetting onlyVisible = add(new BoolSetting("Only Visible", "Ignore players behind walls", true));
	private final NumberSetting randomDelay = add(new NumberSetting("Random Delay", "Wait up to this many ticks before each hit, a different amount every time, like a person's reaction", 2, 0, 5, 1));
	private final BoolSetting firstHit = add(new BoolSetting("First Hit", "Swing the moment a player comes under the crosshair if the sword is nearly charged, without waiting for a full charge, a crit or Random Delay", true));
	private final EnumSetting<Tap> wTap = add(new EnumSetting<>("W Tap", "After a sprint hit, let go of W (or tap S) for a moment so the next hit is a sprint hit with full knockback too", Tap.W_TAP));
	private final NumberSetting tapTicks = add(new NumberSetting("Tap Ticks", "How long to let go of W for each tap", 2, 1, 5, 1)).visibleWhen(() -> !wTap.is(Tap.OFF));
	private final BoolSetting combo = add(new BoolSetting("Combo", "While they're flying from your last hit, sprint-hit to keep them away instead of jumping for a crit", true));
	private final BoolSetting strafe = add(new BoolSetting("Strafe", "Circle them up close, switching sides now and then, so they have a harder time hitting you", true));
	private final BoolSetting jumpReset = add(new BoolSetting("Jump Reset", "Jump the moment you get hit to take less knockback", true));
	private final NumberSetting jumpResetChance = add(new NumberSetting("Reset Chance", "Percent of hits to jump-reset; nobody times every one", 80, 0, 100, 5)).visibleWhen(jumpReset::get);
	private final BoolSetting shieldBreak = add(new BoolSetting("Shield Break", "Switch to an axe and hit them when they're blocking with a shield, disabling it", true));
	private final BoolSetting flank = add(new BoolSetting("Flank", "When their shield is up and you can't break it, hold your hit instead of swinging into it and circle round to their side", true));
	private final BoolSetting shield = add(new BoolSetting("Shield", "Block with the shield in your offhand while your sword recharges and they could hit you", false));
	private final BoolSetting hitSelect = add(new BoolSetting("Hit Select", "Against someone facing you, hold your hit until they swing; their weapon then has to recharge, so your hit lands while they can't answer and starts a combo", true));
	private final NumberSetting selectTime = add(new NumberSetting("Select Time", "Most ticks to wait for them to swing before hitting anyway", 10, 1, 30, 1)).visibleWhen(hitSelect::get);
	private final NumberSetting engageHealth = add(new NumberSetting("Engage Health", "Only pearl in or shoot at far-off targets at or above this much health, and with Legit Pot (if on) done restocking", 16, 1, 20, 1));
	private final BoolSetting pearl = add(new BoolSetting("Pearl", "Throw an ender pearl at a far-off target to close the gap", true));
	private final NumberSetting pearlDistance = add(new NumberSetting("Pearl Distance", "Only pearl at targets at least this far away", 15, 6, 60, 1)).visibleWhen(pearl::get);
	private final BoolSetting rangedWeapons = add(new BoolSetting("Bow", "With no pearl in the hotbar, aim and shoot a bow or crossbow at a far-off target", true));
	private final NumberSetting rangedDistance = add(new NumberSetting("Bow Distance", "Only shoot at targets at least this far away", 8, 4, 60, 1)).visibleWhen(rangedWeapons::get);

	private final Random random = new Random();
	private PlayerEntity target;
	private Perspective previousPerspective;
	/** Set when we just switched to the sword: no hit on the same tick, which no hand could do. */
	private boolean justSwitched;
	/** Whether a player was under the crosshair last tick, and whether one has just come under it and not been hit yet. */
	private boolean wasUnder;
	private boolean fresh;
	/** Whether the target had a shield up towards us last tick, to catch the moment they lower it. */
	private boolean wasShielded;
	/** Ticks a ready hit has been held, waiting for the fall of a jump to make it a crit. */
	private int critWait;
	/** Ticks still to wait before the next hit, picked when it becomes possible; -1 until then. */
	private int reactionWait = -1;
	private long lastFrame;
	private final MouseTurner mouse = new MouseTurner();
	/** Changes slowly, so the aim point drifts around their chest instead of sitting dead still. */
	private final float seed = new Random().nextFloat() * 100;
	/** Ticks left of letting go of W after a sprint hit. */
	private int tapLeft;
	private boolean strafeLeft;
	/** Ticks until strafing switches sides, and ticks since it last did. */
	private int strafeSwitch;
	private int strafeAge;
	/** Ticks before trying to break their shield again, so one break isn't followed by more axe swings before their shield visibly drops. */
	private int shieldBreakCooldown;
	/** Whether we're holding the use key down to block. */
	private boolean blocking;
	/** Ticks spent holding a ready hit, waiting for them to swing first. */
	private int selectWait;
	private Ranged ranged = Ranged.NONE;
	/** Where to look to land the pearl or arrow, worked out each tick; null if it can't reach. */
	private Rotation rangedAim;
	/** Whether we're holding the use key to draw a bow or load a crossbow. */
	private boolean drawing;

	public LegitAuraModule() {
		super("Legit Aura", "Locks on, walks over and hits players like a normal player", Category.LEGIT);
	}

	@Override
	public String getInfo() {
		return target == null ? null : target.getName().getString();
	}

	/** The held weapon's recharge time in ticks: how long a full-strength hit takes to come back. */
	public float rechargeTicks() {
		return mc.player == null ? 0 : mc.player.getAttackCooldownProgressPerTick();
	}

	/** Who Legit Aura is fighting, or null; for the Target HUD. */
	public PlayerEntity getTarget() {
		return isEnabled() ? target : null;
	}

	@Override
	protected void onEnable() {
		lastFrame = 0;
		mouse.reset();
		if (view.get() != View.FIRST_PERSON) {
			previousPerspective = mc.options.getPerspective();
			mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
		}
	}

	@Override
	protected void onDisable() {
		target = null;
		tapLeft = 0;
		if (blocking && !nullCheck()) KeyUtil.sync(mc.options.useKey);
		blocking = false;
		if (!nullCheck()) stopRanged();
		if (previousPerspective != null) mc.options.setPerspective(previousPerspective);
		previousPerspective = null;
	}

	// ---------------------------------------------------------------- targeting and hitting

	@Override
	public void onTick() {
		// Bodyguard picks who to fight, and nobody else under the crosshair gets hit, so bystanders are safe
		boolean guarding = FemboyWare.isEnabled(BodyguardModule.class);
		if (guarding) target = FemboyWare.getModules().get(BodyguardModule.class).getTarget();
		else {
			if (target != null && !keep(target)) target = null;
			if (target == null) target = pick();
		}
		PlayerEntity under = underCrosshair();
		if (guarding && under != target) under = null;
		if (under == null) fresh = false;
		else if (!wasUnder) fresh = true;
		wasUnder = under != null;
		if (shieldBreakCooldown > 0) shieldBreakCooldown--;
		// Lowering a shield is the opening: they can't block again for a moment, so swing like at a fresh target
		boolean shielded = target != null && shielded(target);
		if (wasShielded && !shielded && under != null) fresh = true;
		wasShielded = shielded;
		updateShield();
		// Legit Pot has the camera and hotbar while it throws a potion
		boolean idle = target == null || mc.currentScreen != null || potting();
		if (idle) stopRanged();
		else if (updateRanged()) return;
		if (idle || mc.player.isUsingItem()) {
			critWait = 0;
			return;
		}
		// A shield up towards us is worth an axe swing: it disables the shield whatever the charge
		int axe = best(AXES);
		boolean breaking = shieldBreak.get() && shieldBreakCooldown == 0 && axe >= 0 && shielded;
		if (breaking ? holdSlot(axe) : autoSword.get() && holdWeapon()) {
			justSwitched = true;
			return;
		}
		if (justSwitched) {
			justSwitched = false;
			return;
		}

		// The base delay is the held weapon's own recharge time (20 / its attack speed: 12.5 ticks for a
		// sword, 20 for most axes). The hit itself is scored half a tick ahead, exactly as vanilla does, so
		// a full-strength hit comes the first tick it can; then Random Delay adds a short pause on top
		float charge = mc.player.getAttackCooldownProgress(0.5f);
		// Someone just stepped under the crosshair: swing straight away to get the first hit in. Above 0.9
		// a hit still counts as a sprint hit or crit and does at least 85% damage
		// Hit select: against someone facing us who isn't already reeling, the first hit is theirs to throw.
		// The moment they swing (or hit us) their weapon starts recharging, and that's when we hit
		boolean selecting = hitSelect.get() && !breaking && under != null && under.hurtTime == 0
			&& TargetUtil.isFacing(under, mc.player.getEyePos());
		boolean theySwung = under != null && (under.handSwinging && under.handSwingTicks <= 2 || mc.player.hurtTime > 0 && mc.player.hurtTime >= mc.player.maxHurtTime - 2);
		if (!selecting) selectWait = 0;
		boolean quick = charge > 0.9f && (selecting ? theySwung : firstHit.get() && fresh);
		if (charge < 1 && !breaking && !quick) {
			critWait = 0;
			reactionWait = -1;
			return;
		}
		// The crosshair only lands on a player within vanilla reach, so a player under it is a hit we can make right now
		if (under == null) return;
		if (breaking && under != target) return;
		// Swinging into a raised shield does nothing but knock us back; wait for it to drop or for us to get round it
		if (!breaking && flank.get() && shielded(under) && !mc.player.getMainHandStack().isIn(ItemTags.AXES)) return;
		target = under;
		if (selecting && !theySwung && selectWait < selectTime.getInt()) {
			selectWait++;
			return;
		}
		if (!quick) {
			// A short, different pause each time before swinging, like someone reacting
			if (reactionWait < 0) reactionWait = random.nextInt(randomDelay.getInt() + 1);
			if (reactionWait > 0) {
				reactionWait--;
				return;
			}
		}
		// Facing away they can't hit back, so even a first hit or a combo is worth holding for a crit
		boolean safe = !TargetUtil.isFacing(under, mc.player.getEyePos());
		if (!breaking && crits.get() && (safe || (!quick && !(combo.get() && comboing(under)))) && waitForCrit(under)) {
			critWait++;
			return;
		}

		boolean sprintHit = mc.player.isSprinting();
		((MinecraftClientAccessor) mc).femboyware$leftClick();
		fresh = false;
		selectWait = 0;
		critWait = 0;
		reactionWait = -1;
		if (breaking) shieldBreakCooldown = 10;
		// A sprint hit ends the sprint on the server, but the client carries straight on sprinting without
		// telling it, so the next hit would be a weak one. Letting go of W makes it stop and start again
		if (sprintHit && !wTap.is(Tap.OFF)) tapLeft = tapTicks.getInt();
	}

	// ---------------------------------------------------------------- pearls, bows and crossbows

	/**
	 * Pearls in on, or shoots at, a far-off target while we're healthy and stocked up. True while busy
	 * with it, so the melee part waits.
	 */
	private boolean updateRanged() {
		Ranged want = chooseRanged();
		if (want != ranged) stopRanged();
		ranged = want;
		if (ranged == Ranged.NONE) return false;
		aimRanged();
		switch (ranged) {
			case PEARL -> {
				// Number key first, throw on a later tick once the camera is on the arc
				if (holdSlot(InventoryUtil.findHotbar(stack -> stack.isOf(Items.ENDER_PEARL)))) return true;
				if (aimed(1.5f)) {
					((MinecraftClientAccessor) mc).femboyware$rightClick();
					stopRanged();
				}
			}
			case BOW -> {
				if (holdSlot(InventoryUtil.findHotbar(stack -> stack.isOf(Items.BOW)))) return true;
				press();
				// Let go at full draw once on target, which looses the arrow; the next tick draws again
				if (mc.player.isUsingItem() && BowItem.getPullProgress(mc.player.getItemUseTime()) >= 1 && aimed(1)) release();
			}
			case CROSSBOW -> {
				if (holdSlot(InventoryUtil.findHotbar(stack -> stack.isOf(Items.CROSSBOW)))) return true;
				ItemStack crossbow = mc.player.getMainHandStack();
				if (!CrossbowItem.isCharged(crossbow)) press();
				else if (drawing) release();
				else if (!mc.player.isUsingItem() && aimed(1)) ((MinecraftClientAccessor) mc).femboyware$rightClick();
			}
		}
		return true;
	}

	/** What to use on the target right now. Sticks with the current choice a little closer in, so it doesn't flicker. */
	private Ranged chooseRanged() {
		if (mc.player.getHealth() < engageHealth.get() || !mc.player.canSee(target) || !FemboyWare.getModules().get(LegitPotModule.class).isReady()) return Ranged.NONE;
		double distance = mc.player.distanceTo(target);
		int pearlSlot = InventoryUtil.findHotbar(stack -> stack.isOf(Items.ENDER_PEARL));
		if (pearl.get() && pearlSlot >= 0 && distance >= pearlDistance.get() - (ranged == Ranged.PEARL ? 2 : 0)
			&& !mc.player.getItemCooldownManager().isCoolingDown(mc.player.getInventory().getStack(pearlSlot))) return Ranged.PEARL;
		if (!rangedWeapons.get() || pearlSlot >= 0) return Ranged.NONE;
		boolean shooting = ranged == Ranged.BOW || ranged == Ranged.CROSSBOW;
		if (distance < rangedDistance.get() - (shooting ? 2 : 0)) return Ranged.NONE;
		int bow = InventoryUtil.findHotbar(stack -> stack.isOf(Items.BOW));
		if (bow >= 0 && !mc.player.getProjectileType(mc.player.getInventory().getStack(bow)).isEmpty()) return Ranged.BOW;
		int crossbow = InventoryUtil.findHotbar(stack -> stack.isOf(Items.CROSSBOW));
		if (crossbow >= 0) {
			ItemStack stack = mc.player.getInventory().getStack(crossbow);
			if (CrossbowItem.isCharged(stack) || !mc.player.getProjectileType(stack).isEmpty()) return Ranged.CROSSBOW;
		}
		return Ranged.NONE;
	}

	private void press() {
		mc.options.useKey.setPressed(true);
		drawing = true;
	}

	private void release() {
		KeyUtil.sync(mc.options.useKey);
		drawing = false;
	}

	private void stopRanged() {
		if (drawing) release();
		ranged = Ranged.NONE;
		rangedAim = null;
	}

	private boolean aimed(float tolerance) {
		return rangedAim != null && Math.abs(MathHelper.wrapDegrees(rangedAim.yaw() - mc.player.getYaw())) < tolerance
			&& Math.abs(rangedAim.pitch() - mc.player.getPitch()) < tolerance;
	}

	/**
	 * Works out where to look so the pearl lands at their feet, or the arrow hits their middle, leading
	 * them by how far they'll walk while it flies. Uses the game's own speed, gravity and drag.
	 */
	private void aimRanged() {
		boolean thrown = ranged == Ranged.PEARL;
		double speed = thrown ? 1.5 : ranged == Ranged.CROSSBOW ? 3.15 : 3.0;
		double gravity = thrown ? 0.03 : 0.05;
		// Projectiles start just below the eyes
		Vec3d from = mc.player.getEyePos().subtract(0, 0.1, 0);
		Vec3d aim = target.getEntityPos().add(0, thrown ? 0 : target.getHeight() * 0.5, 0);
		Vec3d walk = new Vec3d(target.getX() - target.lastX, 0, target.getZ() - target.lastZ);
		Vec3d point = aim;
		double[] shot = null;
		for (int i = 0; i < 3; i++) {
			double dx = point.x - from.x;
			double dz = point.z - from.z;
			shot = solve(Math.sqrt(dx * dx + dz * dz), point.y - from.y, speed, gravity, thrown);
			if (shot == null) break;
			point = aim.add(walk.multiply(shot[1]));
		}
		rangedAim = shot == null ? null : new Rotation(Rotation.between(from, point).yaw(), (float) shot[0]);
	}

	/**
	 * The flattest pitch that carries a projectile {@code distance} blocks out at {@code height} above
	 * where it starts, and how many ticks it takes: {pitch, ticks}. Null if it can't reach.
	 */
	static double[] solve(double distance, double height, double speed, double gravity, boolean thrown) {
		double[] lower = flight(60, distance, speed, gravity, thrown);
		if (lower != null && lower[0] >= height) return new double[] {60, lower[1]};
		// Raise the aim a degree at a time until it passes over the point, then narrow it down
		for (double pitch = 59; pitch >= -60; pitch--) {
			double[] upper = flight(pitch, distance, speed, gravity, thrown);
			if (upper == null || upper[0] < height) continue;
			double low = pitch + 1;
			double high = pitch;
			for (int i = 0; i < 12; i++) {
				double middle = (low + high) / 2;
				double[] result = flight(middle, distance, speed, gravity, thrown);
				if (result != null && result[0] >= height) high = middle;
				else low = middle;
			}
			double[] result = flight(high, distance, speed, gravity, thrown);
			return result == null ? null : new double[] {high, result[1]};
		}
		return null;
	}

	/**
	 * Flies a projectile tick by tick as the game does (thrown things fall and slow before moving, arrows
	 * after) and returns {height, ticks} where it passes {@code distance}; null if it falls short.
	 */
	private static double[] flight(double pitch, double distance, double speed, double gravity, boolean thrown) {
		double radians = Math.toRadians(pitch);
		double vx = Math.cos(radians) * speed;
		double vy = -Math.sin(radians) * speed;
		double x = 0;
		double y = 0;
		for (int tick = 1; tick <= 200; tick++) {
			if (thrown) {
				vy -= gravity;
				vx *= 0.99;
				vy *= 0.99;
			}
			double lastX = x;
			double lastY = y;
			x += vx;
			y += vy;
			if (!thrown) {
				vx *= 0.99;
				vy *= 0.99;
				vy -= gravity;
			}
			if (x >= distance) return new double[] {lastY + (y - lastY) * (distance - lastX) / (x - lastX), tick};
			if (y < -100) return null;
		}
		return null;
	}

	/** The player under the crosshair if it's one we can fight, else null. */
	private PlayerEntity underCrosshair() {
		return mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof PlayerEntity player && TargetUtil.isValid(player) ? player : null;
	}

	/**
	 * Holds right click with a shield in the offhand while the sword recharges, as long as they're
	 * close and facing us; lets go in time for the next hit, or as soon as they're the one stunned.
	 */
	private void updateShield() {
		boolean want = shield.get() && target != null && mc.currentScreen == null && !potting() && shouldBlock();
		if (want) mc.options.useKey.setPressed(true);
		else if (blocking) KeyUtil.sync(mc.options.useKey);
		blocking = want;
	}

	private boolean shouldBlock() {
		ItemStack offhand = mc.player.getOffHandStack();
		if (!offhand.contains(DataComponentTypes.BLOCKS_ATTACKS) || mc.player.getItemCooldownManager().isCoolingDown(offhand)) return false;
		if (mc.player.isUsingItem() && mc.player.getActiveItem() != offhand) return false;
		// Someone holding up their own shield can't hit us, so there's nothing to block
		return mc.player.getAttackCooldownProgress(0) < 0.8f && target.hurtTime == 0 && !target.isBlocking()
			&& mc.player.distanceTo(target) < 4 && TargetUtil.isFacing(target, mc.player.getEyePos());
	}

	/** Whether {@code player} has a shield raised that would stop our hits. */
	private boolean shielded(PlayerEntity player) {
		return player.isBlocking() && TargetUtil.isFacing(player, mc.player.getEyePos());
	}

	private boolean potting() {
		return FemboyWare.getModules().get(LegitPotModule.class).isBusy();
	}

	/** Whether {@code player} is flying from a hit, so sprint-hitting again keeps them from fighting back. */
	private boolean comboing(PlayerEntity player) {
		return player.hurtTime > 0 && !player.isOnGround();
	}

	/**
	 * Whether to hold a ready hit for a crit, as long as they'll still be in reach on the way down.
	 * Normally only briefly while already rising in a jump, and on the ground it hits now. When they're
	 * facing away and can't hit back, it holds on the ground too, jumps, and waits for the fall.
	 */
	private boolean waitForCrit(PlayerEntity player) {
		if (!canCrit() || falling()) return false;
		boolean safe = !TargetUtil.isFacing(player, mc.player.getEyePos());
		if (safe ? critWait >= 20 : mc.player.isOnGround() || critWait >= 6) return false;
		double reach = mc.player.getEntityInteractionRange();
		double distance = Math.sqrt(player.getBoundingBox().squaredMagnitude(mc.player.getEyePos()));
		return distance < reach - 0.6;
	}

	/** Switches the hotbar to the best weapon of the chosen kind, or the other kind if there's none. True if it switched just now. */
	private boolean holdWeapon() {
		boolean axe = weapon.is(Weapon.AXE);
		int slot = best(axe ? AXES : SWORDS);
		if (slot < 0) slot = best(axe ? SWORDS : AXES);
		return slot >= 0 && holdSlot(slot);
	}

	/** The hotbar slot of the first of {@code items} we have, which is the best; -1 if none. */
	private int best(Item[] items) {
		for (Item item : items) {
			int slot = InventoryUtil.findHotbar(stack -> stack.isOf(item));
			if (slot >= 0) return slot;
		}
		return -1;
	}

	/** Switches to a hotbar slot, like pressing its number key. True if it switched just now. */
	private boolean holdSlot(int slot) {
		if (mc.player.getInventory().getSelectedSlot() == slot) return false;
		InventoryUtil.select(slot);
		return true;
	}

	/** Whether the camera should watch from the side; read by CameraMixin. */
	public boolean sideView() {
		return isEnabled() && view.get() == View.SIDE && !mc.options.getPerspective().isFirstPerson();
	}

	/** The best player to lock on to: in range, in view, and closest to where we're looking. */
	private PlayerEntity pick() {
		PlayerEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (!TargetUtil.isValid(player) || mc.player.distanceTo(player) > range.get()) continue;
			if (onlyVisible.get() && !mc.player.canSee(player)) continue;
			float angle = Math.abs(MathHelper.wrapDegrees(aimRotation(player, 1).yaw() - mc.player.getYaw()));
			if (angle > fov.get() / 2) continue;
			double score = angle + mc.player.distanceTo(player) * 10;
			if (score < bestScore) {
				bestScore = score;
				best = player;
			}
		}
		return best;
	}

	/** Stays locked on a little beyond the range, so the target doesn't flicker at its edge. */
	private boolean keep(PlayerEntity player) {
		return TargetUtil.isValid(player) && mc.player.distanceTo(player) <= range.get() + 2
			&& (!onlyVisible.get() || mc.player.canSee(player));
	}

	/** Whether a hit could be a crit at all right now; in water, on a ladder and so on it can't. */
	private boolean canCrit() {
		return !mc.player.isTouchingWater() && !mc.player.isInLava() && !mc.player.isClimbing() && !mc.player.hasVehicle()
			&& !mc.player.hasStatusEffect(StatusEffects.BLINDNESS) && !mc.player.hasStatusEffect(StatusEffects.LEVITATION)
			&& !mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING) && !mc.player.getAbilities().flying;
	}

	/** On the way down from a jump, when a hit lands as a crit. */
	private boolean falling() {
		return !mc.player.isOnGround() && mc.player.fallDistance > 0 && mc.player.getVelocity().y < 0 && !mc.player.isSprinting();
	}

	// ---------------------------------------------------------------- moving

	@Override
	public void onInput() {
		if (target == null || !chase.get() || mc.currentScreen != null) return;
		PlayerInput keys = mc.player.input.playerInput;
		double distance = mc.player.distanceTo(target);
		// Keep a sword's length of space: close in when far, ease off near reach, step back if right on top of them.
		// With more than vanilla's 3 blocks of reach (the Reach module), hang back by the difference so we
		// hit from outside their reach
		double extra = Math.max(0, mc.player.getEntityInteractionRange() - 3);
		boolean forward = distance > 2.9 + extra;
		boolean back = distance < 1.8 + extra;
		// Sprint all the way in when W-tapping, since every hit should be a sprint hit
		boolean sprint = forward && (distance > 3.4 + extra || !wTap.is(Tap.OFF));
		boolean jump = false;
		boolean left = keys.left();
		boolean right = keys.right();

		if (crits.get() && canCrit() && distance < 3.6 + extra
			&& (!(combo.get() && comboing(target)) || !TargetUtil.isFacing(target, mc.player.getEyePos()))) {
			float charge = mc.player.getAttackCooldownProgress(0.5f);
			// Jump as the sword is nearly charged, so it's full again on the way down
			if (mc.player.isOnGround() && charge > 0.65f) jump = true;
			// Let go of forward in the air: sprinting stops crits, so players tap off it to land one
			if (!mc.player.isOnGround()) {
				forward = false;
				sprint = false;
			}
		}
		// Hop over whatever is in the way, like anyone running at someone would
		if (forward && mc.player.horizontalCollision && mc.player.isOnGround()) jump = true;

		// Jump the tick a hit lands: running forward into the jump cancels part of the knockback
		if (jumpReset.get() && mc.player.hurtTime > 0 && mc.player.hurtTime == mc.player.maxHurtTime - 1 && mc.player.isOnGround()
			&& random.nextInt(100) < jumpResetChance.getInt()) {
			jump = true;
			forward = true;
			sprint = true;
		}

		// Circle them up close, switching sides every so often or when something is in the way
		// While flanking a shield (even with Strafe off), keep going one way so we actually get round it
		strafeAge++;
		boolean flanking = flank.get() && shielded(target);
		if ((strafe.get() || flanking) && !left && !right && distance < 4.5 + extra) {
			if ((--strafeSwitch <= 0 && !flanking) || (mc.player.horizontalCollision && strafeAge > 5)) {
				strafeLeft = !strafeLeft;
				strafeSwitch = 10 + random.nextInt(21);
				strafeAge = 0;
			}
			left = strafeLeft;
			right = !strafeLeft;
		}

		boolean tapping = tapLeft > 0;
		if (tapping) tapLeft--;
		boolean anyForward = !tapping && (keys.forward() || forward);
		boolean anyBack = tapping ? wTap.is(Tap.S_TAP) : keys.backward() || (back && !keys.forward());
		mc.player.input.playerInput = new PlayerInput(anyForward, anyBack, left, right,
			keys.jump() || jump, keys.sneak(), !tapping && (keys.sprint() || sprint));
		int forwardAxis = (anyForward ? 1 : 0) - (anyBack ? 1 : 0);
		int strafeAxis = (left ? 1 : 0) - (right ? 1 : 0);
		Vec2f movement = new Vec2f(strafeAxis, forwardAxis);
		((InputAccessor) mc.player.input).femboyware$setMovementVector(movement.lengthSquared() > 0 ? movement.normalize() : Vec2f.ZERO);
	}

	// ---------------------------------------------------------------- aiming

	/** Turns the camera a little towards the target every frame, the way a mouse would. */
	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (target == null || mc.currentScreen != null || seconds <= 0 || potting()) return;

		float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
		boolean aimingShot = ranged != Ranged.NONE && rangedAim != null;
		Rotation wanted = aimingShot ? rangedAim : Rotation.between(mc.player.getCameraPosVec(tickDelta), trackPoint(target, tickDelta));
		float yawDelta = MathHelper.wrapDegrees(wanted.yaw() - mc.player.getYaw());
		float pitchDelta = wanted.pitch() - mc.player.getPitch();

		// Eases in: big turns start fast and slow down as the crosshair arrives, a bit unevenly like a hand
		// Near reach, track harder, so the crosshair is already on them the moment they step into it and
		// never slips off while a hit is possible
		double reach = mc.player.getEntityInteractionRange();
		boolean close = Math.sqrt(target.getBoundingBox().squaredMagnitude(mc.player.getEyePos())) < reach + 2;
		float follow = 1 - (float) Math.exp(-aimSpeed.get() * (close || aimingShot ? 1.8 : 1) * seconds);
		float wobble = 0.85f + random.nextFloat() * 0.3f;
		mouse.turn(yawDelta * follow * wobble, pitchDelta * follow * wobble);
	}

	/**
	 * The spot on {@code player} to keep the crosshair on: where we already look, pulled just
	 * inside their hitbox. A person keeps their crosshair wherever it sits on the target rather than
	 * snapping to its middle, and staying inside the edges means it doesn't slip off as they move.
	 */
	private Vec3d trackPoint(PlayerEntity player, float tickDelta) {
		Vec3d eye = mc.player.getCameraPosVec(tickDelta);
		Box box = player.getBoundingBox().offset(player.getLerpedPos(tickDelta).subtract(player.getEntityPos()));
		Box inside = box.contract(0.18, 0.3, 0.18);
		Vec3d onLook = eye.add(mc.player.getRotationVec(tickDelta).multiply(eye.distanceTo(box.getCenter())));
		return new Vec3d(MathHelper.clamp(onLook.x, inside.minX, inside.maxX),
			MathHelper.clamp(onLook.y, inside.minY, inside.maxY),
			MathHelper.clamp(onLook.z, inside.minZ, inside.maxZ));
	}

	/** Where to look to aim at {@code player}'s upper body, drifting slowly around it rather than one fixed point. */
	private Rotation aimRotation(PlayerEntity player, float tickDelta) {
		float time = System.currentTimeMillis() / 1000f + seed;
		double height = player.getHeight() * (0.62 + 0.12 * Math.sin(time * 1.3));
		double side = 0.08 * Math.sin(time * 0.9);
		Vec3d aim = player.getLerpedPos(tickDelta).add(side, height, side * 0.5);
		return Rotation.between(mc.player.getCameraPosVec(tickDelta), aim);
	}
}
