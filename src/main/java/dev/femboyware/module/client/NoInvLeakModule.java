package dev.femboyware.module.client;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ChoiceSetting;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hides your items on screen for screen shares and recordings: your hotbar, inventory, held-item
 * name, tooltips and your inventory model show a totem, a believable random loadout, or nothing.
 * Ported from the Anubis client's No Inv Leak (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client), without its Windows-only Stream Only overlay.
 */
public class NoInvLeakModule extends Module {
	private static final int UNKNOWN = 41;

	private final ChoiceSetting style = add(new ChoiceSetting("Style", "What your items show as", "Totem", () -> List.of("Totem", "Random", "Empty")));
	private final BoolSetting fillEmpty = add(new BoolSetting("Fill Empty Slots", "Empty slots show something too", true));
	private final BoolSetting tooltips = add(new BoolSetting("Hide Tooltips", "No tooltips on your own items", true));
	private final BoolSetting itemName = add(new BoolSetting("Hide Item Name", "No held-item name above the hotbar", true));
	private final BoolSetting selectedSlot = add(new BoolSetting("Hide Selected Slot", "No hotbar selection box", true));

	private static boolean bareModel;
	private final ItemStack[] loadout = new ItemStack[42];
	private long seed;
	private ItemStack totem;

	public NoInvLeakModule() {
		super("No Inv Leak", "Hides your items on screen for screen shares", Category.MISC);
		fillEmpty.visibleWhen(() -> !style.get().equals("Empty"));
	}

	private static NoInvLeakModule active() {
		if (FemboyWare.getModules() == null) return null;
		NoInvLeakModule module = FemboyWare.getModules().get(NoInvLeakModule.class);
		return module != null && module.isEnabled() ? module : null;
	}

	@Override
	protected void onEnable() {
		seed = ThreadLocalRandom.current().nextLong();
		Arrays.fill(loadout, null);
	}

	/** Hotbar slot {@code seed} (1-9, 10 for the offhand) as it should be drawn. */
	public static ItemStack hotbar(ItemStack stack, int hotbarSeed) {
		NoInvLeakModule module = active();
		if (module == null || stack == null) return stack;
		int slot = hotbarSeed == 10 ? 40 : hotbarSeed >= 1 && hotbarSeed <= 9 ? hotbarSeed - 1 : UNKNOWN;
		return module.shown(stack, slot);
	}

	public static ItemStack container(ItemStack stack, Slot slot) {
		NoInvLeakModule module = active();
		if (module == null || stack == null || !ownSlot(slot)) return stack;
		return module.shown(stack, slot.getContainerSlot());
	}

	public static ItemStack dragged(ItemStack stack) {
		NoInvLeakModule module = active();
		return module == null || stack == null || stack.isEmpty() ? stack : module.fake(UNKNOWN);
	}

	public static ItemStack carried(ItemStack stack) {
		NoInvLeakModule module = active();
		return module == null || stack == null || stack.isEmpty() ? stack : module.fake(UNKNOWN);
	}

	public static boolean hidesModelOf(LivingEntity entity) {
		return active() != null && entity != null && entity == mc.player;
	}

	public static void bareModel(boolean on) {
		bareModel = on;
	}

	public static ItemStack modelItem(LivingEntity entity, ItemStack stack) {
		return bareModel && stack != null && !stack.isEmpty() && entity == mc.player ? ItemStack.EMPTY : stack;
	}

	public static boolean ownSlot(Slot slot) {
		return active() != null && slot != null && slot.container instanceof Inventory;
	}

	public static boolean hidesTooltips() {
		NoInvLeakModule module = active();
		return module != null && module.tooltips.get();
	}

	public static boolean hidesItemName() {
		NoInvLeakModule module = active();
		return module != null && module.itemName.get();
	}

	public static boolean hidesSelectedSlot() {
		NoInvLeakModule module = active();
		return module != null && module.selectedSlot.get();
	}

