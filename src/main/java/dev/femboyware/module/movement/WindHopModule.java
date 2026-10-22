package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

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
		boolean jumping = mc.options.jumpKey.isPressed();
		boolean pressed = jumping && !wasJumping;
		wasJumping = jumping;
		if (!pressed || !mc.player.isOnGround() || mc.currentScreen != null) return;
		if (onlySneaking.get() && !mc.options.sneakKey.isPressed()) return;

		int slot = InventoryUtil.findHotbar(stack -> stack.isOf(Items.WIND_CHARGE));
		if (slot < 0 || mc.player.getItemCooldownManager().isCoolingDown(mc.player.getInventory().getStack(slot))) return;

		// The use packet carries our rotation, so look straight down just for it
		float pitch = mc.player.getPitch();
		InventoryUtil.withSlot(slot, switchMode.get(), () -> {
			mc.player.setPitch(90f);
			mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
			mc.player.swingHand(Hand.MAIN_HAND);
			mc.player.setPitch(pitch);
		});
		mc.player.jump();
	}
}
