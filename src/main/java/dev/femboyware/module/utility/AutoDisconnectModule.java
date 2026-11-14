package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/** Leaves the server when you're about to die: low health, or out of totems. */
public class AutoDisconnectModule extends Module {
	private final NumberSetting health = add(new NumberSetting("Health", "Leave at or below this many hearts", 3, 0.5, 10, 0.5));
	private final BoolSetting absorption = add(new BoolSetting("Count Absorption", "Golden hearts count as health", true));
	private final BoolSetting noTotems = add(new BoolSetting("No Totems", "Only once you've no totems left", false));
	private final BoolSetting turnOff = add(new BoolSetting("Turn Off", "Turn this off after it leaves, so it doesn't leave again on rejoin", true));

	public AutoDisconnectModule() {
		super("AutoDisconnect", "Leave the server at low health", Category.PLAYER);
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.getConnection() == null || mc.player.isDeadOrDying() || mc.player.isCreative()) return;
		float hp = mc.player.getHealth() + (absorption.get() ? mc.player.getAbsorptionAmount() : 0);
		if (hp > health.get() * 2) return;
		if (noTotems.get() && dev.femboyware.util.InventoryUtil.count(stack -> stack.is(Items.TOTEM_OF_UNDYING)) > 0) return;
		if (turnOff.get()) setEnabled(false);
		mc.getConnection().getConnection().disconnect(Component.literal("[AutoDisconnect] Left at " + String.format(java.util.Locale.ROOT, "%.1f", hp / 2) + " hearts"));
	}
}
