package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.Timer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
		BlockPos feet = mc.player.blockPosition();
		Vec3 eyes = mc.player.getEyePosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
			if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > range.get() * range.get()) continue;
			BlockState state = mc.level.getBlockState(pos);
			//? if >=26.3 {
			/*if (!(state.getBlock() instanceof BonemealableBlock fertilizable) || !fertilizable.isValidBonemealTarget(mc.level, pos, state, net.minecraft.world.level.block.BonemealSource.INTERACTION)) continue;
			*///?} else {
			if (!(state.getBlock() instanceof BonemealableBlock fertilizable) || !fertilizable.isValidBonemealTarget(mc.level, pos, state)) continue;
			//?}
			if (cropsOnly.get() && !isCrop(state.getBlock())) continue;

			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos.immutable(), false);
			if (PlaceUtil.interact(hit, stack -> stack.is(Items.BONE_MEAL), switchMode.get(), rotate.get())) timer.reset();
			return;
		}
	}

	private static boolean isCrop(Block block) {
		return block instanceof CropBlock || block instanceof StemBlock || block instanceof SaplingBlock
			|| block instanceof SweetBerryBushBlock || block instanceof CocoaBlock;
	}
}
