package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.InventoryUtil;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Equips the best armour in your inventory.
 * Ported from Meteor Client's AutoArmor (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoArmorModule extends Module {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/** Durability left at which Anti Break counts a piece as about to break. */
	private static final int BREAK_DURABILITY = 10;

	public enum Protection {
		PROTECTION(Enchantments.PROTECTION),
		BLAST_PROTECTION(Enchantments.BLAST_PROTECTION),
		FIRE_PROTECTION(Enchantments.FIRE_PROTECTION),
		PROJECTILE_PROTECTION(Enchantments.PROJECTILE_PROTECTION);

		private final ResourceKey<Enchantment> enchantment;

		Protection(ResourceKey<Enchantment> enchantment) {
			this.enchantment = enchantment;
		}
	}

	private final EnumSetting<Protection> preferredProtection = add(new EnumSetting<>("Preferred Protection", "Which protection to rank highest", Protection.PROTECTION));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between equipping pieces", 1, 0, 5, 1));
	private final StringSetting avoidedEnchantments = add(new StringSetting("Avoided Enchantments", "Comma separated enchantment ids to avoid", "binding_curse, frost_walker"));
	private final BoolSetting blastLeggings = add(new BoolSetting("Blast Leggings", "Prefer Blast Protection on leggings regardless", true));
	private final BoolSetting antiBreak = add(new BoolSetting("Anti Break", "Take armour off when it's about to break", false));
	private final BoolSetting ignoreElytra = add(new BoolSetting("Ignore Elytra", "Don't replace an equipped elytra", true));

	private int timer;
	private Set<Identifier> avoided = Set.of();
	private String parsedFrom;

	public AutoArmorModule() {
		super("Auto Armor", "Equips the best armour you have", Category.EQUIP);
	}

	@Override
	protected void onEnable() {
		timer = 0;
	}

	@Override
	public void onTick() {
		if (timer > 0) {
			timer--;
			return;
		}
		// Clicks go to the player inventory, which would desync any other open container
		if (mc.player.containerMenu != mc.player.inventoryMenu) return;
		if (!avoidedEnchantments.get().equals(parsedFrom)) {
			avoided = parseIds(avoidedEnchantments.get());
			parsedFrom = avoidedEnchantments.get();
		}

		for (EquipmentSlot slot : SLOTS) {
			ItemStack current = mc.player.getItemBySlot(slot);
			if (ignoreElytra.get() && current.has(DataComponents.GLIDER)) continue;
			if (hasEnchantment(current, Enchantments.BINDING_CURSE)) continue;

			int currentScore = current.isEmpty() ? -1 : score(current, slot) - 2 * avoidedLevels(current);
			if (aboutToBreak(current)) currentScore = -1;

			int bestSlot = -1;
			int bestScore = currentScore;
			for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
				ItemStack stack = mc.player.getInventory().getItem(i);
				if (slotOf(stack) != slot || aboutToBreak(stack) || avoidedLevels(stack) > 0) continue;
				int score = score(stack, slot);
				if (score > bestScore) {
					bestScore = score;
					bestSlot = i;
				}
			}

			if (bestSlot != -1) {
				InventoryUtil.swapSlots(InventoryUtil.screenSlot(bestSlot), InventoryUtil.armorScreenSlot(slot));
				timer = delay.getInt();
				return;
			}
			if (aboutToBreak(current)) {
				int empty = InventoryUtil.findInventory(ItemStack::isEmpty);
				if (empty == -1) continue;
				InventoryUtil.swapSlots(InventoryUtil.armorScreenSlot(slot), InventoryUtil.screenSlot(empty));
				timer = delay.getInt();
				return;
			}
		}
	}

	private static EquipmentSlot slotOf(ItemStack stack) {
		if (stack.isEmpty()) return null;
		if (stack.has(DataComponents.GLIDER)) return EquipmentSlot.CHEST;
		if (!stack.is(ItemTags.HEAD_ARMOR) && !stack.is(ItemTags.CHEST_ARMOR) && !stack.is(ItemTags.LEG_ARMOR) && !stack.is(ItemTags.FOOT_ARMOR)) return null;
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable == null ? null : equippable.slot();
	}

	/** Enchantments, protection and toughness, weighted towards the preferred protection. */
	private int score(ItemStack stack, EquipmentSlot slot) {
		ResourceKey<Enchantment> preferred = blastLeggings.get() && slot == EquipmentSlot.LEGS ? Enchantments.BLAST_PROTECTION : preferredProtection.get().enchantment;
		int score = 3 * InventoryUtil.getEnchantmentLevel(stack, preferred);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.PROTECTION);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.BLAST_PROTECTION);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.FIRE_PROTECTION);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.PROJECTILE_PROTECTION);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.UNBREAKING);
		score += 2 * InventoryUtil.getEnchantmentLevel(stack, Enchantments.MENDING);

		ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (modifiers == null) return score;
		for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
			if (!entry.attribute().equals(Attributes.ARMOR) && !entry.attribute().equals(Attributes.ARMOR_TOUGHNESS)) continue;
			double value = entry.modifier().amount();
			score += (int) switch (entry.modifier().operation()) {
				case ADD_VALUE -> value;
				case ADD_MULTIPLIED_BASE -> value * mc.player.getAttributeBaseValue(entry.attribute());
				case ADD_MULTIPLIED_TOTAL -> value * score;
			};
		}
		return score;
	}

	private boolean aboutToBreak(ItemStack stack) {
		return antiBreak.get() && stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= BREAK_DURABILITY;
	}

	private int avoidedLevels(ItemStack stack) {
		int total = 0;
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
			for (Identifier id : avoided) {
				if (entry.getKey().is(id)) total += entry.getIntValue();
			}
		}
		return total;
	}

	private static boolean hasEnchantment(ItemStack stack, ResourceKey<Enchantment> enchantment) {
		return InventoryUtil.getEnchantmentLevel(stack, enchantment) > 0;
	}

	private static Set<Identifier> parseIds(String text) {
		Set<Identifier> result = new HashSet<>();
		for (String part : text.split(",")) {
			if (part.isBlank()) continue;
			Identifier id = Identifier.tryParse(part.trim().toLowerCase());
			if (id != null) result.add(id);
		}
		return result;
	}
}
