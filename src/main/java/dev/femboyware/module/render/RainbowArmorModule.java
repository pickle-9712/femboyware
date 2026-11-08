package dev.femboyware.module.render;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.AutoArmorModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Cycles through the dyed leather armour in your inventory: in rainbow order by dye colour, or at
 * random, the whole set at once or one piece at a time.
 */
public class RainbowArmorModule extends Module {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/** The colour undyed leather armour shows. */
	private static final int UNDYED_LEATHER = 0xA06540;

	public enum Order { RAINBOW, RANDOM }
	public enum Pieces { WHOLE_SET, ONE_AT_A_TIME }

	/** A leather piece and the hue of its dye, 0 to 1 around the colour wheel. */
	private record Piece(int inventorySlot, float hue) {}

	private final EnumSetting<Order> order = add(new EnumSetting<>("Order", "Rainbow steps through dye colours in hue order, Random picks any", Order.RAINBOW));
	private final EnumSetting<Pieces> pieces = add(new EnumSetting<>("Pieces", "Change the whole set together, or one piece per step", Pieces.WHOLE_SET));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between changes", 500, 50, 5000, 50));
	private final BoolSetting randomDelay = add(new BoolSetting("Random Delay", "Wait a random time up to twice the delay instead", false));

	private final Random random = new Random();
	private long nextChange;
	/** Next armour slot to change in One At A Time mode. */
	private int slotIndex;
	private boolean warnedAutoArmor;

	public RainbowArmorModule() {
		super("Rainbow Armor", "Cycles your dyed leather armour through the rainbow", Category.COSMETIC);
	}

	@Override
	protected void onEnable() {
		nextChange = 0;
		warnedAutoArmor = false;
	}

	@Override
	public void onTick() {
		if (FemboyWare.isEnabled(AutoArmorModule.class)) {
			// Auto Armor would swap the best armour straight back on every tick
			if (!warnedAutoArmor) ChatUtil.info("Rainbow Armor is paused while Auto Armor is on");
			warnedAutoArmor = true;
			return;
		}
		warnedAutoArmor = false;
		long now = System.currentTimeMillis();
		if (now < nextChange) return;
		// Clicks go to the player inventory, which would desync any other open container
		if (mc.player.containerMenu != mc.player.inventoryMenu) return;

		if (pieces.is(Pieces.WHOLE_SET)) {
			for (EquipmentSlot slot : SLOTS) change(slot);
		} else {
			// The next slot that has another piece to change to
			for (int i = 0; i < SLOTS.length; i++) {
				EquipmentSlot slot = SLOTS[(slotIndex + i) % SLOTS.length];
				if (change(slot)) {
					slotIndex = (slotIndex + i + 1) % SLOTS.length;
					break;
				}
			}
		}
		long wait = delay.getInt();
		if (randomDelay.get()) wait = (long) (random.nextDouble() * wait * 2);
		nextChange = now + Math.max(50, wait);
	}

	/** Swaps in the next piece for {@code slot}. Returns false when there's nothing else to wear there. */
	private boolean change(EquipmentSlot slot) {
		List<Piece> options = piecesFor(slot);
		if (options.isEmpty()) return false;
		ItemStack worn = mc.player.getItemBySlot(slot);
		Piece next;
		if (order.is(Order.RANDOM) || !isLeather(worn)) {
			next = options.get(random.nextInt(options.size()));
		} else {
			// The first piece further round the colour wheel than what's worn, wrapping to the start
			float hue = hue(worn);
			next = options.stream().filter(piece -> piece.hue > hue).findFirst().orElse(options.getFirst());
		}
		InventoryUtil.swapSlots(InventoryUtil.screenSlot(next.inventorySlot), InventoryUtil.armorScreenSlot(slot));
		return true;
	}

	/** Leather pieces for {@code slot} in the inventory, by hue. */
	private List<Piece> piecesFor(EquipmentSlot slot) {
		List<Piece> found = new ArrayList<>();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (isLeather(stack) && slotOf(stack) == slot) found.add(new Piece(i, hue(stack)));
		}
		found.sort(Comparator.comparingDouble(Piece::hue));
		return found;
	}

	private static boolean isLeather(ItemStack stack) {
		return stack.is(Items.LEATHER_HELMET) || stack.is(Items.LEATHER_CHESTPLATE)
			|| stack.is(Items.LEATHER_LEGGINGS) || stack.is(Items.LEATHER_BOOTS);
	}

	private static EquipmentSlot slotOf(ItemStack stack) {
		if (stack.is(Items.LEATHER_HELMET)) return EquipmentSlot.HEAD;
		if (stack.is(Items.LEATHER_CHESTPLATE)) return EquipmentSlot.CHEST;
		if (stack.is(Items.LEATHER_LEGGINGS)) return EquipmentSlot.LEGS;
		return EquipmentSlot.FEET;
	}

	private static float hue(ItemStack stack) {
		DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
		int rgb = dye == null ? UNDYED_LEATHER : dye.rgb();
		float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float range = max - Math.min(r, Math.min(g, b));
		if (range == 0) return 0;
		float hue = max == r ? (g - b) / range : max == g ? 2 + (b - r) / range : 4 + (r - g) / range;
		return ((hue / 6f) % 1f + 1f) % 1f;
	}
}
