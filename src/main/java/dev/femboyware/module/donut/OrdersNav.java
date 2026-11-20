package dev.femboyware.module.donut;

import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
final class OrdersNav {
	static final int YOUR_ORDERS_BUTTON = 51;
	static final int ORDER_SLOTS = 45;
	private static final long OPEN_TIMEOUT_MS = 10000L;

	private OrdersNav() {
	}

	static boolean openYourOrders(MacroWorker worker, Gui gui, Consumer<ModuleChat.Body> say) {
		gui.closeAll();
		worker.sleep(150L);
		gui.command("orders");
		if (!worker.waitFor(OrdersNav::isOrdersRootNow, 10000L)) {
			say.accept(ModuleChat.body().warn("Could not open /orders."));
			gui.closeAll();
			return false;
		}

		String before = gui.fingerprint();
		gui.click(51, 0, ClickType.PICKUP);
		if (worker.waitFor(OrdersNav::isYourOrdersNow, 2500L)) {
			return true;
		}

		if (gui.waitChange(before, 1000L)) {
			worker.sleep(150L);
			if (worker.call(Gui::containerOpenNow) && !worker.call(OrdersNav::isOrdersRootNow)) {
				return true;
			}
		}

		say.accept(ModuleChat.body().warn("Could not open Your Orders."));
		gui.closeAll();
		return false;
	}

	static boolean isOrdersRootNow() {
		if (!Gui.containerOpenNow()) {
			return false;
		} else {
			String title = ServerText.normalize(dev.femboyware.util.Mc.screen().getTitle());
			if (title.startsWith("orders (page") || title.equals("orders")) {
				return true;
			} else if (title.contains("your orders") || title.contains("auction")) {
				return false;
			} else {
				return title.contains("orders") ? true : Gui.nameOf(Gui.slotNow(51)).contains("your order");
			}
		}
	}

	static boolean isYourOrdersNow() {
		if (!Gui.containerOpenNow()) {
			return false;
		}

		String title = ServerText.normalize(dev.femboyware.util.Mc.screen().getTitle());
		return title.contains("your orders") || Gui.findNow(OrdersNav::isNewOrderPane, 45) >= 0;
	}

	static boolean isNewOrderPane(ItemStack stack) {
		return Gui.isItem(stack, "gray_stained_glass_pane") && Gui.nameOf(stack).contains("new order");
	}

	static boolean isPane(ItemStack stack) {
		return stack.isEmpty() || BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("stained_glass_pane");
	}
}
