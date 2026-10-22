package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.Timer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.Fertilizable;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Uses bone meal on growable plants around you. */
public class AutoBoneMealModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "How far to reach", 4.5, 1, 6, 0.5));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between uses", 100, 0, 1000, 10));
	private final BoolSetting cropsOnly = add(new BoolSetting("Crops Only", "Only crops, stems, saplings, berries and cocoa (not grass)", true));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before using", true));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to bone meal", SwitchMode.SILENT));

	private final Timer timer = new Timer();

	public AutoBoneMealModule() {
		super("Auto Bone Meal", "Bone meals plants around you", Category.AUTO);
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.get())) return;
		int r = (int) Math.ceil(range.get());
		BlockPos feet = mc.player.getBlockPos();
		Vec3d eyes = mc.player.getEyePos();
		for (BlockPos pos : BlockPos.iterate(feet.add(-r, -r, -r), feet.add(r, r, r))) {
			if (eyes.squaredDistanceTo(Vec3d.ofCenter(pos)) > range.get() * range.get()) continue;
			BlockState state = mc.world.getBlockState(pos);
			if (!(state.getBlock() instanceof Fertilizable fertilizable) || !fertilizable.isFertilizable(mc.world, pos, state)) continue;
			if (cropsOnly.get() && !isCrop(state.getBlock())) continue;

			BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos.toImmutable(), false);
			if (PlaceUtil.interact(hit, stack -> stack.isOf(Items.BONE_MEAL), switchMode.get(), rotate.get())) timer.reset();
			return;
		}
	}

	private static boolean isCrop(Block block) {
		return block instanceof CropBlock || block instanceof StemBlock || block instanceof SaplingBlock
			|| block instanceof SweetBerryBushBlock || block instanceof CocoaBlock;
	}
}
