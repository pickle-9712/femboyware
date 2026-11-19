package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One key throws an ender pearl, another a wind charge (straight down, with a jump, to launch
 * yourself), then swaps back to what you were holding. Ported from the Anubis client's Key Throw
 * (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class KeyThrowModule extends Module {
	private static final int BUFFER_TICKS = 10;

	private final KeySetting pearlKey = add(new KeySetting("Pearl Key", "Press to throw a pearl", -1));
	private final KeySetting windKey = add(new KeySetting("Wind Charge Key", "Press to throw a wind charge at your feet and jump", -1));

	private Item pending;
	private int pendingTicks, returnSlot = -1, returnIn;

	public KeyThrowModule() {
		super("Key Throw", "Throws a pearl or wind charge with a key and swaps back", Category.ATTACK);
	}

	@Override
	protected void onDisable() {
		pending = null;
		swapBack();
	}

	@Override
	public void onKey(int key) {
		Item item = pearlKey.get() != -1 && pearlKey.is(key) ? Items.ENDER_PEARL : windKey.get() != -1 && windKey.is(key) ? Items.WIND_CHARGE : null;
		if (item == null || Mc.screen() != null || mc.player == null) return;
		pending = item;
		pendingTicks = 0;
	}

	@Override
	public void onTick() {
		if (mc.player == null) {
			pending = null;
			returnSlot = -1;
			return;
		}
		if (returnSlot != -1 && --returnIn <= 0) swapBack();
		if (pending == null) return;
		if (++pendingTicks > BUFFER_TICKS || mc.player.isUsingItem()) {
			if (pendingTicks > BUFFER_TICKS) pending = null;
			return;
		}
		Item item = pending;
		boolean offhand = mc.player.getOffhandItem().is(item);
		int slot = mc.player.getMainHandItem().is(item) ? mc.player.getInventory().getSelectedSlot() : InventoryUtil.findHotbar(stack -> stack.is(item));
		if (!offhand && slot < 0) {
			pending = null;
			return;
		}
		ItemStack stack = offhand ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
		if (mc.player.getCooldowns().isOnCooldown(stack)) return;
		pending = null;
		int before = mc.player.getInventory().getSelectedSlot();
		if (!offhand && slot != before) {
			InventoryUtil.select(slot);
			returnSlot = before;
			returnIn = 1;
		}
		boolean wind = item == Items.WIND_CHARGE;
		float pitch = mc.player.getXRot();
		if (wind) {
			mc.player.setXRot(90);
			if (mc.player.onGround()) mc.player.jumpFromGround();
		}
		InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		InteractionResult result = mc.gameMode.useItem(mc.player, hand);
		if (result.consumesAction()) mc.player.swing(hand);
		if (wind) mc.player.setXRot(pitch);
	}

	private void swapBack() {
		if (returnSlot != -1 && mc.player != null) InventoryUtil.select(returnSlot);
		returnSlot = -1;
	}
}
