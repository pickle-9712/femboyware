package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.larp.LedgerModule;
import dev.femboyware.module.render.ScoreboardEditModule;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * A fake balance, on your screen only: /bal says it, the sidebar's Money line shows it, and while
 * you're in Server Larp, /sell takes your larp items for it and /shop sells you larp items from it.
 * Set with /fakebal (or .fakebal); off, everything goes to the server as normal.
 */
public final class FakeEconomy {
	private static final Minecraft mc = Minecraft.getInstance();
	/** A sell or shop window to open on the next tick, once the chat box has closed. */
	private static Runnable pending;

	private FakeEconomy() {
	}

	private static ScoreboardEditModule board() {
		return FemboyWare.getModules().get(ScoreboardEditModule.class);
	}

	private static ServerLarpModule larp() {
		return FemboyWare.getModules().get(ServerLarpModule.class);
	}

	/** The fake balance, or -1 when it's off. */
	public static double balance() {
		return board().fakeBalance();
	}

	public static boolean on() {
		return balance() >= 0;
	}

	/** Sets the fake balance (negative turns it off), turning Scoreboard Edit on so the sidebar shows it. */
	private static void set(double amount) {
		board().setFakeBalance(amount < 0 ? -1 : Math.floor(amount * 100 + 0.5) / 100);
		if (amount >= 0 && !board().isEnabled()) board().setEnabled(true);
	}

	/** What the fake balance starts from when it's off: your real one, as well as it's known, or nothing. */
	private static double start() {
		if (on()) return balance();
		double real = FemboyWare.getModules().get(LedgerModule.class).balance();
		return real >= 0 ? real : 0;
	}

	/** Adds to (or with a negative amount takes from) the fake balance, starting it from your real one if it's off. */
	public static void add(double amount) {
		set(Math.max(0, start() + amount));
	}

	/** $1.5M, like the sidebar. */
	public static String money(double amount) {
		return "$" + ServerLarpModule.shortMoney(amount);
	}

	// ---------------------------------------------------------------- commands

