package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/**
 * A pearl catch in one press: throws an ender pearl where you're looking, then a wind charge at your
 * feet a moment later so you're launched up after it and catch it in the air. Turns itself off
 * when it's done, so bind it to a key.
 */
public class AutoPearlCatchModule extends Module {
	private final NumberSetting pitch = add(new NumberSetting("Pearl Pitch", "How high to throw the pearl (-90 straight up); 0 uses where you look", -45, -90, 0, 1));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between the pearl and the wind charge", 2, 0, 10, 1));
	private final BoolSetting jump = add(new BoolSetting("Jump", "Jump as the wind charge goes off, for more height", true));
	private final BoolSetting switchBack = add(new BoolSetting("Switch Back", "Go back to the slot you were holding", true));

	private int tick, previous;

	public AutoPearlCatchModule() {
		super("Auto Pearl Catch", "Throws a pearl then wind charges you up to catch it", Category.BASIC);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) {
			setEnabled(false);
			return;
		}
		if (InventoryUtil.findHotbar(s -> s.is(Items.ENDER_PEARL)) < 0 || InventoryUtil.findHotbar(s -> s.is(Items.WIND_CHARGE)) < 0) {
			ChatUtil.info("Auto Pearl Catch: needs an ender pearl and a wind charge in your hotbar");
			setEnabled(false);
			return;
		}
		previous = mc.player.getInventory().getSelectedSlot();
		tick = 0;
	}

	@Override
	public void onTick() {
		var p = mc.player;
		if (tick == 0) {
			throwAt(Items.ENDER_PEARL, pitch.get() == 0 ? p.getXRot() : pitch.get().floatValue());
		} else if (tick == delay.getInt() + 1) {
			if (jump.get() && p.onGround()) p.jumpFromGround();
			throwAt(Items.WIND_CHARGE, 90);
			if (switchBack.get()) InventoryUtil.select(previous);
			setEnabled(false);
		}
		tick++;
	}

	private void throwAt(net.minecraft.world.item.Item item, float throwPitch) {
		var p = mc.player;
		int slot = InventoryUtil.findHotbar(s -> s.is(item));
		if (slot < 0) return;
		InventoryUtil.select(slot);
		float old = p.getXRot();
		p.setXRot(throwPitch);
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		p.swing(InteractionHand.MAIN_HAND);
		p.setXRot(old);
	}
}
