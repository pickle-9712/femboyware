package dev.femboyware.module.movement;

import dev.femboyware.util.Mc;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/** Fires a wind charge at your feet whenever you jump, launching you much higher. */
public class WindHopModule extends Module {
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to wind charges", SwitchMode.SILENT));
	private final BoolSetting onlySneaking = add(new BoolSetting("Only Sneaking", "Only hop while sneaking and jumping", false));

	private boolean wasJumping;

	public WindHopModule() {
		super("Wind Hop", "Wind charge jump when you press jump", Category.BASIC);
	}

	@Override
	public void onTick() {
		boolean jumping = mc.options.keyJump.isDown();
		boolean pressed = jumping && !wasJumping;
		wasJumping = jumping;
		if (!pressed || !mc.player.onGround() || Mc.screen() != null) return;
		if (onlySneaking.get() && !mc.options.keyShift.isDown()) return;

		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.WIND_CHARGE));
		if (slot < 0 || mc.player.getCooldowns().isOnCooldown(mc.player.getInventory().getItem(slot))) return;

		// The use packet carries our rotation, so look straight down just for it
		float pitch = mc.player.getXRot();
		InventoryUtil.withSlot(slot, switchMode.get(), () -> {
			mc.player.setXRot(90f);
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			mc.player.swing(InteractionHand.MAIN_HAND);
			mc.player.setXRot(pitch);
		});
		mc.player.jumpFromGround();
	}
}
