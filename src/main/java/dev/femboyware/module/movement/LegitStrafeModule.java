package dev.femboyware.module.movement;

import dev.femboyware.util.Mc;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.KeyUtil;
import dev.femboyware.util.TargetUtil;
import java.util.Random;
import net.minecraft.world.entity.player.Player;

/**
 * Circles your opponent in melee by holding strafe keys for you, switching sides at random
 * intervals and when you bump into something. Only real key presses are used.
 */
public class LegitStrafeModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Strafe around players this close", 4.5, 1, 8, 0.1));
	private final NumberSetting minSwitch = add(new NumberSetting("Min Switch", "Fewest ticks before switching sides", 10, 1, 60, 1));
	private final NumberSetting maxSwitch = add(new NumberSetting("Max Switch", "Most ticks before switching sides", 30, 1, 80, 1));
	private final BoolSetting onlyAttacking = add(new BoolSetting("Only Attacking", "Only strafe while holding the attack button", true));

	private final Random random = new Random();
	private boolean left;
	private int ticksLeft;
	private boolean active;

	public LegitStrafeModule() {
		super("Legit Strafe", "Strafes around your target with real key presses", Category.BASIC);
	}

	@Override
	protected void onDisable() {
		if (active && !nullCheck()) release();
		active = false;
	}

	@Override
	public void onTick() {
		Player target = TargetUtil.closestPlayer(range.get());
		boolean attacking = !onlyAttacking.get() || KeyUtil.isPhysicallyPressed(mc.options.keyAttack);
		if (target == null || !attacking || Mc.screen() != null) {
			if (active) release();
			active = false;
			return;
		}
		active = true;
		if (--ticksLeft <= 0 || mc.player.horizontalCollision) {
			left = !left;
			int min = Math.min(minSwitch.getInt(), maxSwitch.getInt());
			ticksLeft = min + random.nextInt(Math.abs(maxSwitch.getInt() - minSwitch.getInt()) + 1);
		}
		mc.options.keyLeft.setDown(left);
		mc.options.keyRight.setDown(!left);
	}

	private void release() {
		KeyUtil.sync(mc.options.keyLeft);
		KeyUtil.sync(mc.options.keyRight);
	}
}
