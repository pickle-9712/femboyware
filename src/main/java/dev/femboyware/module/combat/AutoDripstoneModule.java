package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.TargetUtil;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Plants a stalagmite where a falling target is about to land. Landing on pointed dripstone
 * doubles fall damage.
 */
public class AutoDripstoneModule extends Module {
	private static final Predicate<ItemStack> DRIPSTONE = stack -> stack.is(Items.POINTED_DRIPSTONE);

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
		Player target = TargetUtil.closestPlayer(targetRange.get());
		if (target == null || target.onGround() || target.isFallFlying() || target.fallDistance < minFall.get()) return;

		BlockPos landing = findLanding(target);
		if (landing == null || landing.equals(lastPlaced)) return;
		BlockPos ground = landing.below();
		Vec3 hitVec = Vec3.atCenterOf(ground).add(0, 0.5, 0);
		if (mc.player.getEyePosition().distanceToSqr(hitVec) > PlaceUtil.RANGE * PlaceUtil.RANGE) return;
		if (!Blocks.POINTED_DRIPSTONE.defaultBlockState().canSurvive(mc.level, landing)) return;

		BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, ground, false);
		if (PlaceUtil.interact(hit, DRIPSTONE, switchMode.get(), rotate.get())) lastPlaced = landing;
	}

	/** First empty position above solid ground straight below the target. */
	private BlockPos findLanding(Player target) {
		BlockPos pos = target.blockPosition();
		for (int i = 0; i < maxHeight.getInt(); i++) {
			BlockPos below = pos.below();
			if (!mc.level.getBlockState(below).canBeReplaced()) {
				return mc.level.getBlockState(pos).canBeReplaced() ? pos : null;
			}
			pos = below;
		}
		return null;
	}
}
