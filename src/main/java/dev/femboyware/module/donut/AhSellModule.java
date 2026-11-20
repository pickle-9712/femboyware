package dev.femboyware.module.donut;

import dev.femboyware.setting.StringSetting;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ChoiceSetting;
import dev.femboyware.setting.NumberSetting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class AhSellModule extends Module {
	private static final Logger LOGGER = LoggerFactory.getLogger("femboyware");
	private static final int SLOTS = 9;
	private static final String FIXED = "Fixed";
	private static final String AUTO = "Auto (beat cheapest)";
	private final ChoiceSetting count = add(new ChoiceSetting("Items", "Items", "1", () -> List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")));
	private final ChoiceSetting pricing = add(new ChoiceSetting("Pricing", "Pricing", "Fixed", () -> List.of("Fixed", "Auto (beat cheapest)")));
	private final StringSetting[] items = new StringSetting[9];
	private final StringSetting[] enchants = new StringSetting[9];
	private final StringSetting[] prices = new StringSetting[9];
	private final StringSetting undercut;
	private final NumberSetting priceCheck;
	private final NumberSetting relistGap;
	private final NumberSetting ignoreDrops;
	private final NumberSetting salesCheck;
	private final NumberSetting perListing;
	private final NumberSetting maxListings;
	private final NumberSetting listingDelay;
	private final MacroWorker worker = new MacroWorker("femboyware AH Sell");
	private volatile AhSellBot bot;

	public AhSellModule() {
		super("AH Sell", "Lists up to 9 items from your inventory on the Auction House at your price or the cheapest price.", Category.MISC);

		for (int i = 0; i < 9; i++) {
			int slot = i + 1;
			int index = i;
			this.items[i] = add(new StringSetting("Item " + slot, "e.g. diamond_block", ""));
			this.enchants[i] = add(new StringSetting("Enchantments " + slot, "Enchantments " + slot, ""));
			this.prices[i] = add(new StringSetting("Price " + slot + " (each)", "e.g. 5k (Auto: lowest price)", ""));
			this.items[i].visibleWhen(() -> slot <= this.slotCount());
			this.enchants[i].visibleWhen(() -> slot <= this.slotCount() && ItemEnchants.supports(Product.resolveItem(this.items[index].get())));
			this.prices[i].visibleWhen(() -> slot <= this.slotCount());
		}

		this.undercut = add(new StringSetting("Undercut By", "e.g. 100", "100"));
		this.priceCheck = add(new NumberSetting("Price Check (s)", "Price Check (s)", 120.0F, 10.0F, 600.0F, 10.0F));
		this.relistGap = add(new NumberSetting("Relist Higher At (x undercut)", "Relist Higher At (x undercut)", 3.0F, 1.0F, 20.0F, 0.5F));
		this.ignoreDrops = add(new NumberSetting("Ignore Drops Over (x undercut)", "Ignore Drops Over (x undercut)", 20.0F, 1.0F, 100.0F, 1.0F));
		this.salesCheck = add(new NumberSetting("Sales Check (s)", "Sales Check (s)", 20.0F, 5.0F, 300.0F, 5.0F));
		this.perListing = add(new NumberSetting("Items Per Listing", "Items Per Listing", 1.0F, 1.0F, 64.0F, 1.0F));
		this.maxListings = add(new NumberSetting("Max Listings", "Max Listings", 25.0F, 1.0F, 50.0F, 1.0F));
		this.listingDelay = add(new NumberSetting("Listing Delay (s)", "Listing Delay (s)", 0.0F, 0.0F, 5.0F, 0.25F));
		this.undercut.visibleWhen(this::auto);
		this.priceCheck.visibleWhen(this::auto);
		this.relistGap.visibleWhen(this::auto);
		this.ignoreDrops.visibleWhen(this::auto);
	}

	private int slotCount() {
		return Integer.parseInt(this.count.get());
	}

	private boolean auto() {
		return this.pricing.get().equals("Auto (beat cheapest)");
	}

	@Override
	public boolean persistsEnabled() {
		return false;
	}

	@Override
	protected void onEnable() {
		if (mc.player != null && mc.getUser() != null) {
			List<Product> products = new ArrayList<>();
			String problem = this.buildProducts(products);
			if (problem != null) {
				this.info(ModuleChat.body().warn(problem));
				mc.execute(() -> this.setEnabled(false));
			} else {
				AuctionHouse.Config config = new AuctionHouse.Config(
					Math.max(0L, ServerText.parseMoney(this.undercut.get())),
					(long)(this.priceCheck.get() * 1000.0F),
					(long)(this.salesCheck.get() * 1000.0F),
					this.perListing.getInt(),
					this.maxListings.getInt(),
					(long)(this.listingDelay.get() * 1000.0F),
					this.relistGap.get(),
					this.ignoreDrops.get(),
					36,
					15000L
				);
				AhSellBot created = new AhSellBot(this, this.worker, products, !this.auto(), config, mc.getUser().getName());
				this.bot = created;
				this.worker.start(created::run, failure -> {
					LOGGER.error("AH Sell stopped", failure);
					this.info(ModuleChat.body().danger("Stopped").text(": " + failure));
					mc.execute(() -> {
						if (this.bot == created) {
							this.setEnabled(false);
						}
					});
				});
			}
		} else {
			this.info(ModuleChat.body().warn("Join a server first."));
			mc.execute(() -> this.setEnabled(false));
		}
	}

	@Override
	protected void onDisable() {
		AhSellBot stopped = this.bot;
		this.bot = null;
		this.worker.stop();
		LocalPlayer player = mc.player;
		if (stopped != null && player != null) {
			if (player.containerMenu != player.inventoryMenu) {
				player.closeContainer();
			} else {
				Dialogs.close(mc);
			}

			this.info(ModuleChat.body().text(stopped.summary()));
		}
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		AhSellBot current = this.bot;
		if (current != null && event.getPacket() instanceof ClientboundSystemChatPacket chat && chat.content() != null) {
			current.onChat(chat.content().getString());
		}
	}

	private String buildProducts(List<Product> out) {
		if (this.auto() && ServerText.parseMoney(this.undercut.get()) < 0L) {
			return "Undercut By must be a price like 100.";
		}

		Set<Item> seen = new HashSet<>();

		for (int i = 0; i < this.slotCount(); i++) {
			String raw = this.items[i].get().trim();
			if (!raw.isEmpty()) {
				Item item = Product.resolveItem(raw);
				if (item == null) {
					return "Item " + (i + 1) + ": unknown item '" + raw + "'.";
				}

				if (!seen.add(item)) {
					return "Item " + (i + 1) + ": " + raw + " is listed twice.";
				}

				long price = ServerText.parseMoney(this.prices[i].get());
				if (price <= 0L) {
					return "Item " + (i + 1) + ": set a price like 5k" + (this.auto() ? " (the lowest price Auto may use)." : ".");
				}

				List<Product.Enchant> wanted = ItemEnchants.supports(item) ? Product.parseEnchants(this.enchants[i].get()) : List.of();
				out.add(new Product(item, raw, Product.searchText(item), new ItemStack(item).getHoverName().getString(), price, wanted, false, 1, true, 1L, 1L, 0L));
			}
		}

		return out.isEmpty() ? "Set at least one item." : null;
	}

	private void info(ModuleChat.Body body) {
		ModuleChat.send(this, body);
	}
}
