package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.lb.RotationSettings;
import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.FreeLook;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.MoveFix;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Boxes you in the way Trap's Legit mode boxes a target: every block aimed at with a flick of the
 * view (turned every frame in whole mouse steps, or your real head with Real Look while the camera
 * stays free), a real switch to the blocks a tick before the click, one block a tick, back to your
 * weapon after, Auto Offhand, and turns taken between Legit Aura's hits. It steps you onto the
 * middle of your block first (sneaking, with real keys) so the box is four blocks, not eight, and
 * jumps when a block it needs (the roof's support) can only be seen from higher up.
 *
 * <p>{@link LegitSurroundModule} is the ring at your feet; {@link LegitSelfTrapModule} adds the ring
 * at your head and a roof.
 */
public abstract class LegitBoxModule extends Module {
	private static final int ROTATION_PRIORITY = 72;
	private static final int HAND_BACK_TICKS = 3, MAX_TURN_TICKS = 14, FIGHT_TICKS = 8;

	private final BoolSetting anyBlock = add(new BoolSetting("Any Block", "Use any solid block when out of obsidian (the toughest one you have)", true));
	private final BoolSetting center = add(new BoolSetting("Center", "Step onto the middle of your block first (sneaking, real keys) so it's four blocks round you, not up to eight", true));
	private final BoolSetting leaveOff = add(new BoolSetting("Off When You Leave", "Turn off once you walk or jump off the block it boxed", true));
	private final BoolSetting jump = add(new BoolSetting("Jump", "Jump when a block it needs (like the roof's support) can only be seen from higher up", true));
	private final BoolSetting realLook = add(new BoolSetting("Real Look", "Really turn your head to each block (no silent rotation), with free look so you can still look anywhere", true));
	private final BoolSetting thirdPerson = add(new BoolSetting("Third Person", "Switch to third person while Real Look turns your head", false)).visibleWhen(realLook::get);
	private final BoolSetting autoOffhand = add(new BoolSetting("Auto Offhand", "Put your blocks in your offhand the way you would (select them, press swap hands, back to your sword), so you keep the sword in hand while it places. Never swaps out a totem", false));
	private final NumberSetting flickSpeed = add(new NumberSetting("Flick Speed", "How fast your view flicks onto each block: turned every frame in whole mouse steps at your sensitivity, easing in as it arrives", 45, 4, 80, 1));
	private final RotationSettings rotations = new RotationSettings(s -> add(s), () -> !realLook.get(), 45f);
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight the blocks still to place", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0x4055AAFF)).visibleWhen(render::get);

	private Set<BlockPos> positions = Set.of();
	/** The block you're boxed on: leaving it turns the module off. */
	private BlockPos home;
	private boolean working, needJump, centering;
	private BlockPos aimingAt, committed;
	private Vec3 aimPoint;
	private long lastFrame;
	private double yawCarry, pitchCarry;
	private int originalSlot = -1, lastSlot = -1, sinceSwitch, offhandStep;
	private int turnTicks, fightTicks;
	private boolean restoreNext, turnEndedThisTick, clickBlocked;

	protected LegitBoxModule(String name, String description) {
		super(name, description, Category.LEGIT);
	}

	/** The blocks to place round a body standing in {@code columns} at feet level {@code y}, before supports. */
	protected abstract Set<BlockPos> shape(Set<BlockPos> columns, int y);

	@Override
	protected void onEnable() {
		home = null;
		committed = null;
		turnTicks = fightTicks = 0;
	}

	@Override
	protected void onDisable() {
		positions = Set.of();
		working = centering = false;
		aimPoint = null;
		restoreSlot();
	}

	@Override
	public String getInfo() {
		if (positions.isEmpty()) return null;
		int left = 0;
		for (BlockPos pos : positions) if (mc.level != null && mc.level.getBlockState(pos).canBeReplaced()) left++;
		return left == 0 ? "Done" : String.valueOf(left);
	}

	/** Taking a turn from Legit Aura: it doesn't aim, swing or switch meanwhile, so one thing a tick. */
	public boolean isBusy() {
		return isEnabled() && (offhandStep > 0 || working && (!fighting() || turnTicks > 0 || turnEndedThisTick));
	}

	private boolean fighting() {
		return FemboyWare.getModules().get(LegitAuraModule.class).isEnabled() && FemboyWare.getModules().get(LegitAuraModule.class).getTarget() != null;
	}

	private int ticksToCharged() {
		return (int) Math.ceil((1 - mc.player.getAttackStrengthScale(0.5f)) * mc.player.getCurrentItemAttackStrengthDelay());
	}

	private void endTurn() {
		if (turnTicks == 0) return;
		turnTicks = 0;
		fightTicks = FIGHT_TICKS;
		restoreNext = true;
		turnEndedThisTick = true;
	}

	// ---------------------------------------------------------------- the box

	private Set<BlockPos> columns(AABB box, int y) {
		Set<BlockPos> columns = new LinkedHashSet<>();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1e-4); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1e-4); z++) columns.add(new BlockPos(x, y, z));
		}
		return columns;
	}

	/** Each block with nothing to click on gets one first: the block under it, or for a roof, one beside it. */
	private Set<BlockPos> withSupports(Set<BlockPos> shape, Set<BlockPos> columns, int y) {
		Set<BlockPos> result = new LinkedHashSet<>();
		Direction facing = Direction.fromYRot(FreeLook.yaw());
		for (BlockPos pos : shape) {
			if (mc.level.getBlockState(pos).canBeReplaced() && BlockUtil.getPlacement(pos, false) == null) {
				if (pos.getY() == y + 2 && columns.contains(pos.below(2))) {
					// The roof: hangs off a block beside it, on top of the head ring (a jump shows that top)
					BlockPos side = pos.relative(facing);
					if (columns.contains(side.below(2))) side = pos.relative(facing.getClockWise());
					result.add(side);
				} else if (!shape.contains(pos.below())) {
					result.add(pos.below());
				}
			}
			result.add(pos);
		}
		return result;
	}

	@Override
	public void onTick() {
		working = false;
		turnEndedThisTick = false;
		aimingAt = null;
		needJump = false;
		aimPoint = null;
		centering = false;
		if (nullCheck()) return;
		if (restoreNext) {
			restoreNext = false;
			restoreSlot();
		}
		int selected = mc.player.getInventory().getSelectedSlot();
		if (selected != lastSlot) {
			lastSlot = selected;
			sinceSwitch = 0;
		} else {
			sinceSwitch++;
		}
		if (autoOffhandTick()) return;

		BlockPos standing = BlockPos.containing(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
		if (mc.player.onGround()) {
			if (home == null) home = standing;
			else if (leaveOff.get() && (standing.getX() != home.getX() || standing.getZ() != home.getZ() || Math.abs(standing.getY() - home.getY()) > 0)) {
				setEnabled(false);
				return;
			}
		}
		if (home == null) return;

		AABB box = mc.player.getBoundingBox();
		Set<BlockPos> columns = columns(box, home.getY());
		boolean offCentre = Math.abs(mc.player.getX() - (home.getX() + 0.5)) > 0.18 || Math.abs(mc.player.getZ() - (home.getZ() + 0.5)) > 0.18;
		if (center.get() && columns.size() > 1 && mc.player.onGround()) {
			// Over more than one block: step to the middle first, the box is far smaller from there
			centering = true;
			positions = withSupports(shape(columns, home.getY()), columns, home.getY());
			if (offCentre) return;
		}
		if (center.get()) columns = Set.of(home);
		positions = withSupports(shape(columns, home.getY()), columns, home.getY());
		List<BlockPos> missing = new ArrayList<>();
		for (BlockPos pos : positions) if (mc.level.getBlockState(pos).canBeReplaced() && !BlockUtil.hasBlockingEntity(pos, false)) missing.add(pos);
		if (missing.isEmpty() || bestBlock() == null) {
			turnTicks = 0;
			restoreSlot();
			return;
		}
		working = true;

		if (fighting()) {
			// Between Legit Aura's hits, as Trap does: just after a swing, handing back in time for the next
			if (turnTicks == 0) {
				float window = offhandBlocks() ? 0.6f : 0.35f;
				float charge = mc.player.getAttackStrengthScale(0.5f);
				if (fightTicks > 0) {
					fightTicks--;
					if (charge > 0.3f) return;
					fightTicks = 0;
				}
				if (charge > window) return;
				turnTicks = 1;
				clickBlocked = true;
			} else {
				clickBlocked = false;
				if (++turnTicks > MAX_TURN_TICKS || turnTicks > 2 && ticksToCharged() <= HAND_BACK_TICKS) {
					endTurn();
					return;
				}
			}
		} else {
			clickBlocked = false;
		}
		if (realLook.get() && !fighting()) FreeLook.hold(thirdPerson.get());
		if (mc.player.isUsingItem() || Mc.screen() != null) return;
		boolean placed = placeAll(missing);
		if (placed && fighting() && missing.size() == 1) endTurn();
	}

	// ---------------------------------------------------------------- blocks and hands

	private boolean isBoxBlock(ItemStack stack) {
		if (stack.is(Items.OBSIDIAN) || stack.is(Items.CRYING_OBSIDIAN)) return true;
		if (!anyBlock.get() || !(stack.getItem() instanceof BlockItem item)) return false;
		Block block = item.getBlock();
		if (block instanceof FallingBlock || block instanceof BaseEntityBlock) return false;
		return block.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO);
	}

	private static float resistance(ItemStack stack) {
		return ((BlockItem) stack.getItem()).getBlock().getExplosionResistance();
	}

	private ItemStack bestBlock() {
		ItemStack best = null;
		if (isBoxBlock(mc.player.getOffhandItem())) best = mc.player.getOffhandItem();
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (isBoxBlock(stack) && (best == null || resistance(stack) > resistance(best))) best = stack;
		}
		return best;
	}

	private boolean offhandBlocks() {
		ItemStack best = bestBlock();
		return best != null && best == mc.player.getOffhandItem();
	}

	private void restoreSlot() {
		if (originalSlot >= 0 && mc.player != null && mc.player.getInventory().getSelectedSlot() != originalSlot) InventoryUtil.select(originalSlot);
		originalSlot = -1;
	}

	/** A real switch, and a tick with it in your hand before using it; true once it's ready. */
	private boolean ready(Predicate<ItemStack> item) {
		if (item.test(mc.player.getOffhandItem()) && !item.test(mc.player.getMainHandItem())) {
			// A right click tries the main hand first: another block there would go down instead
			if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) return sinceSwitch >= 1;
			for (int i = 0; i < 9; i++) {
				if (!(mc.player.getInventory().getItem(i).getItem() instanceof BlockItem)) {
					InventoryUtil.select(i);
					originalSlot = -1;
					return false;
				}
			}
		}
		int slot = InventoryUtil.findHotbar(item);
		if (slot < 0) return false;
		if (mc.player.getInventory().getSelectedSlot() != slot) {
			if (originalSlot < 0) originalSlot = mc.player.getInventory().getSelectedSlot();
			InventoryUtil.select(slot);
			lastSlot = slot;
			sinceSwitch = 0;
			return false;
		}
		return sinceSwitch >= 1;
	}

	/** Auto Offhand, a step a tick: select the blocks, press swap hands, back to the sword. True while busy. */
	private boolean autoOffhandTick() {
		if (offhandStep == 2) {
			offhandStep = 0;
			restoreSlot();
			return true;
		}
		if (!autoOffhand.get() || Mc.screen() != null || mc.player.isUsingItem()) {
			offhandStep = 0;
			return false;
		}
		ItemStack offhand = mc.player.getOffhandItem();
		if (isBoxBlock(offhand) || offhand.is(Items.TOTEM_OF_UNDYING)) {
			offhandStep = 0;
			return false;
		}
		int slot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (isBoxBlock(stack) && (slot < 0 || resistance(stack) > resistance(mc.player.getInventory().getItem(slot)))) slot = i;
		}
		if (slot < 0) {
			offhandStep = 0;
			return false;
		}
		if (mc.player.getInventory().getSelectedSlot() != slot) {
			if (originalSlot < 0) originalSlot = mc.player.getInventory().getSelectedSlot();
			InventoryUtil.select(slot);
			lastSlot = slot;
			sinceSwitch = 0;
			offhandStep = 1;
			return true;
		}
		if (sinceSwitch < 1) {
			offhandStep = 1;
			return true;
		}
		mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
			net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
		offhandStep = 2;
		return true;
	}

	// ---------------------------------------------------------------- placing

	private boolean placeAll(List<BlockPos> list) {
		ItemStack block = bestBlock();
		if (block == null) return false;
		Predicate<ItemStack> item = stack -> stack.is(block.getItem());
		if (committed != null) {
			if (list.contains(committed) && mc.level.getBlockState(committed).canBeReplaced()) {
				BlockHitResult planned = visiblePlacement(committed);
				if (planned != null) {
					aimingAt = committed;
					boolean placed = placeAimed(planned, item);
					if (placed) committed = null;
					return placed;
				}
			}
			committed = null;
		}
		// Lowest first (supports before what sits on them), then the nearest to where you're looking
		Vec3 look = Vec3.directionFromRotation(FreeLook.pitch(), FreeLook.yaw());
		Vec3 eyes = mc.player.getEyePosition();
		List<BlockPos> ordered = new ArrayList<>(list);
		ordered.sort((a, b) -> {
			if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
			double da = look.dot(Vec3.atCenterOf(a).subtract(eyes).normalize()), db = look.dot(Vec3.atCenterOf(b).subtract(eyes).normalize());
			return Double.compare(db, da);
		});
		Vec3 lookAt = null;
		for (BlockPos pos : ordered) {
			boolean jumpBefore = needJump;
			needJump = false;
			BlockHitResult planned = visiblePlacement(pos);
			boolean hidden = needJump;
			needJump |= jumpBefore;
			if (planned == null) {
				if (lookAt == null && hidden) lookAt = Vec3.atCenterOf(pos);
				continue;
			}
			aimingAt = pos;
			boolean placed = placeAimed(planned, item);
			committed = placed ? null : pos;
			return placed;
		}
		if (lookAt != null) aim(lookAt);
		return false;
	}

	/** A click that places at {@code pos} on a spot of a neighbouring face your eyes can see and reach, or null. */
	private BlockHitResult visiblePlacement(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		double reach = mc.player.blockInteractionRange() - 0.3;
		boolean inReach = false, anyNeighbour = false;
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (mc.level.getBlockState(neighbour).canBeReplaced()) continue;
			anyNeighbour = true;
			Direction face = direction.getOpposite();
			Vec3 middle = BlockUtil.faceCenter(neighbour, face);
			for (double[] o : new double[][]{{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}, {0.3, 0.3}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}}) {
				Vec3 point = middle.add(offset(face, o[0], o[1]));
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
		if (best == null && anyNeighbour && inReach) needJump = true;
		return best;
	}

	private static Vec3 offset(Direction face, double a, double b) {
		return switch (face.getAxis()) {
			case X -> new Vec3(0, a, b);
			case Y -> new Vec3(a, 0, b);
			case Z -> new Vec3(a, b, 0);
		};
	}

	private boolean frameAim() {
		return realLook.get() || fighting();
	}

	private void aim(Vec3 point) {
		if (frameAim()) {
			aimPoint = point;
			return;
		}
		FemboyWare.getRotations().request(Rotation.between(mc.player.getEyePosition(), point), ROTATION_PRIORITY, rotations, false);
	}

	/** Trap's flick: a share of what's left each frame, easing in, a little uneven, in whole mouse steps. */
	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (aimPoint == null || seconds <= 0 || mc.player == null || Mc.screen() != null) {
			yawCarry = pitchCarry = 0;
			return;
		}
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Rotation wanted = Rotation.between(mc.player.getEyePosition(tickDelta), aimPoint);
		double yawLeft = Mth.wrapDegrees(wanted.yaw() - mc.player.getYRot());
		double pitchLeft = wanted.pitch() - mc.player.getXRot();
		double follow = 1 - Math.exp(-flickSpeed.get() * seconds);
		double wobble = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3;
		double yawStep = yawLeft * follow * wobble + yawCarry;
		double pitchStep = pitchLeft * follow * wobble + pitchCarry;
		double sensitivity = mc.options.sensitivity().get() * 0.6 + 0.2;
		double degreesPerCount = sensitivity * sensitivity * sensitivity * 8 * 0.15;
		long yawCounts = Math.round(yawStep / degreesPerCount);
		long pitchCounts = Math.round(pitchStep / degreesPerCount);
		yawCarry = yawStep - yawCounts * degreesPerCount;
		pitchCarry = pitchStep - pitchCounts * degreesPerCount;
		if (yawCounts == 0 && pitchCounts == 0) return;
		float dYaw = (float) (yawCounts * degreesPerCount), dPitch = (float) (pitchCounts * degreesPerCount);
		float pitch = Mth.clamp(mc.player.getXRot() + dPitch, -90f, 90f);
		dPitch = pitch - mc.player.getXRot();
		mc.player.setYRot(mc.player.getYRot() + dYaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO += dYaw;
		mc.player.xRotO += dPitch;
	}

	/** The rotation in the last movement packet: what a click gets checked against. */
	private Rotation sentRotation() {
		if (frameAim()) {
			var sent = (dev.femboyware.mixin.ClientPlayerEntityAccessor) mc.player;
			return new Rotation(sent.femboyware$getLastYaw(), sent.femboyware$getLastPitch());
		}
		return FemboyWare.getRotations().getServerRotation();
	}

	private BlockHitResult seen() {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 end = eyes.add(sentRotation().toVector().scale(mc.player.blockInteractionRange()));
		BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	/** Clicks once the look the server has is on a face that puts the block there; turns there otherwise. */
	private boolean placeAimed(BlockHitResult planned, Predicate<ItemStack> item) {
		BlockHitResult seen = seen();
		BlockPos wanted = planned.getBlockPos().relative(planned.getDirection());
		if (seen == null || !seen.getBlockPos().relative(seen.getDirection()).equals(wanted)) {
			aim(planned.getLocation());
			return false;
		}
		if (clickBlocked || !ready(item)) return false;
		BlockHitResult click = new BlockHitResult(seen.getLocation(), seen.getDirection(), seen.getBlockPos(), false);
		InteractionHand hand = item.test(mc.player.getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		if (hand == InteractionHand.OFF_HAND && !mc.player.getMainHandItem().isEmpty()
			&& mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, click).consumesAction()) {
			mc.player.swing(InteractionHand.MAIN_HAND);
			return true;
		}
		if (mc.gameMode.useItemOn(mc.player, hand, click).consumesAction()) mc.player.swing(hand);
		return true;
	}

	// ---------------------------------------------------------------- moving

	@Override
	public void onInput() {
		if (nullCheck() || home == null) return;
		Input keys = mc.player.input.keyPresses;
		boolean own = keys.forward() || keys.backward() || keys.left() || keys.right();
		if (centering && !own) {
			// Sneak-step onto the middle of the block, with real keys
			Vec3 middle = new Vec3(home.getX() + 0.5, mc.player.getY(), home.getZ() + 0.5);
			if (mc.player.position().distanceTo(middle) > 0.12) {
				float frame = FreeLook.active() ? FreeLook.yaw() : mc.player.getYRot();
				int[] k = MoveFix.keysToward(frame, Rotation.between(mc.player.position(), middle).yaw());
				mc.player.input.keyPresses = new Input(k[0] > 0, k[0] < 0, k[1] > 0, k[1] < 0, false, true, false);
				((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(k[1], k[0]).normalized().scale(0.3f));
				return;
			}
		}
		if (working && jump.get() && needJump && aimingAt == null && mc.player.onGround() && (!fighting() || turnTicks > 0)) {
			mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get() || mc.level == null) return;
		for (BlockPos pos : positions) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			RenderUtil.drawBox(event, new AABB(pos), color.get(), color.withAlpha(255), 1.5f);
		}
	}

	/** The ring round {@code columns} at height {@code y}. */
	protected static Set<BlockPos> ring(Set<BlockPos> columns, int y) {
		Set<BlockPos> ring = new LinkedHashSet<>();
		for (BlockPos column : columns) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos side = new BlockPos(column.getX(), y, column.getZ()).relative(direction);
				if (!columns.contains(side.atY(columns.iterator().next().getY()))) ring.add(side);
			}
		}
		return ring;
	}
}
