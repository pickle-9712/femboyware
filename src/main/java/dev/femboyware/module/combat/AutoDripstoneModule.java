package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.TargetUtil;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/**
 * Plants a stalagmite where a falling target is about to land. Landing on pointed dripstone
 * doubles fall damage.
 */
public class AutoDripstoneModule extends Module {
	private static final Predicate<ItemStack> DRIPSTONE = stack -> stack.isOf(Items.POINTED_DRIPSTONE);

	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 6, 2, 10, 0.5));
	private final NumberSetting minFall = add(new NumberSetting("Min Fall", "Only trigger once the target has fallen this far", 3, 0, 20, 0.5));
	private final NumberSetting maxHeight = add(new NumberSetting("Max Height", "How far below the target to look for ground", 12, 2, 30, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to dripstone", SwitchMode.SILENT));

	private BlockPos lastPlaced;

	public AutoDripstoneModule() {
		super("Auto Dripstone", "Places pointed dripstone where falling targets will land", Category.ATTACK);
	}

	@Override
	public void onTick() {
		PlayerEntity target = TargetUtil.closestPlayer(targetRange.get());
		if (target == null || target.isOnGround() || target.isGliding() || target.fallDistance < minFall.get()) return;

		BlockPos landing = findLanding(target);
		if (landing == null || landing.equals(lastPlaced)) return;
		BlockPos ground = landing.down();
		Vec3d hitVec = Vec3d.ofCenter(ground).add(0, 0.5, 0);
		if (mc.player.getEyePos().squaredDistanceTo(hitVec) > PlaceUtil.RANGE * PlaceUtil.RANGE) return;
		if (!Blocks.POINTED_DRIPSTONE.getDefaultState().canPlaceAt(mc.world, landing)) return;

		BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, ground, false);
		if (PlaceUtil.interact(hit, DRIPSTONE, switchMode.get(), rotate.get())) lastPlaced = landing;
	}

	/** First empty position above solid ground straight below the target. */
	private BlockPos findLanding(PlayerEntity target) {
		BlockPos pos = target.getBlockPos();
		for (int i = 0; i < maxHeight.getInt(); i++) {
			BlockPos below = pos.down();
			if (!mc.world.getBlockState(below).isReplaceable()) {
				return mc.world.getBlockState(pos).isReplaceable() ? pos : null;
			}
			pos = below;
		}
		return null;
	}
}
