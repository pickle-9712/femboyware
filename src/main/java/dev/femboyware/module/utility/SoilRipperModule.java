package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.Timer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Tills the dirt and grass around you into farmland, and can plant seeds on it straight away. */
public class SoilRipperModule extends Module {
	private static final Predicate<ItemStack> SEEDS = stack -> stack.is(Items.WHEAT_SEEDS) || stack.is(Items.CARROT)
		|| stack.is(Items.POTATO) || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.MELON_SEEDS) || stack.is(Items.PUMPKIN_SEEDS);

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
		BlockPos feet = mc.player.blockPosition();
		Vec3 eyes = mc.player.getEyePosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 1, r))) {
			if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > range.get() * range.get()) continue;
			if (!mc.level.getBlockState(pos.above()).isAir()) continue;
			BlockState state = mc.level.getBlockState(pos);
			Predicate<ItemStack> tool;
			if (isTillable(state)) tool = stack -> stack.is(ItemTags.HOES);
			else if (plant.get() && state.is(Blocks.FARMLAND)) tool = SEEDS;
			else continue;

			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos.immutable(), false);
			if (PlaceUtil.interact(hit, tool, switchMode.get(), rotate.get())) {
				timer.reset();
				return;
			}
		}
	}

	private static boolean isTillable(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.COARSE_DIRT);
	}
}
