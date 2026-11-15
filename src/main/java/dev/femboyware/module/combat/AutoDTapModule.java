package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.MouseTurner;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The double tap, done for you the legit way: a full sword hit on them, then an end crystal on the
 * obsidian that hurts them most (putting obsidian down next to them first if there's none good),
 * popped as soon as it appears, then back to the sword. Everything is a real flick of your view in
 * whole mouse steps and a real left or right click on what's under the crosshair, one per tick,
 * with real hotbar switches. With Legit Aura on, its hits start each tap and it waits while the
 * crystal goes in.
 */
public class AutoDTapModule extends Module {
	private enum Step { HIT, OBSIDIAN, CRYSTAL, POP }

	private static final Item[] WEAPONS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.COPPER_SWORD,
		Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD, Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE};

	private final NumberSetting range = add(new NumberSetting("Range", "How far away a target can be", 5, 3, 6, 0.25));
	private final NumberSetting minDamage = add(new NumberSetting("Min Damage", "Least damage a crystal has to do to them to be worth it", 6, 1, 20, 0.5));
	private final NumberSetting maxSelf = add(new NumberSetting("Max Self", "Most damage a crystal may do to you", 8, 0, 20, 0.5));
	private final BoolSetting placeObsidian = add(new BoolSetting("Place Obsidian", "Put obsidian down next to them when there's none a crystal would do enough damage on", true));
	private final NumberSetting flickSpeed = add(new NumberSetting("Flick Speed", "How fast your view flicks onto each thing, in whole mouse steps at your sensitivity", 28, 6, 60, 1));
	private final BoolSetting hitFirst = add(new BoolSetting("Hit First", "Start every tap with a full sword hit (or Legit Aura's)", true));

	private final MouseTurner turner = new MouseTurner();
	private Player target;
	private Step step = Step.HIT;
	private int stepTicks;
	/** The obsidian the crystal is going on, and where obsidian is going down. */
	private BlockPos base, obsidianAt;
	/** Where the view's flicking to this tick, or null. */
	private Vec3 aimPoint;
	private long lastFrame;
	private int lastSlot = -1, sinceSwitch;
	private float lastStrength = 1;

	public AutoDTapModule() {
		super("Auto DTap", "Hits them, then crystals them on the best obsidian (placing it if needed), all with real flicks and clicks", Category.LEGIT);
	}

	@Override
	public String getInfo() {
		return target == null ? null : step.name().charAt(0) + step.name().substring(1).toLowerCase();
	}

	@Override
	protected void onDisable() {
		target = null;
		aimPoint = null;
		step = Step.HIT;
	}

	/** Putting a crystal in: Legit Aura doesn't aim, swing or switch meanwhile. */
	public boolean isBusy() {
		return isEnabled() && target != null && step != Step.HIT;
	}

	private boolean legitAuraOn() {
		return FemboyWare.isEnabled(LegitAuraModule.class);
	}

	@Override
	public void onTick() {
		aimPoint = null;
		if (nullCheck()) return;
		int selected = mc.player.getInventory().getSelectedSlot();
		if (selected != lastSlot) {
			lastSlot = selected;
			sinceSwitch = 0;
		} else sinceSwitch++;
		float strength = mc.player.getAttackStrengthScale(0.5f);
		boolean swungNow = strength < 0.3f && lastStrength > 0.8f;
		lastStrength = strength;

		if (target == null || !TargetUtil.isValid(target) || mc.player.distanceTo(target) > range.get() + 1) {
			target = TargetUtil.closestPlayer(range.get());
			step = Step.HIT;
			stepTicks = 0;
		}
		if (target == null || Mc.screen() != null || mc.player.isUsingItem()) return;
		if (InventoryUtil.findHotbar(stack -> stack.is(Items.END_CRYSTAL)) < 0 && !mc.player.getOffhandItem().is(Items.END_CRYSTAL)) return;

		// A step that can't get done in time is given up, back to the sword
		if (step != Step.HIT && ++stepTicks > 16) next(Step.HIT);
		switch (step) {
			case HIT -> hitStep(swungNow);
			case OBSIDIAN -> obsidianStep();
			case CRYSTAL -> crystalStep();
			case POP -> popStep();
		}
	}

	private void next(Step to) {
		step = to;
		stepTicks = 0;
	}

	// ---------------------------------------------------------------- the steps

	private void hitStep(boolean swungNow) {
		if (!hitFirst.get()) {
			startCrystal();
			return;
		}
		if (legitAuraOn()) {
			// Legit Aura does the hitting; the crystal follows its swing
			if (swungNow && FemboyWare.getModules().get(LegitAuraModule.class).getTarget() == target) startCrystal();
			return;
		}
		if (!hold(this::isWeapon)) return;
		aimPoint = closestPoint(target.getBoundingBox());
		if (mc.player.getAttackStrengthScale(0.5f) < 0.92f) return;
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == target) {
			((MinecraftClientAccessor) mc).femboyware$leftClick();
			startCrystal();
		}
	}

	/** Picks where the crystal goes: the best obsidian there is, or a spot for new obsidian. */
	private void startCrystal() {
		base = bestBase();
		if (base != null) {
			next(Step.CRYSTAL);
			return;
		}
		obsidianAt = placeObsidian.get() && InventoryUtil.findHotbar(stack -> stack.is(Items.OBSIDIAN)) >= 0 ? bestObsidianSpot() : null;
		if (obsidianAt != null) next(Step.OBSIDIAN);
	}

	private void obsidianStep() {
		if (!mc.level.getBlockState(obsidianAt).canBeReplaced()) {
			// It's in: the crystal goes on it
			base = mc.level.getBlockState(obsidianAt).is(Blocks.OBSIDIAN) ? obsidianAt : null;
			if (base == null) next(Step.HIT);
			else next(Step.CRYSTAL);
			return;
		}
		BlockHitResult face = visibleFace(obsidianAt);
		if (face == null) {
			next(Step.HIT);
			return;
		}
		aimPoint = face.getLocation();
		if (!hold(stack -> stack.is(Items.OBSIDIAN))) return;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
			&& hit.getBlockPos().relative(hit.getDirection()).equals(obsidianAt)) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
		}
	}

	private void crystalStep() {
		if (!crystalFits(base)) {
			EndCrystal there = crystalOn(base);
			if (there != null) next(Step.POP);
			else next(Step.HIT);
			return;
		}
		Vec3 top = Vec3.atCenterOf(base).add(0, 0.5, 0);
		aimPoint = top;
		if (!hold(stack -> stack.is(Items.END_CRYSTAL))) return;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(base)) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
			next(Step.POP);
		}
	}

	private void popStep() {
		EndCrystal crystal = crystalOn(base);
		if (crystal == null) {
			// Not there yet (it's up to the server), or already gone
			if (stepTicks > 6 && crystalFits(base)) next(Step.CRYSTAL);
			else aimPoint = Vec3.atCenterOf(base).add(0, 1.0, 0);
			return;
		}
		aimPoint = crystal.getBoundingBox().getCenter();
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == crystal) {
			((MinecraftClientAccessor) mc).femboyware$leftClick();
			next(Step.HIT);
		}
	}

	// ---------------------------------------------------------------- picking spots

	/** The obsidian or bedrock in reach and in view that a crystal on hurts them most and you little enough. */
	private BlockPos bestBase() {
		BlockPos best = null;
		float bestDamage = minDamage.getFloat();
		BlockPos feet = mc.player.blockPosition();
		int r = (int) Math.ceil(mc.player.blockInteractionRange());
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 2, r))) {
			BlockState state = mc.level.getBlockState(pos);
			if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) continue;
			if (pos.distToCenterSqr(target.position()) > 36 || !crystalFits(pos) || !topVisible(pos)) continue;
			float damage = worth(pos);
			if (damage > bestDamage) {
				bestDamage = damage;
				best = pos.immutable();
			}
		}
		return best;
	}

	/** Where new obsidian would let a crystal hurt them most: on the ground right round them. */
	private BlockPos bestObsidianSpot() {
		BlockPos best = null;
		float bestDamage = minDamage.getFloat();
		BlockPos feet = target.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 0, 2))) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			if (!mc.level.getEntities((Entity) null, new AABB(pos), e -> !e.isSpectator()).isEmpty()) continue;
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

	/** Damage to them from a crystal on {@code base}, or 0 if it would do you too much. */
	private float worth(BlockPos base) {
		float self = DamageCalculator.crystalDamage(mc.player, base, false, false);
		if (self > maxSelf.getFloat() || self >= mc.player.getHealth() + mc.player.getAbsorptionAmount() - 1) return 0;
		return DamageCalculator.crystalDamage(target, base, false, false);
	}

	/** A crystal can go on {@code base}: air above, nothing standing in the way. */
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

	/** The top of {@code pos} can be seen and reached, so the crosshair can land on it. */
	private boolean topVisible(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 top = Vec3.atCenterOf(pos).add(0, 0.5, 0);
		if (eyes.distanceTo(top) > mc.player.blockInteractionRange() - 0.3) return false;
		BlockHitResult hit = mc.level.clip(new ClipContext(eyes, top.add(0, -0.05, 0), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
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
			Direction face = direction.getOpposite();
			Vec3 point = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			double distance = eyes.distanceTo(point);
			if (distance > reach || distance >= bestDistance) continue;
			Vec3 through = point.add(eyes.subtract(point).normalize().scale(-0.05));
			BlockHitResult hit = mc.level.clip(new ClipContext(eyes, through, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(neighbour) || hit.getDirection() != face) continue;
			best = new BlockHitResult(point, face, neighbour, false);
			bestDistance = distance;
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

	/**
	 * Holding the item, or switching to it for real (the click waits a tick after a switch, as
	 * yours would). True once it's in hand and settled.
	 */
	private boolean hold(Predicate<ItemStack> item) {
		if (item.test(mc.player.getMainHandItem())) return sinceSwitch >= 1;
		// Crystals in the offhand: the main hand must not be holding a block that'd go down first
		if (item.test(mc.player.getOffhandItem()) && !mc.player.getMainHandItem().is(Items.OBSIDIAN)) return sinceSwitch >= 1;
		int slot = -1;
		if (item.test(new ItemStack(Items.NETHERITE_SWORD)) || item.test(new ItemStack(Items.DIAMOND_SWORD))) {
			// The best weapon there is
			for (Item weapon : WEAPONS) {
				slot = InventoryUtil.findHotbar(stack -> stack.is(weapon));
				if (slot >= 0) break;
			}
		} else slot = InventoryUtil.findHotbar(item);
		if (slot < 0) return false;
		InventoryUtil.select(slot);
		return false;
	}

	// ---------------------------------------------------------------- the view

	/** A quick flick onto the aim point, every frame in whole mouse steps, easing in, a little uneven. */
	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (aimPoint == null || seconds <= 0 || mc.player == null || Mc.screen() != null) {
			turner.reset();
			return;
		}
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Rotation wanted = Rotation.between(mc.player.getEyePosition(tickDelta), aimPoint);
		double yawLeft = Mth.wrapDegrees(wanted.yaw() - mc.player.getYRot());
		double pitchLeft = wanted.pitch() - mc.player.getXRot();
		double follow = 1 - Math.exp(-flickSpeed.get() * seconds);
		double wobble = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3;
		turner.turn(yawLeft * follow * wobble, pitchLeft * follow * wobble);
	}
}