	/**
	 * From ModuleManager for every packet going out, whatever's on: true if it's a command handled
	 * here, which then never reaches the server.
	 */
	public static boolean intercept(Packet<?> packet) {
		String command = packet instanceof ServerboundChatCommandPacket plain ? plain.command()
			: packet instanceof ServerboundChatCommandSignedPacket signed ? signed.command() : null;
		if (command == null || mc.player == null) return false;
		String[] words = command.trim().split("\\s+");
		if (words.length == 0 || words[0].isEmpty()) return false;
		String name = words[0].toLowerCase(Locale.ROOT);
		if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
		switch (name) {
			case "fakebal", "fakebalance", "fakemoney" -> {
				mc.execute(() -> command(words));
				return true;
			}
			case "bal", "balance", "money" -> {
				// Yours only; someone else's still goes to the server
				if (!on() || words.length > 1 && !words[1].equalsIgnoreCase(mc.player.getGameProfile().name())) return false;
				mc.execute(FakeEconomy::sayBalance);
				return true;
			}
			case "sell" -> {
				if (!larp().isLarping()) return false;
				pending = FakeEconomy::openSell;
				return true;
			}
			case "shop" -> {
				if (!larp().isLarping()) return false;
				pending = () -> openShop(-1);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** Server Larp's tick: opens a window asked for from chat. */
	static void tick() {
		if (pending == null || mc.player == null) return;
		Runnable run = pending;
		pending = null;
		run.run();
	}

	/** Handles .fakebal [amount|add|take|off] (args[0] is the command word). */
	public static void command(String[] args) {
		String usage = "Usage: " + CommandManager.PREFIX + "fakebal <amount|add <amount>|take <amount>|off>  (or /fakebal): what /bal and the sidebar say; /sell and /shop use it while larping";
		if (args.length < 2) {
			if (on()) sayBalance();
			else ChatUtil.info("Fake balance is off. " + usage);
			return;
		}
		String what = args[1].toLowerCase(Locale.ROOT);
		try {
			switch (what) {
				case "off", "reset", "clear", "real" -> {
					set(-1);
					ChatUtil.info("Fake balance off: /bal and the sidebar show your real one again");
					return;
				}
				case "add", "give", "+" -> add(parse(args, 2));
				case "take", "remove", "sub", "-" -> add(-parse(args, 2));
				case "set" -> set(parse(args, 2));
				default -> set(parse(args, 1));
			}
		} catch (RuntimeException e) {
			ChatUtil.info(usage);
			return;
		}
		ChatUtil.info("Fake balance: " + money(balance()));
	}

	private static double parse(String[] args, int at) {
		if (at >= args.length) throw new IllegalArgumentException();
		return ServerLarpModule.parseMoney(args[at]);
	}

	/** What /bal says, in the server's style. */
	private static void sayBalance() {
		Mc.addChat(Component.literal("You have ").withStyle(ChatFormatting.WHITE)
			.append(Component.literal(money(balance())).withStyle(ChatFormatting.GREEN)));
	}

	// ---------------------------------------------------------------- /sell

	/** Sell prices for anything the larp worth table hasn't got (.larp worth overrides these). */
	private static final Map<Item, Double> SELL = new LinkedHashMap<>();

	static {
		Object[] table = {
			Items.SPAWNER, 250_000, Items.ELYTRA, 150_000, Items.NETHER_STAR, 75_000, Items.DRAGON_EGG, 1_000_000, Items.BEACON, 90_000,
			Items.NETHERITE_BLOCK, 90_000, Items.NETHERITE_INGOT, 10_000, Items.NETHERITE_SCRAP, 2_400, Items.ANCIENT_DEBRIS, 2_400,
			Items.NETHERITE_SWORD, 14_000, Items.NETHERITE_PICKAXE, 14_000, Items.NETHERITE_AXE, 14_000, Items.NETHERITE_HELMET, 14_000,
			Items.NETHERITE_CHESTPLATE, 14_000, Items.NETHERITE_LEGGINGS, 14_000, Items.NETHERITE_BOOTS, 14_000, Items.MACE, 120_000,
			Items.TOTEM_OF_UNDYING, 1_500, Items.ENCHANTED_GOLDEN_APPLE, 6_000, Items.GOLDEN_APPLE, 120, Items.END_CRYSTAL, 60,
			Items.DIAMOND_BLOCK, 1_080, Items.DIAMOND, 120, Items.EMERALD_BLOCK, 450, Items.EMERALD, 50, Items.GOLD_BLOCK, 270,
			Items.GOLD_INGOT, 30, Items.IRON_BLOCK, 135, Items.IRON_INGOT, 15, Items.OBSIDIAN, 8, Items.ENDER_PEARL, 12,
			Items.EXPERIENCE_BOTTLE, 20, Items.BLAZE_ROD, 15, Items.GHAST_TEAR, 40, Items.SHULKER_SHELL, 600, Items.SHULKER_BOX, 1_300,
			Items.TRIDENT, 9_000, Items.HEAVY_CORE, 60_000, Items.WIND_CHARGE, 6, Items.BREEZE_ROD, 25, Items.SKELETON_SKULL, 600,
			Items.WITHER_SKELETON_SKULL, 9_000, Items.PLAYER_HEAD, 2_500, Items.GUNPOWDER, 4, Items.BONE, 3, Items.STRING, 3,
			Items.ROTTEN_FLESH, 1, Items.SPIDER_EYE, 3, Items.SLIME_BALL, 8, Items.MAGMA_CREAM, 10, Items.PHANTOM_MEMBRANE, 30,
			Items.SUGAR_CANE, 2, Items.CACTUS, 2, Items.PUMPKIN, 3, Items.MELON_SLICE, 1, Items.WHEAT, 2, Items.CARROT, 1, Items.POTATO, 1,
			Items.NETHER_WART, 2, Items.COCOA_BEANS, 2, Items.SWEET_BERRIES, 1, Items.KELP, 1, Items.BAMBOO, 1,
			Items.ARROW, 2, Items.LEATHER, 5, Items.BEEF, 3, Items.PORKCHOP, 3, Items.CHICKEN, 2, Items.FEATHER, 2, Items.MUTTON, 2,
			Items.GOLD_NUGGET, 3, Items.POPPY, 1, Items.INK_SAC, 3, Items.PRISMARINE_SHARD, 6, Items.PRISMARINE_CRYSTALS, 8,
			Items.COD, 2, Items.SALMON, 2, Items.RABBIT_HIDE, 4, Items.RABBIT, 3, Items.GLOWSTONE_DUST, 4, Items.REDSTONE, 3, Items.SUGAR, 2, Items.GLASS_BOTTLE, 1,
			Items.STICK, 1, Items.COBBLESTONE, 0.5, Items.STONE, 1, Items.DIRT, 0.25, Items.SAND, 1, Items.OAK_LOG, 3, Items.SPRUCE_LOG, 3, Items.BIRCH_LOG, 3,
		};
		for (int i = 0; i < table.length; i += 2) SELL.put((Item) table[i], ((Number) table[i + 1]).doubleValue());
	}

	/** What one of these sells for: your .larp worth price if you've set one, else the table's; 0 if it doesn't sell. */
	public static double sellPrice(ItemStack stack) {
		double custom = larp().worthEach(stack);
		if (custom > 0) return custom;
		return SELL.getOrDefault(stack.getItem(), 0.0);
	}

	private static void openSell() {
		Inventory inventory = mc.player.getInventory();
		SellMenu menu = new SellMenu(inventory);
		mc.player.containerMenu = menu;
		Mc.setScreen(new ContainerScreen(menu, inventory, Component.literal("Sell Items")));
	}

	/** A double chest to put things in: whatever sells is sold when you close it, the rest comes back. */
	private static final class SellMenu extends ChestMenu implements dev.femboyware.util.LocalMenu {
		private boolean done;

		SellMenu(Inventory inventory) {
			super(MenuType.GENERIC_9x6, ServerLarpModule.FAKE_CONTAINER_ID, inventory, new SimpleContainer(54), 6);
		}

		@Override
		public void removed(Player player) {
			if (!done) {
				done = true;
				double total = 0;
				int sold = 0;
				List<ItemStack> back = new ArrayList<>();
				for (int i = 0; i < getContainer().getContainerSize(); i++) {
					ItemStack stack = getContainer().getItem(i);
					if (stack.isEmpty()) continue;
					double each = sellPrice(stack);
					if (each > 0) {
						total += each * stack.getCount();
						sold += stack.getCount();
					} else back.add(stack.copy());
				}
				getContainer().clearContent();
				for (ItemStack stack : back) {
					player.getInventory().add(stack);
				}
				if (sold > 0) {
					add(total);
					Mc.addChat(Component.literal("You sold ").withStyle(ChatFormatting.WHITE)
						.append(Component.literal(sold + " item" + (sold == 1 ? "" : "s")).withStyle(ChatFormatting.AQUA))
						.append(Component.literal(" for ").withStyle(ChatFormatting.WHITE))
						.append(Component.literal(money(total)).withStyle(ChatFormatting.GREEN)));
					mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f, 0.7f));
				}
				if (!back.isEmpty()) ChatUtil.info(back.size() + " stack" + (back.size() == 1 ? " doesn't" : "s don't") + " sell, so " + (back.size() == 1 ? "it's" : "they're")
					+ " back in your inventory (set a price with " + CommandManager.PREFIX + "larp worth <item> <price>)");
			}
			super.removed(player);
		}
	}

	// ---------------------------------------------------------------- /shop

	private record Category(String name, Item icon, Object[] items) {
	}

	/** What the shop sells, by category: item, price each. */
	private static final Category[] SHOP = {
		new Category("End", Items.END_STONE, new Object[]{
			Items.ENDER_PEARL, 15, Items.END_CRYSTAL, 80, Items.OBSIDIAN, 12, Items.SHULKER_SHELL, 900, Items.SHULKER_BOX, 2_000,
			Items.ENDER_CHEST, 120, Items.CHORUS_FRUIT, 6, Items.END_STONE, 2, Items.PURPUR_BLOCK, 4, Items.DRAGON_BREATH, 50}),
		new Category("Nether", Items.NETHERRACK, new Object[]{
			Items.BLAZE_ROD, 25, Items.NETHER_WART, 4, Items.GLOWSTONE, 10, Items.QUARTZ, 4, Items.MAGMA_CREAM, 15,
			Items.GHAST_TEAR, 60, Items.RESPAWN_ANCHOR, 400, Items.CRYING_OBSIDIAN, 40, Items.SOUL_SAND, 4, Items.NETHERRACK, 1,
			Items.ANCIENT_DEBRIS, 3_500, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 9_000}),
		new Category("Gear", Items.TOTEM_OF_UNDYING, new Object[]{
			Items.TOTEM_OF_UNDYING, 2_500, Items.GOLDEN_APPLE, 200, Items.ENCHANTED_GOLDEN_APPLE, 9_000, Items.EXPERIENCE_BOTTLE, 30,
			Items.FIREWORK_ROCKET, 5, Items.ARROW, 2, Items.COBWEB, 15, Items.WIND_CHARGE, 10, Items.TNT, 40, Items.TRIDENT, 14_000,
			Items.ELYTRA, 220_000, Items.MACE, 180_000, Items.SPAWNER, 400_000}),
		new Category("Food", Items.GOLDEN_CARROT, new Object[]{
			Items.GOLDEN_CARROT, 8, Items.COOKED_BEEF, 5, Items.COOKED_PORKCHOP, 5, Items.BREAD, 3, Items.BAKED_POTATO, 3,
			Items.PUMPKIN_PIE, 6, Items.CAKE, 30, Items.SWEET_BERRIES, 1, Items.HONEY_BOTTLE, 6}),
		new Category("Blocks", Items.GRASS_BLOCK, new Object[]{
			Items.DIAMOND_BLOCK, 1_500, Items.NETHERITE_BLOCK, 140_000, Items.GOLD_BLOCK, 400, Items.IRON_BLOCK, 200, Items.EMERALD_BLOCK, 700,
			Items.BEACON, 140_000, Items.OAK_LOG, 5, Items.STONE, 2, Items.GLASS, 3, Items.QUARTZ_BLOCK, 16, Items.SEA_LANTERN, 30, Items.HOPPER, 60}),
	};

	private static void openShop(int category) {
		Inventory inventory = mc.player.getInventory();
		ShopMenu menu = new ShopMenu(inventory, category);
		mc.player.containerMenu = menu;
		Mc.setScreen(new ContainerScreen(menu, inventory, Component.literal(category < 0 ? "Shop" : "Shop - " + SHOP[category].name())));
	}

	private static MutableComponent plain(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(color));
	}

