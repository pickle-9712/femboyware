package dev.femboyware.module.larp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.FakeEconomy;
import dev.femboyware.module.utility.ServerLarpModule;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * DonutSMP spawners on your screen only: any block you point at becomes a stacked spawner with its
 * mob spinning inside. Right-click it for the spawner menu: loot piles up over time by stack size,
 * Sell All puts it on your fake balance (see /fakebal), and while larping you can take it.
 */
public class FakeSpawnerModule extends Module {
	/** What each mob drops per kill, on average. */
	private static final Map<String, Object[]> DROPS = new LinkedHashMap<>();

	static {
		DROPS.put("skeleton", new Object[]{Items.BONE, 1.0, Items.ARROW, 1.0});
		DROPS.put("zombie", new Object[]{Items.ROTTEN_FLESH, 1.0});
		DROPS.put("spider", new Object[]{Items.STRING, 1.0, Items.SPIDER_EYE, 0.33});
		DROPS.put("cave_spider", new Object[]{Items.STRING, 1.0, Items.SPIDER_EYE, 0.33});
		DROPS.put("creeper", new Object[]{Items.GUNPOWDER, 1.0});
		DROPS.put("blaze", new Object[]{Items.BLAZE_ROD, 0.5});
		DROPS.put("iron_golem", new Object[]{Items.IRON_INGOT, 4.0, Items.POPPY, 1.0});
		DROPS.put("zombified_piglin", new Object[]{Items.ROTTEN_FLESH, 1.0, Items.GOLD_NUGGET, 1.0, Items.GOLD_INGOT, 0.03});
		DROPS.put("enderman", new Object[]{Items.ENDER_PEARL, 0.5});
		DROPS.put("slime", new Object[]{Items.SLIME_BALL, 1.0});
		DROPS.put("magma_cube", new Object[]{Items.MAGMA_CREAM, 0.5});
		DROPS.put("witch", new Object[]{Items.REDSTONE, 0.6, Items.GLOWSTONE_DUST, 0.6, Items.SUGAR, 0.6, Items.GLASS_BOTTLE, 0.6, Items.STICK, 0.6});
		DROPS.put("cow", new Object[]{Items.BEEF, 2.0, Items.LEATHER, 1.0});
		DROPS.put("pig", new Object[]{Items.PORKCHOP, 2.0});
		DROPS.put("chicken", new Object[]{Items.CHICKEN, 1.0, Items.FEATHER, 1.0});
		DROPS.put("sheep", new Object[]{Items.MUTTON, 1.5});
		DROPS.put("rabbit", new Object[]{Items.RABBIT, 0.5, Items.RABBIT_HIDE, 0.5});
		DROPS.put("squid", new Object[]{Items.INK_SAC, 2.0});
		DROPS.put("guardian", new Object[]{Items.PRISMARINE_SHARD, 1.0, Items.PRISMARINE_CRYSTALS, 0.4, Items.COD, 0.4});
	}

	/** Kills per spawner in its stack per minute, and the XP each gives. */
	private static final double KILLS_PER_MINUTE = 2, XP_PER_KILL = 5;
	/** Loot the menu holds at most, in item counts (five rows of stacks). */
	private static final int MAX_LOOT = 45 * 64;

	private final StringSetting saved = add(new StringSetting("Spawners", "", "[]")).visibleWhen(() -> false);

	private static final class Spawner {
		final BlockPos pos;
		final String mob;
		int stacks;
		long updated;
		final Map<Item, Double> loot = new LinkedHashMap<>();
		double xp;

		Spawner(BlockPos pos, String mob, int stacks, long updated) {
			this.pos = pos;
			this.mob = mob;
			this.stacks = stacks;
			this.updated = updated;
		}
	}

	private final List<Spawner> spawners = new ArrayList<>();
	/** What each block was before it became a spawner here, to put back. */
	private final Map<BlockPos, BlockState> under = new HashMap<>();
	private boolean loaded;

