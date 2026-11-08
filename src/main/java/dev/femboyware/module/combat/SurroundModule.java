package dev.femboyware.module.combat;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Boxes the player in with obsidian and puts blocks back the moment the server reports them broken.
 */
public class SurroundModule extends Module {
	public enum Shape {
		/** Four blocks around the feet. */
		FEET,
		/** Feet ring, head ring and a roof. */
		BOX
	}

	private final EnumSetting<Shape> shape = add(new EnumSetting<>("Shape", "Feet ring only, or a full box with a roof", Shape.FEET));
	private final BoolSetting floor = add(new BoolSetting("Floor", "Also place a block under your feet", true));
	private final NumberSetting blocksPerTick = add(new NumberSetting("Blocks Per Tick", "Max placements per tick", 4, 1, 12, 1));
	private final BoolSetting instantReplace = add(new BoolSetting("Instant Replace", "Replace a block the moment it's mined instead of waiting for the next tick", true));
	private final BoolSetting breakCrystals = add(new BoolSetting("Break Crystals", "Attack crystals that block a surround position", true));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before each placement", true));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to obsidian", SwitchMode.SILENT));
	private final BoolSetting center = add(new BoolSetting("Center", "Snap to the middle of the block on enable", true));
	private final BoolSetting disableOnMove = add(new BoolSetting("Disable On Y Change", "Turn off when you jump or fall", true));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight surround positions", true));
	private final ColorSetting safeColor = add(new ColorSetting("Safe", "Colour of filled positions", 0x3055FF99)).visibleWhen(render::get);
	private final ColorSetting unsafeColor = add(new ColorSetting("Unsafe", "Colour of missing positions", 0x60FF5577)).visibleWhen(render::get);

	private double startY;
	private int placedThisTick;
	private Set<BlockPos> positions = Set.of();
	/** Positions we've just placed at, so we don't spam them before the server confirms. */
	private final Map<BlockPos, Long> pending = new HashMap<>();

	public SurroundModule() {
		super("Surround", "Places obsidian around you and replaces it instantly when mined", Category.PROTECT);
	}

	@Override
	public String getInfo() {
		if (nullCheck()) return null;
		long missing = positions.stream().filter(pos -> mc.level.getBlockState(pos).canBeReplaced()).count();
		return missing == 0 ? "Safe" : String.valueOf(missing);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) return;
		startY = mc.player.getY();
		pending.clear();
		if (center.get()) {
			Vec3 middle = Vec3.atBottomCenterOf(mc.player.blockPosition());
			mc.player.setPos(middle.x, mc.player.getY(), middle.z);
			mc.player.setDeltaMovement(0, mc.player.getDeltaMovement().y, 0);
		}
	}

	@Override
	public void onTick() {
		if (disableOnMove.get() && Math.abs(mc.player.getY() - startY) > 0.5) {
			setEnabled(false);
			return;
		}
		placedThisTick = 0;
		positions = computePositions();
		long now = System.currentTimeMillis();
		pending.values().removeIf(time -> now - time > 300);
		for (BlockPos pos : positions) {
			if (placedThisTick >= blocksPerTick.getInt()) break;
			tryPlace(pos);
		}
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState state) {
		if (!instantReplace.get() || !state.canBeReplaced() || !positions.contains(pos)) return;
		pending.remove(pos);
		// Doesn't count against the per-tick budget: being mined is exactly when speed matters
		tryPlace(pos);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (BlockPos pos : positions) {
			boolean safe = !mc.level.getBlockState(pos).canBeReplaced();
			ColorSetting color = safe ? safeColor : unsafeColor;
			RenderUtil.drawBox(event, new AABB(pos), color.get(), color.withAlpha(Math.min(255, color.getAlpha() * 3)), 1.5f);
		}
	}

	private Set<BlockPos> computePositions() {
		Set<BlockPos> feet = occupiedBlocks(mc.player.getY());
		Set<BlockPos> result = new LinkedHashSet<>();

		if (floor.get()) {
			for (BlockPos pos : feet) result.add(pos.below());
		}
		addRing(result, feet);
		if (shape.is(Shape.BOX)) {
			Set<BlockPos> head = new LinkedHashSet<>();
			for (BlockPos pos : feet) head.add(pos.above());
			addRing(result, head);
			for (BlockPos pos : head) result.add(pos.above());
		}
		return result;
	}

	/** Every block column the player's hitbox overlaps at height {@code y}. */
	private Set<BlockPos> occupiedBlocks(double y) {
		AABB box = mc.player.getBoundingBox();
		int blockY = Mth.floor(y + 0.2);
		Set<BlockPos> set = new LinkedHashSet<>();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1e-4); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1e-4); z++) {
				set.add(new BlockPos(x, blockY, z));
			}
		}
		return set;
	}

	private static void addRing(Set<BlockPos> result, Set<BlockPos> inner) {
		for (BlockPos pos : inner) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos side = pos.relative(direction);
				if (!inner.contains(side)) result.add(side);
			}
		}
	}

	private void tryPlace(BlockPos pos) {
		if (!mc.level.getBlockState(pos).canBeReplaced() || pending.containsKey(pos)) return;
		if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 5.5 * 5.5) return;

		if (breakCrystals.get()) clearCrystals(pos);
		// The server handles our attack before the place packet, so attacked crystals don't block it
		if (BlockUtil.hasBlockingEntity(pos, breakCrystals.get())) return;

		BlockHitResult hit = BlockUtil.getPlacement(pos, strict.get());
		if (hit == null) {
			// Nothing to click on: build a support block underneath first
			BlockPos below = pos.below();
			if (mc.level.getBlockState(below).canBeReplaced() && !BlockUtil.hasBlockingEntity(below, false)) {
				BlockHitResult support = BlockUtil.getPlacement(below, strict.get());
				if (support != null) place(below, support);
			}
			return;
		}
		place(pos, hit);
	}

	private void place(BlockPos pos, BlockHitResult hit) {
		int slot = InventoryUtil.findHotbar(SurroundModule::isSurroundBlock);
		InteractionHand hand = isSurroundBlock(mc.player.getOffhandItem()) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		Runnable action = () -> {
			if (rotate.get()) {
				Rotation rotation = Rotation.between(mc.player.getEyePosition(), hit.getLocation());
				mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					rotation.yaw(), rotation.pitch(), mc.player.onGround(), mc.player.horizontalCollision));
			}
			mc.gameMode.useItemOn(mc.player, hand, hit);
			mc.player.swing(hand);
		};

		boolean placed = hand == InteractionHand.OFF_HAND ? run(action) : InventoryUtil.withSlot(slot, switchMode.get(), action);
		if (placed) {
			pending.put(pos, System.currentTimeMillis());
			placedThisTick++;
		}
	}

	private static boolean run(Runnable action) {
		action.run();
		return true;
	}

	private void clearCrystals(BlockPos pos) {
		for (Entity entity : mc.level.getEntities(null, new AABB(pos))) {
			if (entity instanceof EndCrystal && entity.isAlive()) {
				//? if >=26.1 {
				/*mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundAttackPacket(entity.getId()));
				*///?} else {
				mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, mc.player.isShiftKeyDown()));
				//?}
				mc.player.swing(InteractionHand.MAIN_HAND);
			}
		}
	}

	private static boolean isSurroundBlock(ItemStack stack) {
		return stack.is(Items.OBSIDIAN) || stack.is(Items.CRYING_OBSIDIAN) || stack.is(Items.ENDER_CHEST);
	}
}
