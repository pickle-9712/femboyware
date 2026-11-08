package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.TargetUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Lands a mace smash the moment a target is in reach while you are falling. Mace damage scales
 * with fall distance, so this turns any drop onto someone into a big hit.
 */
public class MaceKillerModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Attack reach", 3.5, 1, 6, 0.1));
	private final NumberSetting minFall = add(new NumberSetting("Min Fall", "Fall distance needed before smashing", 3, 1.5, 30, 0.5));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to the mace", SwitchMode.SILENT));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Face the target before hitting", true));

	private boolean hitThisFall;

	public MaceKillerModule() {
		super("Mace Killer", "Smashes targets with a mace while you fall onto them", Category.ATTACK);
	}

	@Override
	public void onTick() {
		if (mc.player.onGround() || mc.player.isFallFlying()) {
			hitThisFall = false;
			return;
		}
		if (hitThisFall || mc.player.fallDistance < minFall.get()) return;

		Player target = TargetUtil.closestPlayer(range.get());
		if (target == null) return;
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.MACE));
		if (slot < 0) return;

		hitThisFall = InventoryUtil.withSlot(slot, switchMode.get(), () -> {
			if (rotate.get()) PlaceUtil.lookAt(target.getBoundingBox().getCenter());
			mc.gameMode.attack(mc.player, target);
			mc.player.swing(InteractionHand.MAIN_HAND);
		});
	}
}
