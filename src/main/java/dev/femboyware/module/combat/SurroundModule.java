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
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
		long missing = positions.stream().filter(pos -> mc.world.getBlockState(pos).isReplaceable()).count();
		return missing == 0 ? "Safe" : String.valueOf(missing);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) return;
		startY = mc.player.getY();
		pending.clear();
		if (center.get()) {
			Vec3d middle = Vec3d.ofBottomCenter(mc.player.getBlockPos());
			mc.player.setPosition(middle.x, mc.player.getY(), middle.z);
			mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
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
		if (!instantReplace.get() || !state.isReplaceable() || !positions.contains(pos)) return;
		pending.remove(pos);
		// Doesn't count against the per-tick budget: being mined is exactly when speed matters
		tryPlace(pos);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (BlockPos pos : positions) {
			boolean safe = !mc.world.getBlockState(pos).isReplaceable();
			ColorSetting color = safe ? safeColor : unsafeColor;
			RenderUtil.drawBox(event, new Box(pos), color.get(), color.withAlpha(Math.min(255, color.getAlpha() * 3)), 1.5f);
		}
	}

	private Set<BlockPos> computePositions() {
		Set<BlockPos> feet = occupiedBlocks(mc.player.getY());
		Set<BlockPos> result = new LinkedHashSet<>();

		if (floor.get()) {
			for (BlockPos pos : feet) result.add(pos.down());
		}
		addRing(result, feet);
		if (shape.is(Shape.BOX)) {
			Set<BlockPos> head = new LinkedHashSet<>();
			for (BlockPos pos : feet) head.add(pos.up());
			addRing(result, head);
			for (BlockPos pos : head) result.add(pos.up());
		}
		return result;
	}

	/** Every block column the player's hitbox overlaps at height {@code y}. */
	private Set<BlockPos> occupiedBlocks(double y) {
		Box box = mc.player.getBoundingBox();
		int blockY = MathHelper.floor(y + 0.2);
		Set<BlockPos> set = new LinkedHashSet<>();
		for (int x = MathHelper.floor(box.minX); x <= MathHelper.floor(box.maxX - 1e-4); x++) {
			for (int z = MathHelper.floor(box.minZ); z <= MathHelper.floor(box.maxZ - 1e-4); z++) {
				set.add(new BlockPos(x, blockY, z));
			}
		}
		return set;
	}

	private static void addRing(Set<BlockPos> result, Set<BlockPos> inner) {
		for (BlockPos pos : inner) {
			for (Direction direction : Direction.Type.HORIZONTAL) {
				BlockPos side = pos.offset(direction);
				if (!inner.contains(side)) result.add(side);
			}
		}
	}

	private void tryPlace(BlockPos pos) {
		if (!mc.world.getBlockState(pos).isReplaceable() || pending.containsKey(pos)) return;
		if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > 5.5 * 5.5) return;

		if (breakCrystals.get()) clearCrystals(pos);
		// The server handles our attack before the place packet, so attacked crystals don't block it
		if (BlockUtil.hasBlockingEntity(pos, breakCrystals.get())) return;

		BlockHitResult hit = BlockUtil.getPlacement(pos, strict.get());
		if (hit == null) {
			// Nothing to click on: build a support block underneath first
			BlockPos below = pos.down();
			if (mc.world.getBlockState(below).isReplaceable() && !BlockUtil.hasBlockingEntity(below, false)) {
				BlockHitResult support = BlockUtil.getPlacement(below, strict.get());
				if (support != null) place(below, support);
			}
			return;
		}
		place(pos, hit);
	}

	private void place(BlockPos pos, BlockHitResult hit) {
		int slot = InventoryUtil.findHotbar(SurroundModule::isSurroundBlock);
		Hand hand = isSurroundBlock(mc.player.getOffHandStack()) ? Hand.OFF_HAND : Hand.MAIN_HAND;
		Runnable action = () -> {
			if (rotate.get()) {
				Rotation rotation = Rotation.between(mc.player.getEyePos(), hit.getPos());
				mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
					rotation.yaw(), rotation.pitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
			}
			mc.interactionManager.interactBlock(mc.player, hand, hit);
			mc.player.swingHand(hand);
		};

		boolean placed = hand == Hand.OFF_HAND ? run(action) : InventoryUtil.withSlot(slot, switchMode.get(), action);
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
		for (Entity entity : mc.world.getOtherEntities(null, new Box(pos))) {
			if (entity instanceof EndCrystalEntity && entity.isAlive()) {
				mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking()));
				mc.player.swingHand(Hand.MAIN_HAND);
			}
		}
	}

	private static boolean isSurroundBlock(ItemStack stack) {
		return stack.isOf(Items.OBSIDIAN) || stack.isOf(Items.CRYING_OBSIDIAN) || stack.isOf(Items.ENDER_CHEST);
	}
}