	private ItemStack shown(ItemStack stack, int slot) {
		return stack.isEmpty() && (!fillEmpty.get() || style.get().equals("Empty")) ? stack : fake(slot);
	}

	private ItemStack fake(int slot) {
		if (style.get().equals("Empty")) return ItemStack.EMPTY;
		if (style.get().equals("Random")) {
			if (slot < 0 || slot > UNKNOWN) slot = UNKNOWN;
			if (loadout[slot] == null) loadout[slot] = roll(slot);
			return loadout[slot];
		}
		if (totem == null) totem = new ItemStack(Items.TOTEM_OF_UNDYING);
		return totem;
	}

	// The random loadout: ordinary survival stuff, the same for a slot until you turn it back on

	private static final Item[] HOTBAR = {Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE,
		Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL, Items.BOW, Items.WATER_BUCKET,
		Items.COBBLESTONE, Items.TORCH, Items.COOKED_BEEF, Items.GOLDEN_CARROT, Items.BREAD, Items.ENDER_PEARL, Items.OAK_PLANKS, Items.DIRT, Items.FLINT_AND_STEEL};
	private static final Item[] MAIN = {Items.COBBLESTONE, Items.STONE, Items.DIRT, Items.OAK_LOG, Items.OAK_PLANKS, Items.STICK, Items.COAL, Items.IRON_INGOT, Items.GOLD_INGOT,
		Items.DIAMOND, Items.REDSTONE, Items.LAPIS_LAZULI, Items.ARROW, Items.STRING, Items.BONE, Items.ROTTEN_FLESH, Items.GUNPOWDER, Items.SAND, Items.GRAVEL,
		Items.WHEAT_SEEDS, Items.WHEAT, Items.LADDER, Items.OBSIDIAN, Items.GLASS, Items.LEATHER, Items.FEATHER, Items.FLINT, Items.SPIDER_EYE, Items.APPLE,
		Items.COOKED_PORKCHOP, Items.BUCKET, Items.BOOK, Items.PAPER, Items.SUGAR_CANE, Items.CRAFTING_TABLE, Items.CHEST, Items.FURNACE};
	private static final Item[] OFFHAND = {Items.SHIELD, Items.TORCH, Items.WATER_BUCKET, Items.ARROW, Items.COOKED_BEEF, Items.GOLDEN_APPLE};
	private static final Item[][] ARMOR = {
		{Items.IRON_BOOTS, Items.CHAINMAIL_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS},
		{Items.IRON_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS},
		{Items.IRON_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE},
		{Items.IRON_HELMET, Items.CHAINMAIL_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET}};

	private ItemStack roll(int slot) {
		Item item;
		if (slot < 9) item = pick(HOTBAR, slot);
		else if (slot < 36 || slot == UNKNOWN) item = pick(MAIN, slot);
		else if (slot == 40) item = pick(OFFHAND, slot);
		else item = ARMOR[slot - 36][(int) Long.remainderUnsigned(mix(seed ^ 167L), ARMOR[0].length)];
		ItemStack stack = new ItemStack(item);
		if (stack.getMaxStackSize() > 1) stack.setCount(1 + (int) Long.remainderUnsigned(mix(seed ^ slot ^ 81L), stack.getMaxStackSize()));
		else if (stack.isDamageableItem()) stack.setDamageValue((int) Long.remainderUnsigned(mix(seed ^ slot ^ 82L), stack.getMaxDamage() / 2));
		return stack;
	}

	private Item pick(Item[] pool, int slot) {
		return pool[(int) Long.remainderUnsigned(mix(seed ^ slot * -7046029254386353131L), pool.length)];
	}

	private static long mix(long z) {
		z = (z ^ z >>> 30) * -4658895280553007687L;
		z = (z ^ z >>> 27) * -7723592293110705685L;
		return z ^ z >>> 31;
	}
}
