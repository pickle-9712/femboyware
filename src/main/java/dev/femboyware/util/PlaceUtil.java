package dev.femboyware.util;

import dev.femboyware.util.InventoryUtil.SwitchMode;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Shared block placement used by the trap, filler and utility modules. */
public final class PlaceUtil {
	private static final Minecraft mc = Minecraft.getInstance();
	public static final double RANGE = 5.5;
	public static final Predicate<ItemStack> OBSIDIAN = stack -> stack.is(Items.OBSIDIAN) || stack.is(Items.CRYING_OBSIDIAN);

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
		if (!mc.level.getBlockState(pos).canBeReplaced()) return false;
		if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > RANGE * RANGE) return false;
		return !checkEntities || !BlockUtil.hasBlockingEntity(pos, false);
	}

	/** Right clicks {@code hit} while holding a stack matching {@code item}. */
	public static boolean interact(BlockHitResult hit, Predicate<ItemStack> item, SwitchMode switchMode, boolean rotate) {
		InteractionHand hand = item.test(mc.player.getOffhandItem()) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		Runnable action = () -> {
			if (rotate) lookAt(hit.getLocation());
			mc.gameMode.useItemOn(mc.player, hand, hit);
			mc.player.swing(hand);
		};
		if (hand == InteractionHand.OFF_HAND) {
			action.run();
			return true;
		}
		int slot = InventoryUtil.findHotbar(item);
		if (slot < 0) return false;
		return InventoryUtil.withSlot(slot, switchMode, action);
	}

	/** Uses the item matching {@code item} without targeting a block, e.g. throwing a pearl. */
	public static boolean useItem(Predicate<ItemStack> item, SwitchMode switchMode) {
		if (item.test(mc.player.getOffhandItem())) {
			mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
			mc.player.swing(InteractionHand.OFF_HAND);
			return true;
		}
		int slot = InventoryUtil.findHotbar(item);
		if (slot < 0) return false;
		return InventoryUtil.withSlot(slot, switchMode, () -> {
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			mc.player.swing(InteractionHand.MAIN_HAND);
		});
	}

	/** Sends a one-off rotation packet facing {@code point}. */
	public static void lookAt(Vec3 point) {
		Rotation rotation = Rotation.between(mc.player.getEyePosition(), point);
		mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
			rotation.yaw(), rotation.pitch(), mc.player.onGround(), mc.player.horizontalCollision));
	}
}
