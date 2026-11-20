package dev.femboyware.module.donut;

import dev.femboyware.setting.StringSetting;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ChoiceSetting;
import dev.femboyware.setting.NumberSetting;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class OrderAhSellerModule extends Module {
	private static final Logger LOGGER = LoggerFactory.getLogger("femboyware");
	private static final int PRODUCTS = 5;
	private static final String PRICE_BEAT = "Beat Top Order";
	private static final String PRICE_FIXED = "Fixed";
	private final ChoiceSetting productSlot = add(new ChoiceSetting("Product", "Product", "1", () -> List.of("1", "2", "3", "4", "5")));
	private final StringSetting[] items = new StringSetting[5];
	private final StringSetting[] minPrices = new StringSetting[5];
	private final StringSetting[] enchants = new StringSetting[5];
	private final BoolSetting[] restock = new BoolSetting[5];
	private final StringSetting[] orderAmounts = new StringSetting[5];
	private final ChoiceSetting[] priceModes = new ChoiceSetting[5];
	private final StringSetting[] fixedPrices = new StringSetting[5];
	private final StringSetting[] bidIncrements = new StringSetting[5];
	private final StringSetting[] maxPrices = new StringSetting[5];
	private final StringSetting undercut;
	private final NumberSetting priceCheck;
	private final NumberSetting salesCheck;
	private final NumberSetting perListing;
	private final NumberSetting maxListings;
	private final NumberSetting listingDelay;
	private final NumberSetting relistGap;
	private final NumberSetting ignoreDrops;
	private final NumberSetting collectSlots;
	private final NumberSetting orderCheck;
	private final MacroWorker worker = new MacroWorker("femboyware Order AH Trader");
	private volatile OrderAhBot bot;

	public OrderAhSellerModule() {
		super("Order AH Trader", "Sells an item on the Auction House, undercuts other sellers and restocks through orders.", Category.MISC);

		for (int i = 0; i < 5; i++) {
			int slot = i + 1;
			String id = "product-" + slot + "-";
			this.items[i] = add(new StringSetting("Item " + slot, "e.g. barrel or diamond_chestplate", slot == 1 ? "barrel" : ""));
			this.minPrices[i] = add(new StringSetting("Min Price (each) " + slot, "e.g. 3k", slot == 1 ? "3k" : "1k"));
			this.enchants[i] = add(new StringSetting("Enchantments " + slot, "Enchantments", ""));
			this.restock[i] = add(new BoolSetting("Restock With Orders " + slot, "Restock With Orders", false));
			this.orderAmounts[i] = add(new StringSetting("Order Amount " + slot, "e.g. 64", "64"));
			this.priceModes[i] = add(new ChoiceSetting("Order Price " + slot, "Order Price", "Beat Top Order", () -> List.of("Beat Top Order", "Fixed")));
			this.fixedPrices[i] = add(new StringSetting("Fixed Order Price (each) " + slot, "e.g. 150k", "100"));
			this.bidIncrements[i] = add(new StringSetting("Beat By " + slot, "e.g. 1 or 100", "1"));
			this.maxPrices[i] = add(new StringSetting("Max Order Price (each) " + slot, "Empty = no limit", ""));
			int index = i;
			dev.femboyware.setting.Setting<?>[] all = new dev.femboyware.setting.Setting<?>[]{
				this.items[i],
				this.minPrices[i],
				this.enchants[i],
				this.restock[i],
				this.orderAmounts[i],
				this.priceModes[i],
				this.fixedPrices[i],
				this.bidIncrements[i],
				this.maxPrices[i]
			};

			for (dev.femboyware.setting.Setting<?> setting : all) {
				setting.visibleWhen(() -> this.selected() == index);
			}

			this.enchants[i].visibleWhen(() -> this.selected() == index && ItemEnchants.supports(Product.resolveItem(this.items[index].get())));
			this.orderAmounts[i].visibleWhen(() -> this.selected() == index && this.restock[index].get());
			this.priceModes[i].visibleWhen(() -> this.selected() == index && this.restock[index].get());
			this.fixedPrices[i].visibleWhen(() -> this.selected() == index && this.restock[index].get() && this.priceModes[index].get().equals("Fixed"));
			this.bidIncrements[i].visibleWhen(() -> this.selected() == index && this.restock[index].get() && this.priceModes[index].get().equals("Beat Top Order"));
			this.maxPrices[i].visibleWhen(() -> this.selected() == index && this.restock[index].get());
		}

		this.undercut = add(new StringSetting("Undercut By", "e.g. 100", "100"));
		this.priceCheck = add(new NumberSetting("Price Check (s)", "Price Check (s)", 120.0F, 10.0F, 600.0F, 10.0F));
		this.salesCheck = add(new NumberSetting("Sales Check (s)", "Sales Check (s)", 20.0F, 5.0F, 300.0F, 5.0F));
		this.perListing = add(new NumberSetting("Items Per Listing", "Items Per Listing", 1.0F, 1.0F, 64.0F, 1.0F));
		this.maxListings = add(new NumberSetting("Max Listings", "Max Listings", 25.0F, 1.0F, 50.0F, 1.0F));
		this.listingDelay = add(new NumberSetting("Listing Delay (s)", "Listing Delay (s)", 0.0F, 0.0F, 5.0F, 0.25F));
		this.relistGap = add(new NumberSetting("Relist Higher At (x undercut)", "Relist Higher At (x undercut)", 3.0F, 1.0F, 20.0F, 0.5F));
		this.ignoreDrops = add(new NumberSetting("Ignore Drops Over (x undercut)", "Ignore Drops Over (x undercut)", 20.0F, 1.0F, 100.0F, 1.0F));
		this.collectSlots = add(new NumberSetting("Collect Up To (slots)", "Collect Up To (slots)", 18.0F, 1.0F, 36.0F, 1.0F));
		this.orderCheck = add(new NumberSetting("Order Check (s)", "Order Check (s)", 15.0F, 5.0F, 300.0F, 5.0F));
		this.collectSlots.visibleWhen(this::anyRestock);
		this.orderCheck.visibleWhen(this::anyRestock);
	}

	private int selected() {
		return Integer.parseInt(this.productSlot.get()) - 1;
	}

	private boolean anyRestock() {
		return this.restock[this.selected()].get();
	}

	@Override
	public boolean persistsEnabled() {
		return false;
	}

	@Override
	protected void onEnable() {
		if (mc.player != null && mc.getUser() != null) {
			String problem = this.validate();
			if (problem != null) {
				this.info(ModuleChat.body().warn(problem));
				mc.execute(() -> this.setEnabled(false));
			} else {
				OrderAhBot created = new OrderAhBot(this, this.worker, this.buildProduct(), this.buildConfig(), mc.getUser().getName());
				this.bot = created;
				this.worker.start(created::run, failure -> {
					LOGGER.error("Order AH Trader stopped", failure);
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
		OrderAhBot stopped = this.bot;
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
		OrderAhBot current = this.bot;
		if (current != null && event.getPacket() instanceof ClientboundSystemChatPacket chat && chat.content() != null) {
			current.onChat(chat.content().getString());
		}
	}

	private String validate() {
		int i = this.selected();
		Item item = Product.resolveItem(this.items[i].get());
		if (item == null) {
			return "Product " + (i + 1) + ": unknown item '" + this.items[i].get() + "'.";
		} else if (ServerText.parseMoney(this.minPrices[i].get()) <= 0L) {
			return "Product " + (i + 1) + ": Min Price must be a price like 3k.";
		} else if (ServerText.parseMoney(this.undercut.get()) < 0L) {
			return "Undercut By must be a price like 100.";
		} else if (!this.restock[i].get()) {
			return null;
		} else if (parseAmount(this.orderAmounts[i].get()) <= 0) {
			return "Product " + (i + 1) + ": Order Amount must be a whole number.";
		} else if (this.priceModes[i].get().equals("Fixed") && ServerText.parseMoney(this.fixedPrices[i].get()) <= 0L) {
			return "Product " + (i + 1) + ": Fixed Order Price must be a price like 150k.";
		} else if (this.priceModes[i].get().equals("Beat Top Order") && ServerText.parseMoney(this.bidIncrements[i].get()) <= 0L) {
			return "Product " + (i + 1) + ": Beat By must be a price like 1.";
		} else {
			return !this.maxPrices[i].get().isBlank() && ServerText.parseMoney(this.maxPrices[i].get()) <= 0L
				? "Product " + (i + 1) + ": Max Order Price must be empty or a price like 200k."
				: null;
		}
	}

	private Product buildProduct() {
		int i = this.selected();
		Item item = Product.resolveItem(this.items[i].get());
		long max = this.maxPrices[i].get().isBlank() ? 0L : ServerText.parseMoney(this.maxPrices[i].get());
		return new Product(
			item,
			this.items[i].get().trim(),
			Product.searchText(item),
			new ItemStack(item).getHoverName().getString(),
			ServerText.parseMoney(this.minPrices[i].get()),
			ItemEnchants.supports(item) ? Product.parseEnchants(this.enchants[i].get()) : List.of(),
			this.restock[i].get(),
			parseAmount(this.orderAmounts[i].get()),
			this.priceModes[i].get().equals("Beat Top Order"),
			Math.max(1L, ServerText.parseMoney(this.fixedPrices[i].get())),
			Math.max(1L, ServerText.parseMoney(this.bidIncrements[i].get())),
			Math.max(0L, max)
		);
	}

	private AuctionHouse.Config buildConfig() {
		return new AuctionHouse.Config(
			Math.max(0L, ServerText.parseMoney(this.undercut.get())),
			(long)(this.priceCheck.get() * 1000.0F),
			(long)(this.salesCheck.get() * 1000.0F),
			this.perListing.getInt(),
			this.maxListings.getInt(),
			(long)(this.listingDelay.get() * 1000.0F),
			this.relistGap.get(),
			this.ignoreDrops.get(),
			this.collectSlots.getInt(),
			(long)(this.orderCheck.get() * 1000.0F)
		);
	}

	private static int parseAmount(String raw) {
		try {
			int value = Integer.parseInt(raw.trim());
			return value > 0 ? value : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private void info(ModuleChat.Body body) {
		ModuleChat.send(this, body);
	}
}
