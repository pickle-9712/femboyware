package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Mine blocks through entities standing in front of them. The crosshair change is in
 * ClientPlayerEntityMixin.
 * Ported from Meteor Client's NoMiningTrace (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class NoMiningTraceModule extends Module {
	private final BoolSetting onlyPickaxe = add(new BoolSetting("Only Pickaxe", "Only while holding a pickaxe", true));
	private final BoolSetting ignorePlayers = add(new BoolSetting("Ignore Players", "Still target players so you can hit them", true));

	public NoMiningTraceModule() {
		super("No Mining Trace", "Mine through entities", Category.PLAYER);
	}

	/** Whether the crosshair should look past {@code entity} to the block behind it. */
	public boolean shouldSkip(Entity entity) {
		if (!isEnabled() || mc.player == null) return false;
		if (ignorePlayers.get() && entity instanceof Player) return false;
		return !onlyPickaxe.get() || mc.player.getMainHandItem().is(ItemTags.PICKAXES) || mc.player.getOffhandItem().is(ItemTags.PICKAXES);
	}
}
