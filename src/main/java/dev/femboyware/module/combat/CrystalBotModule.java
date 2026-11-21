package dev.femboyware.module.combat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.femboyware.util.Teams;
import dev.femboyware.FemboyWare;
import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.Pathfinder;
import dev.femboyware.util.MouseTurner;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Plays crystal PvP for you, legit: picks the nearest player, walks in to crystal range, keeps a
 * totem in your offhand, and every tick does one real thing with a real flick of the view, a real
 * hotbar switch and a real click: crystals on the best obsidian (putting obsidian down when there's
 * none good), respawn anchors charged and blown up next to them, and a full sword hit whenever it's
 * charged and they're open to one, so the crystal straight after makes it a double tap.
 */
public class CrystalBotModule extends Module {
	private enum Step { IDLE, TOTEM, EAT, PEARL, SWORD, OBSIDIAN, CRYSTAL, POP, ANCHOR_PLACE, ANCHOR_CHARGE, ANCHOR_BLOW }

	private static final Item[] WEAPONS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.COPPER_SWORD,
		Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD, Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE};
	private static final float ANCHOR_POWER = 5f;

	private final NumberSetting range = add(new NumberSetting("Range", "How far away to pick a fight", 24, 4, 48, 0.5));
	private final BoolSetting teamCheck = add(new BoolSetting("Team Check", "Leave players on your team alone (same scoreboard team, name colour or helmet colour)", false));
	private final NumberSetting minDamage = add(new NumberSetting("Min Damage", "Least damage a crystal or anchor has to do to them", 5, 1, 20, 0.5));
	private final NumberSetting maxSelf = add(new NumberSetting("Max Self", "Most damage a crystal or anchor may do to you", 8, 0, 20, 0.5));
	private final BoolSetting crystals = add(new BoolSetting("Crystals", "Place and pop end crystals", true));
	private final BoolSetting placeObsidian = add(new BoolSetting("Place Obsidian", "Put obsidian down next to them when there's none a crystal would do enough on", true)).visibleWhen(crystals::get);
	private final NumberSetting maxCrystals = add(new NumberSetting("Max Crystals", "Most crystals in a row on the same obsidian before looking again", 8, 1, 30, 1)).visibleWhen(crystals::get);
	private final BoolSetting anchors = add(new BoolSetting("Anchors", "Place, charge and blow up respawn anchors next to them when that hurts more than a crystal (not in the Nether, where they don't explode)", true));
	private final BoolSetting sword = add(new BoolSetting("Sword", "A full sword hit whenever it's charged and they're in reach and not still flinching, with a crystal straight after", true));
	private final BoolSetting move = add(new BoolSetting("Move", "Walk in to crystal range, back off when on top of them, and hop over what's in the way", true));
	private final NumberSetting distance = add(new NumberSetting("Distance", "How far from them to stand", 3.2, 2, 4.5, 0.1)).visibleWhen(move::get);
	private final BoolSetting pearl = add(new BoolSetting("Pearl", "Throw an ender pearl at their feet when they're too far to walk to, leading them by where they'll be when it lands", true));
	private final NumberSetting pearlDistance = add(new NumberSetting("Pearl Distance", "Pearl when they're at least this far away", 10, 6, 40, 0.5)).visibleWhen(pearl::get);
	private final BoolSetting autoTotem = add(new BoolSetting("Auto Totem", "Keep a totem in your offhand: a hotbar one goes in with a real switch and F, and Legit Totem is turned on for the rest", true));
	private final BoolSetting fastCrystals = add(new BoolSetting("Fast Crystals", "Turn on Crystal Optimizer while fighting: a crystal you hit goes on your side at once, so the next goes down a ping sooner", true));
	private final BoolSetting gapple = add(new BoolSetting("Gapple", "Eat a golden apple (enchanted first) when you're low, holding right click like you would", true));
	private final NumberSetting gappleHealth = add(new NumberSetting("Gapple Health", "Eat at or below this much health plus absorption", 12, 2, 30, 0.5)).visibleWhen(gapple::get);
	private final BoolSetting strafe = add(new BoolSetting("Strafe", "Circle them left and right while in place, switching now and then, so they're harder to crystal back", true)).visibleWhen(move::get);
	private final BoolSetting predict = add(new BoolSetting("Predict", "Only put a crystal or anchor down if it still hurts them where they'll be a few ticks on, so running doesn't waste it", true));
	private final NumberSetting flickSpeed = add(new NumberSetting("Flick Speed", "How fast your view flicks onto each thing, in whole mouse steps at your sensitivity", 28, 6, 60, 1));

	private final MouseTurner turner = new MouseTurner();
	private Player target;
	private Step step = Step.IDLE;
	private int stepTicks;
	/** The obsidian the crystals go on, where obsidian is going down, and the anchor. */
	private BlockPos base, obsidianAt, anchorAt;
	private Vec3 aimPoint;
	private long lastFrame;
	private int lastSlot = -1, sinceSwitch;
	private int popped;
	/** Nothing could be done from here last time it looked: walk in closer till something's in reach. */
	private boolean outOfReach;
	/** Ticks left waiting for the server to answer an offhand swap, so a second F doesn't swap it back. */
	private int totemWait;
	/** Modules this turned on or off, put back when it's turned off. */
	private boolean enabledTotem, disabledDTap, enabledOptimizer;
	/** Holding right click to eat, and whether the eating got going. */
	private boolean eating, startedEating;
	/** Strafing: which way, and ticks till it switches. */
	private boolean strafeLeft;
	private int strafeTimer, retarget;
	private final Map<BlockPos, Vec3> topSpots = new HashMap<>();
	/** The A* walk to them: the blocks to go through, how far along, where it was aimed, and ticks till it can be worked out again. */
	private java.util.List<Vec3> path;
	private int pathIndex, pathCooldown;
	private Vec3 pathGoal;

	public CrystalBotModule() {
		super("Crystal Bot", "Fights people in crystal PvP for you with crystals, anchors, the sword and totems, all real flicks, switches and clicks", Category.LEGIT);
	}

	@Override
	public String getInfo() {
		return target == null ? null : target.getGameProfile().name();
	}

	@Override
	protected void onEnable() {
		step = Step.IDLE;
		target = null;
		enabledTotem = autoTotem.get() && !FemboyWare.isEnabled(LegitTotemModule.class);
		if (enabledTotem) FemboyWare.getModules().get(LegitTotemModule.class).setEnabled(true);
		// Two things flicking the view at once fight each other
		disabledDTap = FemboyWare.isEnabled(HitCrystalModule.class);
		if (disabledDTap) FemboyWare.getModules().get(HitCrystalModule.class).setEnabled(false);
		enabledOptimizer = fastCrystals.get() && !FemboyWare.isEnabled(CrystalOptimizerModule.class);
		if (enabledOptimizer) FemboyWare.getModules().get(CrystalOptimizerModule.class).setEnabled(true);
	}

	@Override
	protected void onDisable() {
		target = null;
		aimPoint = null;
		step = Step.IDLE;
		if (enabledTotem) FemboyWare.getModules().get(LegitTotemModule.class).setEnabled(false);
		if (disabledDTap) FemboyWare.getModules().get(HitCrystalModule.class).setEnabled(true);
		if (enabledOptimizer) FemboyWare.getModules().get(CrystalOptimizerModule.class).setEnabled(false);
		enabledTotem = disabledDTap = enabledOptimizer = false;
		stopEating();
	}

	/** Fighting: Legit Aura doesn't aim, swing or switch meanwhile. */
	public boolean isBusy() {
		return isEnabled() && target != null;
	}

	// ---------------------------------------------------------------- deciding

	@Override
	public void onTick() {
		aimPoint = null;
		if (nullCheck()) return;
		int selected = mc.player.getInventory().getSelectedSlot();
		if (totemWait > 0) totemWait--;
		if (selected != lastSlot) {
			lastSlot = selected;
			sinceSwitch = 0;
		} else sinceSwitch++;

		boolean lost = target == null || !TargetUtil.isValid(target, teamCheck.get()) || mc.player.distanceTo(target) > range.get() + 2;
		if (lost || ++retarget >= 10) {
			retarget = 0;
			Player picked = pickTarget(lost ? null : target);
			if (picked != target) {
				target = picked;
				if (step != Step.EAT) next(Step.IDLE);
			}
		}
		// Eating goes on with or without someone to fight
		if (step == Step.EAT) {
			eatStep();
			return;
		}
		if (target == null || Mc.screen() != null || mc.player.isUsingItem()) return;
		if (FemboyWare.getModules().get(TrapModule.class).isBusy()) return;

		// A step that can't get done in time is given up
		// Staying alive comes first: these cut in on a crystal spam or an anchor half done
		if (needsApple()) {
			next(Step.EAT);
			startedEating = false;
			eatStep();
			return;
		}
		if (step != Step.TOTEM && autoTotem.get() && totemWait == 0 && !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
			&& InventoryUtil.findHotbar(stack -> stack.is(Items.TOTEM_OF_UNDYING)) >= 0) next(Step.TOTEM);
		if (step != Step.IDLE && ++stepTicks > (step == Step.OBSIDIAN || step == Step.ANCHOR_PLACE ? 20 : 12)) next(Step.IDLE);
		if (step == Step.IDLE) decide();
		switch (step) {
			case IDLE -> {
			}
			case TOTEM -> totemStep();
			case PEARL -> pearlStep();
			case SWORD -> swordStep();
			case OBSIDIAN -> obsidianStep();
			case CRYSTAL -> crystalStep();
			case POP -> popStep();
			case ANCHOR_PLACE -> anchorPlaceStep();
			case ANCHOR_CHARGE -> anchorChargeStep();
			case ANCHOR_BLOW -> anchorBlowStep();
		}
	}

	private void next(Step to) {
		step = to;
		stepTicks = 0;
	}

	/** What to do next: a totem first, a sword hit when it's the moment, then whichever of crystal or anchor hurts most. */
	private void decide() {
		outOfReach = false;
		if (autoTotem.get() && totemWait == 0 && !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && InventoryUtil.findHotbar(stack -> stack.is(Items.TOTEM_OF_UNDYING)) >= 0) {
			next(Step.TOTEM);
			return;
		}
		if (needsApple()) {
			next(Step.EAT);
			startedEating = false;
			return;
		}
		if (pearlTime()) {
			next(Step.PEARL);
			return;
		}
		if (swordTime()) {
			next(Step.SWORD);
			return;
		}
		float crystalDamage = 0, anchorDamage = 0;
		BlockPos crystalBase = null, obsidianSpot = null, anchorSpot = null;
		if (crystals.get() && has(Items.END_CRYSTAL)) {
			crystalBase = bestBase();
			if (crystalBase != null) crystalDamage = worth(crystalBase);
			else if (placeObsidian.get() && has(Items.OBSIDIAN)) {
				obsidianSpot = bestObsidianSpot();
				// A crystal's a tick or two later with obsidian to put down first
				if (obsidianSpot != null) crystalDamage = worth(obsidianSpot) * 0.9f;
			}
		}
		if (anchors.get() && mc.level.dimension() != Level.NETHER && has(Items.RESPAWN_ANCHOR) && has(Items.GLOWSTONE)) {
			anchorSpot = bestAnchorSpot();
			// Three clicks to a crystal's two: it has to be worth it
			if (anchorSpot != null) anchorDamage = anchorWorth(anchorSpot) * 0.85f;
		}
		if (anchorDamage > crystalDamage && anchorSpot != null) {
			anchorAt = anchorSpot;
			next(Step.ANCHOR_PLACE);
		} else if (crystalBase != null) {
			base = crystalBase;
			popped = 0;
			next(crystalOn(base) != null ? Step.POP : Step.CRYSTAL);
		} else if (obsidianSpot != null) {
			obsidianAt = obsidianSpot;
			next(Step.OBSIDIAN);
		} else if (sword.get() && inReach() && mc.player.getAttackStrengthScale(0.5f) >= 0.92f) {
			// Nothing to blow up: just the sword
			next(Step.SWORD);
		} else outOfReach = true;
	}

	/**
	 * A sword hit now: charged, in reach, and they're not still flinching from the last thing (a hit
	 * then only does what it has over that one). The crystal straight after is the double tap.
	 */
	private boolean swordTime() {
		return sword.get() && inReach() && target.hurtTime == 0 && mc.player.getAttackStrengthScale(0.5f) >= 0.92f
			&& InventoryUtil.findHotbar(this::isWeapon) >= 0;
	}

	private boolean inReach() {
		return mc.player.getEyePosition().distanceTo(closestPoint(target.getBoundingBox())) < mc.player.entityInteractionRange() - 0.1;
	}

	private boolean has(Item item) {
		return InventoryUtil.findHotbar(stack -> stack.is(item)) >= 0 || mc.player.getOffhandItem().is(item);
	}

	// ---------------------------------------------------------------- the steps

	private void totemStep() {
		if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
			next(Step.IDLE);
			return;
		}
		if (!hold(stack -> stack.is(Items.TOTEM_OF_UNDYING))) return;
		// The real swap key, as if you pressed F
		KeyMapping.click(InputConstants.getKey(mc.options.keySwapOffhand.saveString()));
		totemWait = 10;
		next(Step.IDLE);
	}

	/**
	 * Who to fight: near and weak (health plus absorption) beats far and healthy. The one already being
	 * fought gets a head start, so it only switches for someone clearly better.
	 */
	private Player pickTarget(Player current) {
		Player best = null;
		double bestScore = Double.MAX_VALUE;
		for (Player player : mc.level.players()) {
			if (!TargetUtil.isValid(player, teamCheck.get())) continue;
			double distance = mc.player.distanceTo(player);
			if (distance > range.get()) continue;
			double score = distance + (player.getHealth() + player.getAbsorptionAmount()) / 3 - (player == current ? 4 : 0);
			if (score < bestScore) {
				bestScore = score;
				best = player;
			}
		}
		return best;
	}

	private boolean low() {
		return mc.player.getHealth() + mc.player.getAbsorptionAmount() <= gappleHealth.get();
	}

	/** Low, with an apple to eat, and not still on the last one's absorption (unless it's getting desperate). */
	private boolean needsApple() {
		return gapple.get() && low() && gappleSlot() >= 0 && (mc.player.getAbsorptionAmount() <= 0 || mc.player.getHealth() <= 6);
	}

	private int gappleSlot() {
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.ENCHANTED_GOLDEN_APPLE));
		return slot >= 0 ? slot : InventoryUtil.findHotbar(stack -> stack.is(Items.GOLDEN_APPLE));
	}

	/** Switches to the apple and holds right click till it's eaten, as you would; nothing else meanwhile. */
	private void eatStep() {
		if (++stepTicks > 60 || Mc.screen() != null) {
			stopEating();
			next(Step.IDLE);
			return;
		}
		if (mc.player.isUsingItem()) {
			startedEating = true;
			return;
		}
		if (startedEating || (!low() && !eating)) {
			// Eaten (or let go of): back to it
			stopEating();
			next(Step.IDLE);
			return;
		}
		int slot = gappleSlot();
		if (slot < 0) {
			stopEating();
			next(Step.IDLE);
			return;
		}
		if (mc.player.getInventory().getSelectedSlot() != slot) {
			InventoryUtil.select(slot);
			return;
		}
		if (sinceSwitch < 1) return;
		mc.options.keyUse.setDown(true);
		eating = true;
	}

	private void stopEating() {
		if (!eating) return;
		eating = false;
		dev.femboyware.util.KeyUtil.sync(mc.options.keyUse);
	}

	/** Too far to walk in, a pearl in the hotbar that's off cooldown, and an arc that gets there. */
	private boolean pearlTime() {
		if (!pearl.get() || mc.player.distanceTo(target) < pearlDistance.get()) return false;
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.ENDER_PEARL));
		return slot >= 0 && !mc.player.getCooldowns().isOnCooldown(mc.player.getInventory().getItem(slot)) && pearlAim() != null;
	}

	/**
	 * Where to look so the pearl lands at their feet, leading them by how far they'll walk while it
	 * flies, using the game's own pearl speed, gravity and drag; null if it can't reach.
	 */
	private Rotation pearlAim() {
		Vec3 from = mc.player.getEyePosition().subtract(0, 0.1, 0);
		Vec3 feet = target.position();
		Vec3 walk = new Vec3(target.getX() - target.xo, 0, target.getZ() - target.zo);
		Vec3 point = feet;
		double[] shot = null;
		for (int i = 0; i < 3; i++) {
			double dx = point.x - from.x, dz = point.z - from.z;
			shot = LegitAuraModule.solve(Math.sqrt(dx * dx + dz * dz), point.y - from.y, 1.5, 0.03, true);
			if (shot == null) return null;
			point = feet.add(walk.scale(shot[1]));
		}
		return new Rotation(Rotation.between(from, point).yaw(), (float) shot[0]);
	}

	private void pearlStep() {
		Rotation aim = pearlAim();
		if (aim == null || mc.player.distanceTo(target) < pearlDistance.get() - 2) {
			next(Step.IDLE);
			return;
		}
		// The view goes to a point far out along that arc's starting line
		float yaw = (float) Math.toRadians(aim.yaw()), pitch = (float) Math.toRadians(aim.pitch());
		aimPoint = mc.player.getEyePosition().add(-Mth.sin(yaw) * Mth.cos(pitch) * 20, -Mth.sin(pitch) * 20, Mth.cos(yaw) * Mth.cos(pitch) * 20);
		if (!hold(stack -> stack.is(Items.ENDER_PEARL))) return;
		if (Math.abs(Mth.wrapDegrees(aim.yaw() - mc.player.getYRot())) < 1.5f && Math.abs(aim.pitch() - mc.player.getXRot()) < 1.5f) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
			next(Step.IDLE);
		}
	}

	private void swordStep() {
		if (!inReach()) {
			next(Step.IDLE);
			return;
		}
		aimPoint = closestPoint(target.getBoundingBox());
		if (!hold(this::isWeapon)) return;
		if (mc.player.getAttackStrengthScale(0.5f) < 0.92f) return;
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == target) {
			((MinecraftClientAccessor) mc).femboyware$leftClick();
			next(Step.IDLE);
		}
	}

	private void obsidianStep() {
		if (!mc.level.getBlockState(obsidianAt).canBeReplaced()) {
			base = mc.level.getBlockState(obsidianAt).is(Blocks.OBSIDIAN) ? obsidianAt : null;
			popped = 0;
			next(base == null ? Step.IDLE : Step.CRYSTAL);
			return;
		}
		BlockHitResult face = visibleFace(obsidianAt);
		if (face == null) {
			next(Step.IDLE);
			return;
		}
		aimPoint = face.getLocation();
		if (!hold(stack -> stack.is(Items.OBSIDIAN))) return;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().relative(hit.getDirection()).equals(obsidianAt)) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
		}
	}

	private void crystalStep() {
		if (!crystalFits(base)) {
			next(crystalOn(base) != null ? Step.POP : Step.IDLE);
			return;
		}
		if (!topVisible(base)) {
			next(Step.IDLE);
			return;
		}
		aimPoint = crystalAim(base);
		if (!hold(stack -> stack.is(Items.END_CRYSTAL))) return;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(base)) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
			next(Step.POP);
		}
	}

	private void popStep() {
		aimPoint = crystalAim(base);
		EndCrystal crystal = crystalOn(base);
		if (crystal == null) {
			if (stepTicks > 6 && crystalFits(base)) next(Step.CRYSTAL);
			return;
		}
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == crystal) {
			((MinecraftClientAccessor) mc).femboyware$leftClick();
			popped++;
			// Straight back to placing on the same obsidian while it's still the move, else think again
			next(again() ? Step.CRYSTAL : Step.IDLE);
		}
	}

	/** Another crystal on the same obsidian: not the sword's moment, not had its fill, still worth it. */
	private boolean again() {
		if (target == null || !TargetUtil.isValid(target, teamCheck.get()) || popped >= maxCrystals.getInt()) return false;
		if (swordTime()) return false;
		return crystalFits(base) && worth(base) > minDamage.getFloat();
	}

	private void anchorPlaceStep() {
		BlockState state = mc.level.getBlockState(anchorAt);
		if (state.is(Blocks.RESPAWN_ANCHOR)) {
			next(Step.ANCHOR_CHARGE);
			return;
		}
		if (!state.canBeReplaced()) {
			next(Step.IDLE);
			return;
		}
		BlockHitResult face = visibleFace(anchorAt);
		if (face == null) {
			next(Step.IDLE);
			return;
		}
		aimPoint = face.getLocation();
		if (!hold(stack -> stack.is(Items.RESPAWN_ANCHOR))) return;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().relative(hit.getDirection()).equals(anchorAt)) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
		}
	}

	private void anchorChargeStep() {
		if (!mc.level.getBlockState(anchorAt).is(Blocks.RESPAWN_ANCHOR)) {
			next(Step.IDLE);
			return;
		}
		if (charges() > 0) {
			next(Step.ANCHOR_BLOW);
			return;
		}
		Vec3 point = visibleSurface(anchorAt);
		if (point == null) {
			next(Step.IDLE);
			return;
		}
		aimPoint = point;
		if (!hold(stack -> stack.is(Items.GLOWSTONE))) return;
		if (lookingAt(anchorAt)) ((MinecraftClientAccessor) mc).femboyware$rightClick();
	}

	private void anchorBlowStep() {
		if (!mc.level.getBlockState(anchorAt).is(Blocks.RESPAWN_ANCHOR) || charges() == 0) {
			// Gone: it went off (or someone broke it)
			next(Step.IDLE);
			return;
		}
		// Still worth it where they are now, and safe for you
		if (anchorWorth(anchorAt) <= minDamage.getFloat() * 0.5f) {
			next(Step.IDLE);
			return;
		}
		Vec3 point = visibleSurface(anchorAt);
		if (point == null) {
			next(Step.IDLE);
			return;
		}
		aimPoint = point;
		// Anything that won't charge it more or put a block down: the sword's best
		if (!hold(stack -> isWeapon(stack) || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.END_CRYSTAL))) return;
		if (lookingAt(anchorAt)) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
			next(Step.IDLE);
		}
	}

	private boolean lookingAt(BlockPos pos) {
		return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	private int charges() {
		BlockState state = mc.level.getBlockState(anchorAt);
		return state.is(Blocks.RESPAWN_ANCHOR) ? state.getValue(RespawnAnchorBlock.CHARGE) : 0;
	}

	// ---------------------------------------------------------------- picking spots

	private BlockPos bestBase() {
		BlockPos trap = FemboyWare.getModules().get(TrapModule.class).crystalSpot(target);
		if (trap != null && (crystalFits(trap) || crystalOn(trap) != null) && topVisible(trap)) return trap;
		BlockPos best = null;
		float bestDamage = minDamage.getFloat();
		BlockPos feet = mc.player.blockPosition();
		int r = (int) Math.ceil(mc.player.blockInteractionRange());
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 2, r))) {
			BlockState state = mc.level.getBlockState(pos);
			if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) continue;
			if (pos.distToCenterSqr(target.position()) > 36 || !(crystalFits(pos) || crystalOn(pos) != null) || !topVisible(pos)) continue;
			float damage = worth(pos);
			if (damage > bestDamage) {
				bestDamage = damage;
				best = pos.immutable();
			}
		}
		return best;
	}

	private BlockPos bestObsidianSpot() {
		BlockPos best = null;
		float bestDamage = minDamage.getFloat();
		BlockPos feet = target.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 0, 2))) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			// Only bodies stop a block going down, not dropped items or xp
			if (!mc.level.getEntities((Entity) null, new AABB(pos), e -> !e.isSpectator() && e.blocksBuilding).isEmpty()) continue;
			if (!mc.level.isEmptyBlock(pos.above()) || !mc.level.isEmptyBlock(pos.above(2))) continue;
			if (visibleFace(pos) == null) continue;
			float damage = worth(pos);
			if (damage > bestDamage) {
				bestDamage = damage;
				best = pos.immutable();
			}
		}
		return best;
	}

	/** An empty spot right next to them (feet, head, or over their head) an anchor can go, hurting them most. */
	private BlockPos bestAnchorSpot() {
		BlockPos best = null;
		float bestDamage = minDamage.getFloat();
		BlockPos feet = target.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-1, 0, -1), feet.offset(1, 2, 1))) {
			// Not inside them, and only the block over their head on the top layer
			if (pos.getY() == feet.getY() + 2 ? pos.getX() != feet.getX() || pos.getZ() != feet.getZ() : pos.getX() == feet.getX() && pos.getZ() == feet.getZ()) continue;
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			if (!mc.level.getEntities((Entity) null, new AABB(pos), e -> !e.isSpectator() && e.blocksBuilding).isEmpty()) continue;
			if (visibleFace(pos) == null) continue;
			float damage = anchorWorth(pos);
			if (damage > bestDamage) {
				bestDamage = damage;
				best = pos.immutable();
			}
		}
		return best;
	}

	/** Damage to them from a crystal on {@code base}, or 0 if it would do you too much. */
	private float worth(BlockPos base) {
		float self = DamageCalculator.crystalDamage(mc.player, base, false, false);
		if (self > maxSelf.getFloat() || self >= mc.player.getHealth() + mc.player.getAbsorptionAmount() - 1) return 0;
		Vec3 center = Vec3.atBottomCenterOf(base.above());
		float now = DamageCalculator.explosionDamage(target, target.getBoundingBox(), center, DamageCalculator.CRYSTAL_POWER, false, false);
		if (!predict.get()) return now;
		return Math.min(now, DamageCalculator.explosionDamage(target, ahead(), center, DamageCalculator.CRYSTAL_POWER, false, false));
	}

	/** Where they'll be a few ticks on if they keep going the way they are. */
	private AABB ahead() {
		return target.getBoundingBox().move((target.getX() - target.xo) * 3, 0, (target.getZ() - target.zo) * 3);
	}

	/** Damage to them from an anchor going off at {@code pos}, or 0 if it would do you too much. */
	private float anchorWorth(BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		float self = DamageCalculator.explosionDamage(mc.player, mc.player.getBoundingBox(), center, ANCHOR_POWER, false, false, null, pos);
		if (self > maxSelf.getFloat() || self >= mc.player.getHealth() + mc.player.getAbsorptionAmount() - 1) return 0;
		float now = DamageCalculator.explosionDamage(target, target.getBoundingBox(), center, ANCHOR_POWER, false, false, null, pos);
		if (!predict.get()) return now;
		return Math.min(now, DamageCalculator.explosionDamage(target, ahead(), center, ANCHOR_POWER, false, false, null, pos));
	}

	private boolean crystalFits(BlockPos base) {
		BlockPos above = base.above();
		if (!mc.level.isEmptyBlock(above)) return false;
		return mc.level.getEntities((Entity) null, new AABB(above).expandTowards(0, 1, 0), e -> !e.isSpectator()).isEmpty();
	}

	private EndCrystal crystalOn(BlockPos base) {
		for (Entity entity : mc.level.getEntities((Entity) null, new AABB(base.above()), e -> e instanceof EndCrystal)) {
			if (entity.blockPosition().equals(base.above())) return (EndCrystal) entity;
		}
		return null;
	}

	// ---------------------------------------------------------------- what can be seen

	private Vec3 crystalAim(BlockPos base) {
		Vec3 picked = topSpots.get(base);
		if (picked != null) return picked;
		Vec3 top = Vec3.atCenterOf(base).add(0, 0.5, 0);
		Vec3 toward = mc.player.getEyePosition().subtract(top).multiply(1, 0, 1);
		if (toward.lengthSqr() > 1e-4) top = top.add(toward.normalize().scale(0.2));
		return top;
	}

	private boolean clearOfEntities(Vec3 eyes, Vec3 point) {
		AABB span = new AABB(eyes, point).inflate(1);
		for (Entity entity : mc.level.getEntities(mc.player, span, e -> !e.isSpectator() && e.isPickable())) {
			if (entity.getBoundingBox().inflate(entity.getPickRadius()).clip(eyes, point).isPresent()) return false;
		}
		return true;
	}

	/** Some spot on top of {@code pos} can be seen and reached, clear of bodies; remembered for the aim. */
	private boolean topVisible(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 middle = Vec3.atCenterOf(pos).add(0, 0.5, 0);
		Vec3 toward = eyes.subtract(middle).multiply(1, 0, 1);
		toward = toward.lengthSqr() > 1e-4 ? toward.normalize() : Vec3.ZERO;
		Vec3 side = new Vec3(-toward.z, 0, toward.x);
		for (double[] o : new double[][]{{0.2, 0}, {0, 0}, {0.35, 0}, {0.2, 0.25}, {0.2, -0.25}, {0, 0.3}, {0, -0.3}, {-0.2, 0}, {0.35, 0.35}, {0.35, -0.35}}) {
			Vec3 point = middle.add(toward.scale(o[0])).add(side.scale(o[1]));
			if (eyes.distanceTo(point) > mc.player.blockInteractionRange() - 0.3) continue;
			BlockHitResult hit = mc.level.clip(new ClipContext(eyes, point.add(0, -0.05, 0), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getDirection() != Direction.UP) continue;
			if (!clearOfEntities(eyes, point)) continue;
			topSpots.put(pos.immutable(), point);
			return true;
		}
		return false;
	}

	/** A spot on any face of the block at {@code pos} itself that can be seen and reached, clear of bodies. */
	private Vec3 visibleSurface(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		double reach = mc.player.blockInteractionRange() - 0.3;
		Vec3 best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			Vec3 middle = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			for (double[] o : new double[][]{{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}}) {
				Vec3 point = middle.add(switch (face.getAxis()) {
					case X -> new Vec3(0, o[0], o[1]);
					case Y -> new Vec3(o[0], 0, o[1]);
					case Z -> new Vec3(o[0], o[1], 0);
				});
				double distance = eyes.distanceTo(point);
				if (distance > reach || distance >= bestDistance) continue;
				Vec3 through = point.add(eyes.subtract(point).normalize().scale(-0.05));
				BlockHitResult hit = mc.level.clip(new ClipContext(eyes, through, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getDirection() != face) continue;
				if (!clearOfEntities(eyes, point)) continue;
				best = point;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** A face next to {@code pos} that can be seen and reached, clicked to put a block there. */
	private BlockHitResult visibleFace(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		double reach = mc.player.blockInteractionRange() - 0.3;
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (mc.level.getBlockState(neighbour).canBeReplaced()) continue;
			// Clicking an anchor uses it instead of putting a block against it
			if (mc.level.getBlockState(neighbour).is(Blocks.RESPAWN_ANCHOR)) continue;
			Direction face = direction.getOpposite();
			Vec3 middle = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			for (double[] o : new double[][]{{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}, {0.3, 0.3}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}}) {
				Vec3 point = middle.add(switch (face.getAxis()) {
					case X -> new Vec3(0, o[0], o[1]);
					case Y -> new Vec3(o[0], 0, o[1]);
					case Z -> new Vec3(o[0], o[1], 0);
				});
				double distance = eyes.distanceTo(point);
				if (distance > reach || distance >= bestDistance) continue;
				Vec3 through = point.add(eyes.subtract(point).normalize().scale(-0.05));
				BlockHitResult hit = mc.level.clip(new ClipContext(eyes, through, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(neighbour) || hit.getDirection() != face) continue;
				if (!clearOfEntities(eyes, point)) continue;
				best = new BlockHitResult(point, face, neighbour, false);
				bestDistance = distance;
			}
		}
		return best;
	}

	private Vec3 closestPoint(AABB box) {
		Vec3 eyes = mc.player.getEyePosition();
		return new Vec3(Mth.clamp(eyes.x, box.minX + 0.1, box.maxX - 0.1), Mth.clamp(eyes.y, box.minY + 0.2, box.maxY - 0.2),
			Mth.clamp(eyes.z, box.minZ + 0.1, box.maxZ - 0.1));
	}

	// ---------------------------------------------------------------- hands

	private boolean isWeapon(ItemStack stack) {
		for (Item item : WEAPONS) if (stack.is(item)) return true;
		return false;
	}

	/** Holding it, or switching to it for real; true once it's in hand and a tick has passed, as yours would. */
	private boolean hold(Predicate<ItemStack> item) {
		if (item.test(mc.player.getMainHandItem())) return sinceSwitch >= 1;
		if (item.test(mc.player.getOffhandItem()) && !mc.player.getMainHandItem().is(Items.OBSIDIAN) && !mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)
			&& !mc.player.getMainHandItem().is(Items.GLOWSTONE)) return sinceSwitch >= 1;
		int slot = -1;
		if (item.test(new ItemStack(Items.NETHERITE_SWORD)) || item.test(new ItemStack(Items.DIAMOND_SWORD))) {
			for (Item weapon : WEAPONS) {
				slot = InventoryUtil.findHotbar(stack -> stack.is(weapon));
				if (slot >= 0) break;
			}
		}
		if (slot < 0) slot = InventoryUtil.findHotbar(item);
		if (slot < 0) {
			next(Step.IDLE);
			return false;
		}
		InventoryUtil.select(slot);
		return false;
	}

	// ---------------------------------------------------------------- moving

	/**
	 * Walks to {@link #distance} from them along an A* path (round walls, up steps, down drops, off
	 * lava and fire), worked out again whenever they've moved off its end, pressing whichever keys
	 * head to the next block of it from where the camera faces. Backing off is straight away.
	 */
	@Override
	public void onInput() {
		if (pathCooldown > 0) pathCooldown--;
		if (!move.get() || target == null || Mc.screen() != null || mc.player == null) {
			path = null;
			return;
		}
		Input keys = mc.player.input.keyPresses;
		Vec3 pos = mc.player.position();
		double gap = Math.hypot(target.getX() - pos.x, target.getZ() - pos.z);
		double wanted = outOfReach ? 2 : distance.get();
		boolean closer = gap > wanted + 0.4, away = !outOfReach && gap < wanted - 1.2;
		if (!closer && !away) {
			path = null;
			if (strafe.get() && !outOfReach && mc.player.onGround()) {
				if (--strafeTimer <= 0 || mc.player.horizontalCollision) {
					strafeLeft = !strafeLeft;
					strafeTimer = 15 + ThreadLocalRandom.current().nextInt(25);
				}
				// Sideways from where the view is, which is round about them
				mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left() || strafeLeft, keys.right() || !strafeLeft,
					keys.jump(), keys.shift(), keys.sprint());
				((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(strafeLeft ? 1 : -1, 0));
			}
			return;
		}
		Vec3 toward = new Vec3(target.getX() - pos.x, 0, target.getZ() - pos.z);
		boolean climb = false;
		if (away) {
			path = null;
			toward = toward.scale(-1);
		} else {
			Vec3 goal = target.position();
			if (pathCooldown == 0 && (path == null || pathGoal.distanceTo(goal) > 2 || pathIndex >= path.size())) {
				path = Pathfinder.find(mc.level, BlockPos.containing(pos.add(0, 0.2, 0)), BlockPos.containing(goal), 6000);
				pathIndex = 0;
				pathGoal = goal;
				pathCooldown = 10;
			}
			if (path != null) {
				while (pathIndex < path.size()) {
					Vec3 next = path.get(pathIndex);
					if (Math.hypot(next.x - pos.x, next.z - pos.z) < 0.55 && Math.abs(next.y - pos.y) < 1.5) pathIndex++;
					else break;
				}
				if (pathIndex < path.size()) {
					Vec3 next = path.get(pathIndex);
					Vec3 step = new Vec3(next.x - pos.x, 0, next.z - pos.z);
					if (step.lengthSqr() > 1e-4) toward = step;
					climb = next.y > pos.y + 0.4;
				}
			}
		}
		float heading = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(toward.z, toward.x)) - 90 - mc.player.getYRot());
		boolean forward = Math.abs(heading) < 67.5f, back = Math.abs(heading) > 112.5f;
		boolean left = heading < -22.5f && heading > -157.5f, right = heading > 22.5f && heading < 157.5f;
		// Sprint whenever there's a way to go, round the path's turns too
		boolean sprint = forward && closer && gap > wanted + 1.5;
		boolean jump = mc.player.onGround() && (climb || mc.player.horizontalCollision);
		mc.player.input.keyPresses = new Input(keys.forward() || forward, keys.backward() || back, keys.left() || left, keys.right() || right,
			keys.jump() || jump, keys.shift(), keys.sprint() || sprint);
		int forwardAxis = (forward ? 1 : 0) - (back ? 1 : 0);
		int strafeAxis = (left ? 1 : 0) - (right ? 1 : 0);
		Vec2 movement = new Vec2(strafeAxis, forwardAxis);
		((InputAccessor) mc.player.input).femboyware$setMovementVector(movement.lengthSquared() > 0 ? movement.normalized() : Vec2.ZERO);
	}

	// ---------------------------------------------------------------- the view

	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (seconds <= 0 || mc.player == null || Mc.screen() != null) {
			turner.reset();
			return;
		}
		// Between actions the view rests on them, like anyone watching who they're fighting
		Vec3 point = aimPoint != null ? aimPoint : target != null ? target.getBoundingBox().getCenter() : null;
		if (point == null) {
			turner.reset();
			return;
		}
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Rotation wanted = Rotation.between(mc.player.getEyePosition(tickDelta), point);
		double yawLeft = Mth.wrapDegrees(wanted.yaw() - mc.player.getYRot());
		double pitchLeft = wanted.pitch() - mc.player.getXRot();
		double follow = 1 - Math.exp(-(aimPoint != null ? flickSpeed.get() : flickSpeed.get() * 0.4) * seconds);
		double wobble = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3;
		turner.turn(yawLeft * follow * wobble, pitchLeft * follow * wobble);
	}
}
