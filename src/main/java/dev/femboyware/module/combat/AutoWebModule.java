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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Webs the target's feet (and optionally head) so they can't run or escape a hole. */
public class AutoWebModule extends Module {
	private static final Predicate<ItemStack> WEB = stack -> stack.is(Items.COBWEB);

	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 5, 2, 8, 0.5));
	private final BoolSetting head = add(new BoolSetting("Head", "Also web the block at the target's head", false));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between placements", 150, 0, 1000, 10));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to cobwebs", SwitchMode.SILENT));

	private final Timer timer = new Timer();

	public AutoWebModule() {
		super("Auto Web", "Places cobwebs on your target", Category.ATTACK);
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.get())) return;
		Player target = TargetUtil.closestPlayer(targetRange.get());
		if (target == null) return;

		BlockPos feet = target.blockPosition();
		if (tryWeb(feet) || head.get() && tryWeb(feet.above())) timer.reset();
	}

	private boolean tryWeb(BlockPos pos) {
		if (mc.level.getBlockState(pos).is(Blocks.COBWEB)) return false;
		return PlaceUtil.placeInside(pos, WEB, switchMode.get(), rotate.get(), strict.get());
	}
}
