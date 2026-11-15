package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.lb.RotationSettings;
import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.FriendsModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.FreeLook;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.MoveFix;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Boxes the target in with blocks: a ring at the feet, a ring at the head and a roof.
 *
 * <p>Legit mode aims at every block it places (with Real Look your head really turns there while the
 * camera stays free) and goes in stages: the blocks behind the target first, then a knockback hit
 * that pins them against that wall, then a cobweb or lava at their feet, then the rest of the walls
 * and the roof. Packet mode is the old way: several blocks a tick with look packets.
 */
public class TrapModule extends Module {
	private static final int ROTATION_PRIORITY = 70;

	public enum Mode { LEGIT, PACKET }
	public enum Fill { NONE, COBWEB, LAVA }
	public enum Stage { BACK, HIT, FILL, WALLS, DONE }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Legit aims at every block (and can turn your real head); Packet places several a tick with look packets", Mode.LEGIT));
	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 6, 2, 10, 0.5));
	private final BoolSetting mobs = add(new BoolSetting("Mobs", "Trap mobs too, not just players", false));
	private final BoolSetting anyBlock = add(new BoolSetting("Any Block", "Use any solid block when out of obsidian (the toughest one you have)", true));
	private final BoolSetting feet = add(new BoolSetting("Feet", "Place the ring around the target's feet", true));
	private final BoolSetting roof = add(new BoolSetting("Roof", "Place a block above the target's head", true));
	private final BoolSetting backFirst = add(new BoolSetting("Back First", "Wall them in from behind before anything else", true));
	private final BoolSetting knockback = add(new BoolSetting("Knockback Hit", "After the back wall, a sprint hit with your best knockback weapon pins them against it", true));
	private final EnumSetting<Fill> fill = add(new EnumSetting<>("Fill", "Put a cobweb or lava at their feet before closing the walls", Fill.NONE));
	private final BoolSetting jump = add(new BoolSetting("Jump", "Jump while trapping, to reach the high blocks and look the part", true)).visibleWhen(() -> mode.is(Mode.LEGIT));
	private final BoolSetting realLook = add(new BoolSetting("Real Look", "Really turn your head to each block (no silent rotation), with free look so you can still look anywhere", true)).visibleWhen(() -> mode.is(Mode.LEGIT));
	private final BoolSetting thirdPerson = add(new BoolSetting("Third Person", "Switch to third person while Real Look turns your head", true)).visibleWhen(() -> mode.is(Mode.LEGIT) && realLook.get());
	private final RotationSettings rotations = new RotationSettings(s -> add(s), () -> mode.is(Mode.LEGIT), 45f);
	private final NumberSetting blocksPerTick = add(new NumberSetting("Blocks Per Tick", "Max placements per tick", 4, 1, 12, 1)).visibleWhen(() -> mode.is(Mode.PACKET));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true)).visibleWhen(() -> mode.is(Mode.PACKET));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false)).visibleWhen(() -> mode.is(Mode.PACKET));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to blocks (Legit always really switches, a tick before using it, and goes back after)", SwitchMode.SILENT)).visibleWhen(() -> mode.is(Mode.PACKET));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight trap positions", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0x40FF5577)).visibleWhen(render::get);
	private final BoolSetting grim = addGrim();

	private Set<BlockPos> positions = Set.of();
	private LivingEntity target;
	private BlockPos trappedAt;
	private Stage stage = Stage.DONE;
	private boolean hitDone, filled, working;
	private int hitTicks, fillWait;
	/** What the legit placement is aiming at this tick. */
	private BlockPos aimingAt;
	/** Nothing left to place can be seen from here: a jump might show it, or it's too far and you walk closer. */
	private boolean needJump, needApproach, needBackOff;
	/** Legit: the slot you had before the trap took over, to go back to after; -1 when nothing to restore. */
	private int originalSlot = -1;
	private int lastSlot = -1, sinceSwitch;

	public TrapModule() {
		super("Trap", "Traps your target in blocks: back wall, knockback hit, web or lava, then the rest", Category.ATTACK);
	}

	@Override
	public String getInfo() {
		return target == null ? null : stage == Stage.DONE ? "Trapped" : EnumSetting.prettify(stage.name());
	}

	@Override
	protected void onDisable() {
		positions = Set.of();
		target = null;
		trappedAt = null;
		working = false;
		restoreSlot();
	}

	/**
	 * Legit mode is taking turns with Aura: while it traps, Aura doesn't aim or swing, so the two
	 * never fight over your look or do two things in one tick.
	 */
	public boolean isBusy() {
		return isEnabled() && working && mode.is(Mode.LEGIT);
	}

	/** Legit: back to the slot you had before the trap, like switching back to your sword. */
	private void restoreSlot() {
		if (originalSlot >= 0 && mc.player != null && mc.player.getInventory().getSelectedSlot() != originalSlot) InventoryUtil.select(originalSlot);
		originalSlot = -1;
	}

	/**
	 * Legit: really switch to the item and give it a tick in your hand before using it, like a
	 * player scrolling to it; true once it's ready.
	 */
	private boolean ready(Predicate<ItemStack> item) {
		if (item.test(mc.player.getOffhandItem()) && !item.test(mc.player.getMainHandItem())) return true;
		int slot = InventoryUtil.findHotbar(item);
		if (slot < 0) return false;
		return readySlot(slot);
	}

	private boolean readySlot(int slot) {
		if (mc.player.getInventory().getSelectedSlot() != slot) {
			if (originalSlot < 0) originalSlot = mc.player.getInventory().getSelectedSlot();
			InventoryUtil.select(slot);
			lastSlot = slot;
			sinceSwitch = 0;
			return false;
		}
		return sinceSwitch >= 1;
	}

	// ---------------------------------------------------------------- blocks

	private boolean isTrapBlock(ItemStack stack) {
		if (stack.is(Items.OBSIDIAN) || stack.is(Items.CRYING_OBSIDIAN)) return true;
		if (!anyBlock.get() || !(stack.getItem() instanceof BlockItem item)) return false;
		Block block = item.getBlock();
		if (block instanceof FallingBlock || block instanceof BaseEntityBlock) return false;
		return block.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO);
	}

	/** The toughest trap block you hold or have on the hotbar, or null. */
	private ItemStack bestBlock() {
		ItemStack best = null;
		if (isTrapBlock(mc.player.getOffhandItem())) best = mc.player.getOffhandItem();
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (!isTrapBlock(stack)) continue;
			if (best == null || resistance(stack) > resistance(best)) best = stack;
		}
		return best;
	}

	private static float resistance(ItemStack stack) {
		return ((BlockItem) stack.getItem()).getBlock().getExplosionResistance();
	}

	// ---------------------------------------------------------------- targets

	private LivingEntity findTarget() {
		LivingEntity best = TargetUtil.closestPlayer(targetRange.get());
		if (!mobs.get()) return best;
		double bestDistance = best == null ? targetRange.get() * targetRange.get() : mc.player.distanceToSqr(best);
		for (var entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Mob mob) || !mob.isAlive() || FriendsModule.protects(mob)) continue;
			double d = mc.player.distanceToSqr(mob);
			if (d < bestDistance) {
				bestDistance = d;
				best = mob;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- the tick

	@Override
	public void onTick() {
		working = false;
		aimingAt = null;
		needJump = needApproach = needBackOff = false;
		int selected = mc.player.getInventory().getSelectedSlot();
		if (selected != lastSlot) {
			lastSlot = selected;
			sinceSwitch = 0;
		} else {
			sinceSwitch++;
		}
		LivingEntity found = findTarget();
		if (found != target) {
			target = found;
			resetStages();
		}
		if (target == null) {
			positions = Set.of();
			restoreSlot();
			return;
		}
		BlockPos feetPos = BlockPos.containing(target.getX(), target.getY() + 0.2, target.getZ());
		// Knocked up or jumping: the trap stays around where they'll land
		for (int i = 0; i < 2 && !target.onGround() && mc.level.getBlockState(feetPos.below()).canBeReplaced(); i++) feetPos = feetPos.below();
		if (!feetPos.equals(trappedAt)) {
			// They moved out: the trap starts over around where they are now
			trappedAt = feetPos;
			resetStages();
		}
		positions = withRoofSupport(trapPositions(target, feet.get(), roof.get()));
		List<BlockPos> missing = new ArrayList<>();
		for (BlockPos pos : positions) if (mc.level.getBlockState(pos).canBeReplaced()) missing.add(pos);
		List<BlockPos> back = backPositions(missing);

		if (backFirst.get() && !back.isEmpty()) stage = Stage.BACK;
		else if (knockback.get() && !hitDone) stage = Stage.HIT;
		else if (!fill.is(Fill.NONE) && !filled) stage = Stage.FILL;
		else stage = missing.isEmpty() ? Stage.DONE : Stage.WALLS;

		if (stage == Stage.DONE) {
			restoreSlot();
			return;
		}
		working = true;
		if (mode.is(Mode.LEGIT) && realLook.get()) FreeLook.hold(thirdPerson.get());
		// Eating, blocking or in a menu: a player can't place or hit then, so neither does Legit
		if (mode.is(Mode.LEGIT) && (mc.player.isUsingItem() || Mc.screen() != null)) return;
		switch (stage) {
			case BACK -> placeAll(back);
			case HIT -> hit();
			case FILL -> fill(feetPos);
			case WALLS -> placeAll(missing);
			default -> {
			}
		}
	}

	/**
	 * A roof has nothing beside it to click on until something's there: a block on top of the head
	 * ring (on the far side) goes in just before it.
	 */
	private Set<BlockPos> withRoofSupport(Set<BlockPos> trap) {
		if (!roof.get()) return trap;
		Set<BlockPos> result = new LinkedHashSet<>();
		for (BlockPos pos : trap) {
			if (pos.getY() == trappedAt.getY() + 2 && mc.level.getBlockState(pos).canBeReplaced() && BlockUtil.getPlacement(pos, false) == null) {
				// On the far side: its face towards the roof then faces you, so you can click it over the walls
				Direction away = Direction.getApproximateNearest(target.getX() - mc.player.getX(), 0, target.getZ() - mc.player.getZ());
				result.add(pos.relative(away));
			}
			result.add(pos);
		}
		return result;
	}

	private void resetStages() {
		hitDone = false;
		filled = false;
		hitTicks = 0;
		fillWait = 0;
	}

	/** The missing blocks on the far side of the target from you: the wall they get knocked into. */
	private List<BlockPos> backPositions(List<BlockPos> missing) {
		List<BlockPos> back = new ArrayList<>();
		Vec3 away = target.position().subtract(mc.player.position());
		away = new Vec3(away.x, 0, away.z);
		if (away.lengthSqr() < 1e-4) return back;
		away = away.normalize();
		for (BlockPos pos : missing) {
			if (pos.getY() > trappedAt.getY() + 1) continue;
			Vec3 offset = Vec3.atCenterOf(pos).subtract(target.position());
			if (new Vec3(offset.x, 0, offset.z).normalize().dot(away) > 0.3) back.add(pos);
		}
		return back;
	}

	private void placeAll(List<BlockPos> list) {
		ItemStack block = bestBlock();
		if (block == null) return;
		Predicate<ItemStack> item = stack -> stack.is(block.getItem());
		if (mode.is(Mode.PACKET)) {
			int placed = 0;
			for (BlockPos pos : list) {
				if (placed >= blocksPerTick.getInt()) break;
				if (PlaceUtil.place(pos, item, switchMode.get(), rotate.get(), strict.get())) placed++;
			}
			return;
		}
		// Furthest first: the near blocks would stand between your eyes and the far ones
		Vec3 eyes = mc.player.getEyePosition();
		List<BlockPos> ordered = new ArrayList<>(list);
		ordered.sort((a, b) -> Double.compare(Vec3.atCenterOf(b).distanceToSqr(eyes), Vec3.atCenterOf(a).distanceToSqr(eyes)));
		Vec3 lookAt = null;
		for (BlockPos pos : ordered) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			if (BlockUtil.hasBlockingEntity(pos, false)) {
				// Standing in it yourself: step back out of the way
				if (mc.player.getBoundingBox().intersects(new AABB(pos))) needBackOff = true;
				continue;
			}
			BlockHitResult planned = visiblePlacement(pos);
			if (planned == null) {
				if (lookAt == null) lookAt = Vec3.atCenterOf(pos);
				continue;
			}
			aimingAt = pos;
			placeAimed(planned, item);
			return;
		}
		// Nothing to click from here yet: look at the next block while jumping or walking to it
		if (lookAt != null) aim(lookAt);
	}

	/**
	 * A click that places at {@code pos} on a spot of a neighbouring face your eyes can actually see
	 * and reach, or null; sets {@link #needJump} or {@link #needApproach} when there's none.
	 */
	private BlockHitResult visiblePlacement(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		double reach = mc.player.blockInteractionRange();
		boolean inReach = false, anyNeighbour = false;
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (mc.level.getBlockState(neighbour).canBeReplaced()) continue;
			anyNeighbour = true;
			Direction face = direction.getOpposite();
			Vec3 center = BlockUtil.faceCenter(neighbour, face);
			// The centre first, then spots towards the edges
			for (double[] o : new double[][]{{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}, {0.3, 0.3}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}}) {
				Vec3 point = center.add(offset(face, o[0], o[1]));
				double distance = eyes.distanceTo(point);
				if (distance > reach) continue;
				inReach = true;
				if (distance >= bestDistance) continue;
				Vec3 through = point.add(eyes.subtract(point).normalize().scale(-0.05));
				BlockHitResult hit = mc.level.clip(new ClipContext(eyes, through, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(neighbour) || hit.getDirection() != face) continue;
				best = new BlockHitResult(point, face, neighbour, false);
				bestDistance = distance;
			}
		}
		// Nothing to click on yet (a roof before its support) is no reason to move
		if (best == null && anyNeighbour) {
			if (inReach) needJump = true;
			else needApproach = true;
		}
		return best;
	}

	/** {@code a} and {@code b} along the two axes lying in {@code face}. */
	private static Vec3 offset(Direction face, double a, double b) {
		return switch (face.getAxis()) {
			case X -> new Vec3(0, a, b);
			case Y -> new Vec3(a, 0, b);
			case Z -> new Vec3(a, b, 0);
		};
	}

	// ---------------------------------------------------------------- legit placement

	private void aim(Vec3 point) {
		FemboyWare.getRotations().request(Rotation.between(mc.player.getEyePosition(), point), ROTATION_PRIORITY, rotations, realLook.get());
	}

	/** Where the rotation the server has looks, or null. */
	private BlockHitResult seen() {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 end = eyes.add(FemboyWare.getRotations().getServerRotation().toVector().scale(mc.player.blockInteractionRange()));
		BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	/** Clicks {@code planned} once the server rotation looks at it; turns there otherwise. */
	private boolean placeAimed(BlockHitResult planned, Predicate<ItemStack> item) {
		BlockHitResult seen = seen();
		if (seen == null || !seen.getBlockPos().equals(planned.getBlockPos()) || seen.getDirection() != planned.getDirection()) {
			aim(planned.getLocation());
			return false;
		}
		if (!ready(item)) return false;
		BlockHitResult click = new BlockHitResult(seen.getLocation(), seen.getDirection(), seen.getBlockPos(), false);
		InteractionHand hand = item.test(mc.player.getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		if (mc.gameMode.useItemOn(mc.player, hand, click).consumesAction()) mc.player.swing(hand);
		return true;
	}

	// ---------------------------------------------------------------- knockback hit

	private int bestKnockbackSlot() {
		int best = mc.player.getInventory().getSelectedSlot();
		int bestLevel = InventoryUtil.getEnchantmentLevel(mc.player.getInventory().getItem(best), Enchantments.KNOCKBACK);
		for (int i = 0; i < 9; i++) {
			int level = InventoryUtil.getEnchantmentLevel(mc.player.getInventory().getItem(i), Enchantments.KNOCKBACK);
			if (level > bestLevel) {
				bestLevel = level;
				best = i;
			}
		}
		return best;
	}

	private void hit() {
		if (++hitTicks > 60) {
			// Couldn't get the hit in: get on with the trap
			hitDone = true;
			return;
		}
		AABB box = target.getBoundingBox();
		Vec3 eyes = mc.player.getEyePosition();
		aim(BlockUtil.closestPoint(box.deflate(0.1), eyes));
		Vec3 end = eyes.add(FemboyWare.getRotations().getServerRotation().toVector().scale(3.0));
		var point = box.clip(eyes, end);
		boolean onTarget = box.contains(eyes) || point.isPresent()
			// Not through a wall: the server checks that
			&& mc.level.clip(new ClipContext(eyes, point.get(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
		int slot = bestKnockbackSlot();
		boolean legit = mode.is(Mode.LEGIT);
		// Scroll to the knockback weapon while running at them
		if (legit && !readySlot(slot)) return;
		// A sprint hit at full charge gives the extra knockback
		if (!onTarget || mc.player.getAttackStrengthScale(0.5f) < 0.95f || !mc.player.isSprinting() && hitTicks < 30) return;
		LivingEntity victim = target;
		Runnable attack = () -> {
			mc.gameMode.attack(mc.player, victim);
			mc.player.swing(InteractionHand.MAIN_HAND);
		};
		if (legit) attack.run();
		else InventoryUtil.withSlot(slot, SwitchMode.SILENT, attack);
		hitDone = true;
	}

	// ---------------------------------------------------------------- fill

	private void fill(BlockPos feetPos) {
		boolean done = fill.is(Fill.COBWEB) ? mc.level.getBlockState(feetPos).is(Blocks.COBWEB)
			: mc.level.getFluidState(feetPos).is(net.minecraft.tags.FluidTags.LAVA);
		Predicate<ItemStack> item = fill.is(Fill.COBWEB) ? stack -> stack.is(Items.COBWEB) : stack -> stack.is(Items.LAVA_BUCKET);
		boolean have = item.test(mc.player.getOffhandItem()) || InventoryUtil.findHotbar(item) >= 0;
		if (done || !have) {
			filled = true;
			return;
		}
		// Wait for them to land after the knockback, or the web holds them up in the air with their head where the roof goes
		if ((!target.onGround() || target.getY() - feetPos.getY() > 0.05) && ++fillWait < 40) {
			if (mode.is(Mode.LEGIT)) aim(new Vec3(feetPos.getX() + 0.5, feetPos.getY(), feetPos.getZ() + 0.5));
			return;
		}
		BlockPos ground = feetPos.below();
		if (mc.level.getBlockState(ground).canBeReplaced()) {
			// Nothing under them to click on
			filled = true;
			return;
		}
		BlockHitResult planned = new BlockHitResult(new Vec3(feetPos.getX() + 0.5, feetPos.getY(), feetPos.getZ() + 0.5), Direction.UP, ground, false);
		if (fill.is(Fill.COBWEB)) {
			aimingAt = feetPos;
			if (mode.is(Mode.PACKET)) {
				PlaceUtil.placeInside(feetPos, item, switchMode.get(), rotate.get(), strict.get());
				return;
			}
			BlockHitResult visible = visiblePlacement(feetPos);
			if (visible != null) placeAimed(visible, item);
			return;
		}
		// Lava: the bucket pours where your look meets the ground under their feet
		BlockHitResult seen = seen();
		if (seen == null || !seen.getBlockPos().equals(ground) || seen.getDirection() != Direction.UP) {
			aim(planned.getLocation());
			if (mode.is(Mode.LEGIT)) return;
		}
		Rotation server = FemboyWare.getRotations().getServerRotation();
		Rotation pour = mode.is(Mode.PACKET) ? Rotation.between(mc.player.getEyePosition(), planned.getLocation()) : server;
		InteractionHand hand = item.test(mc.player.getOffhandItem()) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		Runnable action = () -> {
			// The bucket reads the look from the player itself
			float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
			mc.player.setYRot(pour.yaw());
			mc.player.setXRot(pour.pitch());
			if (mc.gameMode.useItem(mc.player, hand).consumesAction()) mc.player.swing(hand);
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
		};
		if (mode.is(Mode.LEGIT)) {
			if (!ready(item)) return;
			InteractionHand held = item.test(mc.player.getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
			if (mc.gameMode.useItem(mc.player, held).consumesAction()) mc.player.swing(held);
		} else if (hand == InteractionHand.OFF_HAND) action.run();
		else InventoryUtil.withSlot(InventoryUtil.findHotbar(item), switchMode.get(), action);
		filled = true;
	}

	// ---------------------------------------------------------------- movement

	@Override
	public void onInput() {
		if (!working || target == null || !mode.is(Mode.LEGIT)) return;
		Input keys = mc.player.input.keyPresses;
		// Jump when a jump would show you a block you can't see from the ground
		boolean doJump = jump.get() && mc.player.onGround() && needJump && aimingAt == null;
		if (needBackOff && aimingAt == null && stage != Stage.HIT) {
			// Your own body is in a spot the trap needs: back away from them
			float frame = FreeLook.active() ? FreeLook.yaw() : mc.player.getYRot();
			float away = Rotation.between(target.position(), mc.player.position()).yaw();
			int[] k = MoveFix.keysToward(frame, away);
			mc.player.input.keyPresses = new Input(k[0] > 0, k[0] < 0, k[1] > 0, k[1] < 0, false, false, false);
			((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(k[1], k[0]).normalized());
			return;
		}
		if (stage == Stage.HIT || needApproach && aimingAt == null) {
			// Run at them sprinting, for the sprint knockback
			float frame = FreeLook.active() ? FreeLook.yaw() : mc.player.getYRot();
			float towards = Rotation.between(mc.player.position(), target.position()).yaw();
			int[] k = MoveFix.keysToward(frame, towards);
			// Close enough to place already: don't walk into them, just jump if that helps
			if (stage != Stage.HIT && mc.player.distanceTo(target) < 2.0) {
				if (doJump) mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
				return;
			}
			mc.player.input.keyPresses = new Input(k[0] > 0, k[0] < 0, k[1] > 0, k[1] < 0, doJump || keys.jump(), false, true);
			((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(k[1], k[0]).normalized());
			return;
		}
		if (doJump) mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (BlockPos pos : positions) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			RenderUtil.drawBox(event, new AABB(pos), color.get(), color.withAlpha(255), 1.5f);
		}
	}

	/** Feet ring, head ring and roof around every block column {@code entity} stands in, bottom up. */
	public static Set<BlockPos> trapPositions(LivingEntity entity, boolean includeFeet, boolean includeRoof) {
		AABB box = entity.getBoundingBox();
		int y = Mth.floor(entity.getY() + 0.2);
		Set<BlockPos> columns = new LinkedHashSet<>();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1e-4); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1e-4); z++) {
				columns.add(new BlockPos(x, y, z));
			}
		}
		Set<BlockPos> result = new LinkedHashSet<>();
		for (int level = includeFeet ? 0 : 1; level <= 1; level++) {
			for (BlockPos column : columns) {
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					BlockPos side = column.above(level).relative(direction);
					if (!columns.contains(side.below(level))) result.add(side);
				}
			}
		}
		if (includeRoof) {
			for (BlockPos column : columns) result.add(column.above(2));
		}
		return result;
	}
}
