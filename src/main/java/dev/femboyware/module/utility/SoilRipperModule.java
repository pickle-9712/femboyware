package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.Timer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/** Tills the dirt and grass around you into farmland, and can plant seeds on it straight away. */
public class SoilRipperModule extends Module {
	private static final Predicate<ItemStack> SEEDS = stack -> stack.isOf(Items.WHEAT_SEEDS) || stack.isOf(Items.CARROT)
		|| stack.isOf(Items.POTATO) || stack.isOf(Items.BEETROOT_SEEDS) || stack.isOf(Items.MELON_SEEDS) || stack.isOf(Items.PUMPKIN_SEEDS);

	private final NumberSetting range = add(new NumberSetting("Range", "How far to reach", 4.5, 1, 6, 0.5));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between actions", 100, 0, 1000, 10));
	private final BoolSetting plant = add(new BoolSetting("Plant", "Plant seeds on empty farmland", true));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before each action", true));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to the hoe and seeds", SwitchMode.SILENT));

	private final Timer timer = new Timer();

	public SoilRipperModule() {
		super("Soil Ripper", "Tills and plants the ground around you", Category.AUTO);
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.get())) return;
		int r = (int) Math.ceil(range.get());
		BlockPos feet = mc.player.getBlockPos();
		Vec3d eyes = mc.player.getEyePos();
		for (BlockPos pos : BlockPos.iterate(feet.add(-r, -2, -r), feet.add(r, 1, r))) {
			if (eyes.squaredDistanceTo(Vec3d.ofCenter(pos)) > range.get() * range.get()) continue;
			if (!mc.world.getBlockState(pos.up()).isAir()) continue;
			BlockState state = mc.world.getBlockState(pos);
			Predicate<ItemStack> tool;
			if (isTillable(state)) tool = stack -> stack.isIn(ItemTags.HOES);
			else if (plant.get() && state.isOf(Blocks.FARMLAND)) tool = SEEDS;
			else continue;

			BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos.toImmutable(), false);
			if (PlaceUtil.interact(hit, tool, switchMode.get(), rotate.get())) {
				timer.reset();
				return;
			}
		}
	}

	private static boolean isTillable(BlockState state) {
		return state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT) || state.isOf(Blocks.DIRT_PATH) || state.isOf(Blocks.COARSE_DIRT);
	}
}
