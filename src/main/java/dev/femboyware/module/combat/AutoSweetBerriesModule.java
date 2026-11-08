package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.TargetUtil;
import dev.femboyware.util.Timer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Plants sweet berry bushes in the target's feet, which slow and hurt them while they move. */
public class AutoSweetBerriesModule extends Module {
	private static final Predicate<ItemStack> BERRIES = stack -> stack.is(Items.SWEET_BERRIES);

	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 5, 2, 8, 0.5));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between placements", 200, 0, 1000, 10));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to sweet berries", SwitchMode.SILENT));

	private final Timer timer = new Timer();

	public AutoSweetBerriesModule() {
		super("Auto Sweet Berries", "Plants sweet berry bushes on your target", Category.AUTO);
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.get())) return;
		Player target = TargetUtil.closestPlayer(targetRange.get());
		if (target == null) return;

		BlockPos feet = target.blockPosition();
		if (!mc.level.getBlockState(feet).canBeReplaced() || mc.level.getBlockState(feet).is(Blocks.SWEET_BERRY_BUSH)) return;
		if (!Blocks.SWEET_BERRY_BUSH.defaultBlockState().canSurvive(mc.level, feet)) return;

		// Bushes have no collision box, so the target standing there doesn't block the placement
		BlockPos ground = feet.below();
		Vec3 hitVec = Vec3.atCenterOf(ground).add(0, 0.5, 0);
		if (mc.player.getEyePosition().distanceToSqr(hitVec) > PlaceUtil.RANGE * PlaceUtil.RANGE) return;
		if (PlaceUtil.interact(new BlockHitResult(hitVec, Direction.UP, ground, false), BERRIES, switchMode.get(), rotate.get())) {
			timer.reset();
		}
	}
}