	public FakeSpawnerModule() {
		super("Fake Spawner", "DonutSMP spawners on your screen: " + CommandManager.PREFIX + "fakespawner add <mob> [stacks] on the block you're looking at", Category.LARP);
	}

	@Override
	public String getInfo() {
		return spawners.isEmpty() ? null : String.valueOf(spawners.size());
	}

	// ---------------------------------------------------------------- saving

	private void load() {
		if (loaded) return;
		loaded = true;
		spawners.clear();
		try {
			for (var element : JsonParser.parseString(saved.get()).getAsJsonArray()) {
				JsonObject o = element.getAsJsonObject();
				spawners.add(new Spawner(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()),
					o.get("mob").getAsString(), o.get("stacks").getAsInt(), o.get("updated").getAsLong()));
			}
		} catch (RuntimeException ignored) {
		}
	}

	private void save() {
		JsonArray array = new JsonArray();
		for (Spawner s : spawners) {
			JsonObject o = new JsonObject();
			o.addProperty("x", s.pos.getX());
			o.addProperty("y", s.pos.getY());
			o.addProperty("z", s.pos.getZ());
			o.addProperty("mob", s.mob);
			o.addProperty("stacks", s.stacks);
			o.addProperty("updated", s.updated);
			array.add(o);
		}
		saved.set(array.toString());
	}

	// ---------------------------------------------------------------- the blocks

	@Override
	public void onTick() {
		load();
		if (mc.level == null) return;
		for (Spawner s : spawners) {
			if (!mc.level.hasChunkAt(s.pos)) continue;
			BlockState state = mc.level.getBlockState(s.pos);
			if (state.is(Blocks.SPAWNER) && mc.level.getBlockEntity(s.pos) instanceof SpawnerBlockEntity) continue;
			// Put (back) as a spawner here; the server sending the real block over it is undone next tick
			under.put(s.pos, state);
			mc.level.setBlock(s.pos, Blocks.SPAWNER.defaultBlockState(), 0);
			if (mc.level.getBlockEntity(s.pos) instanceof SpawnerBlockEntity spawner) {
				EntityType<?> type = type(s.mob);
				if (type != null) spawner.setEntityId(type, mc.level.getRandom());
			}
		}
	}

	@Override
	protected void onDisable() {
		for (Spawner s : spawners) restore(s.pos);
	}

	private void restore(BlockPos pos) {
		BlockState was = under.remove(pos);
		if (mc.level != null && was != null && mc.level.hasChunkAt(pos)) mc.level.setBlock(pos, was, 0);
	}

	private static EntityType<?> type(String mob) {
		Identifier id = Identifier.tryParse(mob.contains(":") ? mob : "minecraft:" + mob);
		return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
	}

	private static String name(String mob) {
		String plain = mob.contains(":") ? mob.substring(mob.indexOf(':') + 1) : mob;
		StringBuilder out = new StringBuilder();
		for (String word : plain.split("_")) {
			if (word.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.toString();
	}

	private Spawner at(BlockPos pos) {
		for (Spawner s : spawners) if (s.pos.equals(pos)) return s;
		return null;
	}

	/** From ClientPlayerInteractionManagerMixin: right-clicking one of ours opens its menu; true if it was one. */
	public boolean onUse(BlockHitResult hit) {
		if (!isEnabled() || mc.player == null) return false;
		Spawner s = at(hit.getBlockPos());
		if (s == null) return false;
		open(s);
		return true;
	}

	// ---------------------------------------------------------------- loot

	/** Adds what it's made since it was last looked at. */
	private void produce(Spawner s) {
		long now = System.currentTimeMillis();
		double kills = s.stacks * KILLS_PER_MINUTE * (now - s.updated) / 60_000.0;
		s.updated = now;
		Object[] drops = DROPS.getOrDefault(s.mob.replace("minecraft:", ""), new Object[]{Items.ROTTEN_FLESH, 1.0});
		double held = 0;
		for (double count : s.loot.values()) held += count;
		for (int i = 0; i < drops.length; i += 2) {
			Item item = (Item) drops[i];
			double add = kills * (Double) drops[i + 1];
			if (held + add > MAX_LOOT) add = Math.max(0, MAX_LOOT - held);
			s.loot.merge(item, add, Double::sum);
			held += add;
		}
		s.xp += kills * XP_PER_KILL;
		save();
	}

	private static double value(Spawner s) {
		double total = 0;
		for (Map.Entry<Item, Double> e : s.loot.entrySet()) {
			total += FakeEconomy.sellPrice(new ItemStack(e.getKey())) * Math.floor(e.getValue());
		}
		return total;
	}

	private void open(Spawner s) {
		produce(s);
		Inventory inventory = mc.player.getInventory();
		SpawnerMenu menu = new SpawnerMenu(inventory, s);
		mc.player.containerMenu = menu;
		Mc.setScreen(new ContainerScreen(menu, inventory, Component.literal(name(s.mob) + " Spawner" + (s.stacks > 1 ? " (x" + s.stacks + ")" : ""))));
	}

	private static MutableComponent plain(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(st -> st.withItalic(false).withColor(color));
	}

	private static ItemStack named(Item item, Component name, List<Component> lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, name);
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	private static boolean larping() {
		return FemboyWare.getModules().get(ServerLarpModule.class).isLarping();
	}

	/** The spawner's menu: five rows of loot, then XP, Sell All and its stack. */
	private final class SpawnerMenu extends ChestMenu implements dev.femboyware.util.LocalMenu {
		private static final int XP = 48, SELL = 49, INFO = 50;
		private final Spawner spawner;
		private final List<Item> shown = new ArrayList<>();

		SpawnerMenu(Inventory inventory, Spawner spawner) {
			super(MenuType.GENERIC_9x6, 122, inventory, new SimpleContainer(54), 6);
			this.spawner = spawner;
			fill();
		}

		private void fill() {
			var box = getContainer();
			box.clearContent();
			shown.clear();
			int slot = 0;
			for (Map.Entry<Item, Double> e : spawner.loot.entrySet()) {
				int count = (int) Math.floor(e.getValue());
				while (count > 0 && slot < 45) {
					int n = Math.min(64, count);
					box.setItem(slot++, new ItemStack(e.getKey(), n));
					shown.add(e.getKey());
					count -= n;
				}
			}
			ItemStack pane = named(Items.GLASS_PANE, Component.literal(" "), List.of());
			for (int i = 45; i < 54; i++) box.setItem(i, pane.copy());
			box.setItem(XP, named(Items.EXPERIENCE_BOTTLE, plain("Collect XP", ChatFormatting.GREEN),
				List.of(plain((int) spawner.xp + " XP stored", ChatFormatting.GRAY))));
			box.setItem(SELL, named(Items.GOLD_INGOT, plain("Sell All", ChatFormatting.GOLD),
				List.of(plain("Worth ", ChatFormatting.GRAY).append(plain(FakeEconomy.money(value(spawner)), ChatFormatting.GREEN)))));
			box.setItem(INFO, named(Items.SPAWNER, plain(name(spawner.mob) + " Spawner", ChatFormatting.AQUA),
				List.of(plain("Stacked: x" + spawner.stacks, ChatFormatting.GRAY))));
		}

		@Override
		public void clicked(int slot, int button, ClickType type, Player player) {
			if (slot < 0 || slot >= 54) {
				super.clicked(slot, button, type, player);
				return;
			}
			if (slot == SELL) {
				double total = value(spawner);
				if (total <= 0) return;
				spawner.loot.clear();
				FakeEconomy.add(total);
				Mc.addChat(Component.literal("You sold your spawner loot for ").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(FakeEconomy.money(total)).withStyle(ChatFormatting.GREEN)));
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f, 0.7f));
			} else if (slot == XP) {
				int xp = (int) spawner.xp;
				if (xp <= 0) return;
				if (!larping()) {
					ChatUtil.info("Turn on Server Larp to take a fake spawner's XP or loot");
					return;
				}
				spawner.xp -= xp;
				player.giveExperiencePoints(xp);
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 0.6f));
			} else if (slot < 45) {
				ItemStack stack = getContainer().getItem(slot);
				if (stack.isEmpty()) return;
				if (!larping()) {
					ChatUtil.info("Turn on Server Larp to take a fake spawner's XP or loot");
					return;
				}
				ItemStack taking = stack.copy();
				player.getInventory().add(taking);
				int took = stack.getCount() - taking.getCount();
				if (took > 0) spawner.loot.merge(stack.getItem(), (double) -took, Double::sum);
			} else return;
			save();
			fill();
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			return ItemStack.EMPTY;
		}
	}

	// ---------------------------------------------------------------- command

	/** Handles .fakespawner add <mob> [stacks], stack <n>, remove, list and clear. */
	public void command(String[] args) {
		load();
		String usage = "Usage: " + CommandManager.PREFIX + "fakespawner <add <mob> [stacks]|stack <stacks>|remove|list|clear>  (on the block you're looking at)";
		if (args.length < 2) {
			ChatUtil.info(usage);
			return;
		}
		BlockPos looking = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().immutable() : null;
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "add", "set" -> {
				if (args.length < 3 || looking == null) {
					ChatUtil.info(looking == null ? "Look at a block first" : usage);
					return;
				}
				String mob = args[2].toLowerCase(Locale.ROOT);
				if (type(mob) == null) {
					ChatUtil.info("No mob called " + args[2] + " (like skeleton, zombie, blaze, iron_golem)");
					return;
				}
				int stacks = 1;
				try {
					if (args.length > 3) stacks = Math.max(1, Integer.parseInt(args[3].replace("x", "")));
				} catch (NumberFormatException e) {
					ChatUtil.info(usage);
					return;
				}
				Spawner old = at(looking);
				if (old != null) {
					spawners.remove(old);
					restore(looking);
				}
				// As if it's been going an hour, so there's loot in it straight away
				spawners.add(new Spawner(looking, mob, stacks, System.currentTimeMillis() - 3_600_000L));
				save();
				if (!isEnabled()) setEnabled(true);
				ChatUtil.info("Placed a " + name(mob) + " spawner x" + stacks + " (only you see it)");
			}
			case "stack", "stacks" -> {
				Spawner s = looking == null ? null : at(looking);
				if (s == null || args.length < 3) {
					ChatUtil.info(s == null ? "Look at one of your fake spawners" : usage);
					return;
				}
				try {
					produce(s);
					s.stacks = Math.max(1, Integer.parseInt(args[2].replace("x", "")));
				} catch (NumberFormatException e) {
					ChatUtil.info(usage);
					return;
				}
				save();
				ChatUtil.info("Now x" + s.stacks);
			}
			case "remove", "delete" -> {
				Spawner s = looking == null ? null : at(looking);
				if (s == null) {
					ChatUtil.info("Look at one of your fake spawners");
					return;
				}
				spawners.remove(s);
				restore(s.pos);
				save();
				ChatUtil.info("Removed");
			}
			case "list" -> {
				if (spawners.isEmpty()) ChatUtil.info("No fake spawners");
				for (Spawner s : spawners) ChatUtil.info(name(s.mob) + " x" + s.stacks + " at " + s.pos.toShortString());
			}
			case "clear" -> {
				for (Spawner s : spawners) restore(s.pos);
				spawners.clear();
				save();
				ChatUtil.info("Cleared every fake spawner");
			}
			default -> ChatUtil.info(usage);
		}
	}
}
