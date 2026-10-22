package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/**
 * Places a block inside your own feet: fakes a jump with position packets, places the block in
 * the space the server now thinks is empty, then rubberbands back down into it.
 */
public class BurrowModule extends Module {
	/** Vanilla jump positions, so the fake jump looks legitimate. */
	private static final double[] JUMP = {0.41999998688698, 0.7531999805212, 1.00133597911214, 1.16610926093821};

	private final NumberSetting rubberband = add(new NumberSetting("Rubberband", "Height of the packet that forces the setback", 3, -5, 10, 0.1));
	private final BoolSetting enderChest = add(new BoolSetting("Ender Chest", "Use ender chests when out of obsidian", true));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look down while placing", true));

	public BurrowModule() {
		super("Burrow", "Places a block inside your feet", Category.PROTECT);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) return;
		burrow();
		setEnabled(false);
	}

	private void burrow() {
		BlockPos feet = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
		if (!mc.player.isOnGround() || !mc.world.getBlockState(feet).isReplaceable()) return;
		if (!mc.world.getBlockState(feet.up(2)).isReplaceable()) return;

		Predicate<ItemStack> block = stack -> PlaceUtil.OBSIDIAN.test(stack) || enderChest.get() && stack.isOf(Items.ENDER_CHEST);
		int slot = InventoryUtil.findHotbar(block);
		if (slot < 0) return;

		double x = mc.player.getX();
		double y = mc.player.getY();
		double z = mc.player.getZ();
		for (double offset : JUMP) {
			mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + offset, z, false, false));
		}

		BlockPos ground = feet.down();
		Vec3d hitVec = Vec3d.ofCenter(ground).add(0, 0.5, 0);
		BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, ground, false);
		InventoryUtil.withSlot(slot, SwitchMode.SILENT, () -> {
			if (rotate.get()) {
				mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(mc.player.getYaw(), 90f, false, false));
			}
			mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
			mc.player.swingHand(Hand.MAIN_HAND);
		});

		mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + rubberband.get(), z, false, false));
	}
}