	private static ItemStack named(ItemStack stack, Component name, List<Component> lore) {
		stack.set(DataComponents.CUSTOM_NAME, name);
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	/**
	 * The shop window: the categories, or one category's items with their prices. Click to buy one,
	 * shift-click for a stack; it comes off your fake balance and into your (larp) inventory.
	 */
	private static final class ShopMenu extends ChestMenu implements dev.femboyware.util.LocalMenu {
		private static final int BACK = 49, BALANCE = 53;
		private final int category;

		ShopMenu(Inventory inventory, int category) {
			super(MenuType.GENERIC_9x6, ServerLarpModule.FAKE_CONTAINER_ID, inventory, new SimpleContainer(54), 6);
			this.category = category;
			fill();
		}

		private void fill() {
			var box = getContainer();
			box.clearContent();
			ItemStack pane = named(new ItemStack(Items.GLASS_PANE), Component.literal(" "), List.of());
			for (int i = 45; i < 54; i++) box.setItem(i, pane.copy());
			if (category < 0) {
				int[] spots = {20, 21, 22, 23, 24};
				for (int c = 0; c < SHOP.length; c++) {
					box.setItem(spots[c], named(new ItemStack(SHOP[c].icon()), plain(SHOP[c].name(), ChatFormatting.AQUA).withStyle(ChatFormatting.BOLD),
						List.of(plain("Click to browse", ChatFormatting.GRAY))));
				}
			} else {
				Object[] items = SHOP[category].items();
				for (int i = 0; i < items.length / 2 && i < 45; i++) {
					Item item = (Item) items[i * 2];
					double price = ((Number) items[i * 2 + 1]).doubleValue();
					int stack = new ItemStack(item).getMaxStackSize();
					List<Component> lore = new ArrayList<>();
					lore.add(plain("Price: ", ChatFormatting.GRAY).append(plain(money(price), ChatFormatting.GREEN)));
					lore.add(Component.empty());
					lore.add(plain("Click to buy 1", ChatFormatting.YELLOW));
					if (stack > 1) lore.add(plain("Shift-click to buy " + stack + " (" + money(price * stack) + ")", ChatFormatting.YELLOW));
					ItemStack shown = new ItemStack(item);
					shown.set(DataComponents.LORE, new ItemLore(lore));
					box.setItem(i, shown);
				}
				box.setItem(BACK, named(new ItemStack(Items.ARROW), plain("Back", ChatFormatting.RED), List.of()));
			}
			box.setItem(BALANCE, named(new ItemStack(Items.SUNFLOWER), plain("Your balance", ChatFormatting.GOLD),
				List.of(plain(money(start()), ChatFormatting.GREEN))));
		}

		@Override
		public void clicked(int slot, int button, ClickType type, Player player) {
			// Your own inventory works as normal; the shop's slots are buttons
			if (slot < 0 || slot >= 54) {
				super.clicked(slot, button, type, player);
				return;
			}
			if (type != ClickType.PICKUP && type != ClickType.QUICK_MOVE) return;
			if (category < 0) {
				int[] spots = {20, 21, 22, 23, 24};
				for (int c = 0; c < SHOP.length; c++) {
					int chosen = c;
					if (spots[c] == slot) click(() -> openShop(chosen));
				}
				return;
			}
			if (slot == BACK) {
				click(() -> openShop(-1));
				return;
			}
			Object[] items = SHOP[category].items();
			if (slot >= items.length / 2) return;
			buy((Item) items[slot * 2], ((Number) items[slot * 2 + 1]).doubleValue(), type == ClickType.QUICK_MOVE, player);
			fill();
		}

		private static void click(Runnable then) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
			pending = then;
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			// Nothing shift-clicks into the shop
			return ItemStack.EMPTY;
		}
	}

	private static void buy(Item item, double price, boolean stack, Player player) {
		int count = stack ? new ItemStack(item).getMaxStackSize() : 1;
		double cost = price * count, have = start();
		String name = new ItemStack(item).getHoverName().getString();
		if (have < cost) {
			Mc.addChat(Component.literal("You can't afford that! ").withStyle(ChatFormatting.RED)
				.append(Component.literal(count + "x " + name + " costs " + money(cost)).withStyle(ChatFormatting.GRAY)));
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1f, 0.6f));
			return;
		}
		ItemStack bought = new ItemStack(item, count);
		player.getInventory().add(bought);
		int got = count - bought.getCount();
		if (got == 0) {
			ChatUtil.info("Your inventory's full");
			return;
		}
		add(-price * got);
		Mc.addChat(Component.literal("You bought ").withStyle(ChatFormatting.WHITE)
			.append(Component.literal(got + "x " + name).withStyle(ChatFormatting.AQUA))
			.append(Component.literal(" for ").withStyle(ChatFormatting.WHITE))
			.append(Component.literal(money(price * got)).withStyle(ChatFormatting.GREEN)));
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f, 0.6f));
	}
}
