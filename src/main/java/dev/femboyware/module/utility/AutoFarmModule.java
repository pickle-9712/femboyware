package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.Timer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Harvests ripe crops round you as you walk through your farm (wheat, carrots, potatoes, beetroot,
 * nether wart, cocoa, melons and pumpkins), and plants the seed back where one came up.
 */
public class AutoFarmModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "How far to reach", 4.5, 1, 5.5, 0.5));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between actions", 80, 0, 1000, 10));
	private final BoolSetting replant = add(new BoolSetting("Replant", "Plant the seed back", true));
	private final BoolSetting melons = add(new BoolSetting("Melons & Pumpkins", "Break melons and pumpkins too", true));

	private final Timer timer = new Timer();

	public AutoFarmModule() {
		super("Auto Farm", "Harvest and replant ripe crops around you", Category.WORLD);
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null || mc.gameMode == null || !timer.passed(delay.get())) return;
		Vec3 eyes = mc.player.getEyePosition();
		int r = (int) Math.ceil(range.get());
		BlockPos feet = mc.player.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 2, r))) {
			if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > range.get() * range.get()) continue;
			BlockState state = mc.level.getBlockState(pos);
			if (ripe(state)) {
				PlaceUtil.lookAt(Vec3.atCenterOf(pos));
				mc.gameMode.startDestroyBlock(pos.immutable(), Direction.UP);
				mc.player.swing(InteractionHand.MAIN_HAND);
				timer.reset();
				return;
			}
			if (replant.get() && state.isAir()) {
				BlockState below = mc.level.getBlockState(pos.below());
				Predicate<ItemStack> seed = seedFor(below.getBlock());
				if (seed == null || InventoryUtil.findHotbar(seed) < 0 && !seed.test(mc.player.getOffhandItem())) continue;
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, 0.5, 0), Direction.UP, pos.below().immutable(), false);
				if (PlaceUtil.interact(hit, seed, InventoryUtil.SwitchMode.SILENT, true)) {
					timer.reset();
					return;
				}
			}
		}
	}

	private boolean ripe(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock crop) return crop.isMaxAge(state);
		if (block instanceof NetherWartBlock) return state.getValue(NetherWartBlock.AGE) >= 3;
		if (block instanceof CocoaBlock) return state.getValue(CocoaBlock.AGE) >= 2;
		return melons.get() && (block == Blocks.MELON || block == Blocks.PUMPKIN);
	}

	/** What grows on this block, or null if nothing we plant does. */
	private static Predicate<ItemStack> seedFor(Block soil) {
		if (soil == Blocks.FARMLAND) return stack -> stack.is(Items.WHEAT_SEEDS) || stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT_SEEDS);
		if (soil == Blocks.SOUL_SAND) return stack -> stack.is(Items.NETHER_WART);
		return null;
	}
}
