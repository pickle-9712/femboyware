package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.Timer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Drops junk from your inventory as you pick it up: whatever items you list. */
public class InvCleanerModule extends Module {
	private final StringSetting junk = add(new StringSetting("Junk", "Item ids to drop, comma separated",
		"rotten_flesh,poisonous_potato,spider_eye,string,bone,gunpowder,arrow,cobblestone,cobbled_deepslate,diorite,granite,andesite,tuff,dirt,gravel,netherrack,wheat_seeds,egg,feather"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between drops", 100, 0, 1000, 10));

	private final Timer timer = new Timer();
	private String parsedFrom;
	private final Set<String> ids = new HashSet<>();

	public InvCleanerModule() {
		super("InvCleaner", "Drop junk items automatically", Category.EQUIP);
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.gameMode == null || !timer.passed(delay.get())) return;
		// Only with no window open, or your own inventory, so clicks go to the right place
		if (Mc.screen() != null && !(Mc.screen() instanceof InventoryScreen)) return;
		if (mc.player.containerMenu != mc.player.inventoryMenu) return;
		if (!junk.get().equals(parsedFrom)) {
			parsedFrom = junk.get();
			ids.clear();
			for (String id : parsedFrom.split(",")) if (!id.isBlank()) ids.add(id.trim().toLowerCase(Locale.ROOT).replace("minecraft:", ""));
		}
		var inventory = mc.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !ids.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())) continue;
			InventoryUtil.click(InventoryUtil.screenSlot(i), 1, ClickType.THROW);
			timer.reset();
			return;
		}
	}
}
