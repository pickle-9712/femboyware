package dev.femboyware.util;

import dev.femboyware.util.InventoryUtil.SwitchMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/** Shared block placement used by the trap, filler and utility modules. */
public final class PlaceUtil {
	private static final MinecraftClient mc = MinecraftClient.getInstance();
	public static final double RANGE = 5.5;
	public static final Predicate<ItemStack> OBSIDIAN = stack -> stack.isOf(Items.OBSIDIAN) || stack.isOf(Items.CRYING_OBSIDIAN);

	private PlaceUtil() {
	}

	/**
	 * Places a block matching {@code item} at {@code pos}, from the offhand if possible.
	 *
	 * @return whether a place packet was sent
	 */
	public static boolean place(BlockPos pos, Predicate<ItemStack> item, SwitchMode switchMode, boolean rotate, boolean strict) {
		return place(pos, item, switchMode, rotate, strict, true);
	}

	/** Places a block without a collision box (webs, bushes), which may go inside entities. */
	public static boolean placeInside(BlockPos pos, Predicate<ItemStack> item, SwitchMode switchMode, boolean rotate, boolean strict) {
		return place(pos, item, switchMode, rotate, strict, false);
	}

	private static boolean place(BlockPos pos, Predicate<ItemStack> item, SwitchMode switchMode, boolean rotate, boolean strict, boolean checkEntities) {
		if (!canPlace(pos, checkEntities)) return false;
		BlockHitResult hit = BlockUtil.getPlacement(pos, strict);
		if (hit == null) return false;
		return interact(hit, item, switchMode, rotate);
	}

	/** Whether {@code pos} is empty, in range and, if {@code checkEntities}, not blocked by an entity. */
	public static boolean canPlace(BlockPos pos, boolean checkEntities) {
		if (!mc.world.getBlockState(pos).isReplaceable()) return false;
		if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > RANGE * RANGE) return false;
		return !checkEntities || !BlockUtil.hasBlockingEntity(pos, false);
	}

	/** Right clicks {@code hit} while holding a stack matching {@code item}. */
	public static boolean interact(BlockHitResult hit, Predicate<ItemStack> item, SwitchMode switchMode, boolean rotate) {
		Hand hand = item.test(mc.player.getOffHandStack()) ? Hand.OFF_HAND : Hand.MAIN_HAND;
		Runnable action = () -> {
			if (rotate) lookAt(hit.getPos());
			mc.interactionManager.interactBlock(mc.player, hand, hit);
			mc.player.swingHand(hand);
		};
		if (hand == Hand.OFF_HAND) {
			action.run();
			return true;
		}
		int slot = InventoryUtil.findHotbar(item);
		if (slot < 0) return false;
		return InventoryUtil.withSlot(slot, switchMode, action);
	}

	/** Uses the item matching {@code item} without targeting a block, e.g. throwing a pearl. */
	public static boolean useItem(Predicate<ItemStack> item, SwitchMode switchMode) {
		if (item.test(mc.player.getOffHandStack())) {
			mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
			mc.player.swingHand(Hand.OFF_HAND);
			return true;
		}
		int slot = InventoryUtil.findHotbar(item);
		if (slot < 0) return false;
		return InventoryUtil.withSlot(slot, switchMode, () -> {
			mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
			mc.player.swingHand(Hand.MAIN_HAND);
		});
	}

	/** Sends a one-off rotation packet facing {@code point}. */
	public static void lookAt(Vec3d point) {
		Rotation rotation = Rotation.between(mc.player.getEyePos(), point);
		mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
			rotation.yaw(), rotation.pitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
	}
}
