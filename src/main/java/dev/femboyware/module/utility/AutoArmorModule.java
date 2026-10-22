package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.InventoryUtil;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Set;

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

		private final RegistryKey<Enchantment> enchantment;

		Protection(RegistryKey<Enchantment> enchantment) {
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
		if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;
		if (!avoidedEnchantments.get().equals(parsedFrom)) {
			avoided = parseIds(avoidedEnchantments.get());
			parsedFrom = avoidedEnchantments.get();
		}

		for (EquipmentSlot slot : SLOTS) {
			ItemStack current = mc.player.getEquippedStack(slot);
			if (ignoreElytra.get() && current.contains(DataComponentTypes.GLIDER)) continue;
			if (hasEnchantment(current, Enchantments.BINDING_CURSE)) continue;

			int currentScore = current.isEmpty() ? -1 : score(current, slot) - 2 * avoidedLevels(current);
			if (aboutToBreak(current)) currentScore = -1;

			int bestSlot = -1;
			int bestScore = currentScore;
			for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
				ItemStack stack = mc.player.getInventory().getStack(i);
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
		if (stack.contains(DataComponentTypes.GLIDER)) return EquipmentSlot.CHEST;
		if (!stack.isIn(ItemTags.HEAD_ARMOR) && !stack.isIn(ItemTags.CHEST_ARMOR) && !stack.isIn(ItemTags.LEG_ARMOR) && !stack.isIn(ItemTags.FOOT_ARMOR)) return null;
		EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
		return equippable == null ? null : equippable.slot();
	}

	/** Enchantments, protection and toughness, weighted towards the preferred protection. */
	private int score(ItemStack stack, EquipmentSlot slot) {
		RegistryKey<Enchantment> preferred = blastLeggings.get() && slot == EquipmentSlot.LEGS ? Enchantments.BLAST_PROTECTION : preferredProtection.get().enchantment;
		int score = 3 * InventoryUtil.getEnchantmentLevel(stack, preferred);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.PROTECTION);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.BLAST_PROTECTION);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.FIRE_PROTECTION);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.PROJECTILE_PROTECTION);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.UNBREAKING);
		score += 2 * InventoryUtil.getEnchantmentLevel(stack, Enchantments.MENDING);

		AttributeModifiersComponent modifiers = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
		if (modifiers == null) return score;
		for (AttributeModifiersComponent.Entry entry : modifiers.modifiers()) {
			if (!entry.attribute().equals(EntityAttributes.ARMOR) && !entry.attribute().equals(EntityAttributes.ARMOR_TOUGHNESS)) continue;
			double value = entry.modifier().value();
			score += (int) switch (entry.modifier().operation()) {
				case ADD_VALUE -> value;
				case ADD_MULTIPLIED_BASE -> value * mc.player.getAttributeBaseValue(entry.attribute());
				case ADD_MULTIPLIED_TOTAL -> value * score;
			};
		}
		return score;
	}

	private boolean aboutToBreak(ItemStack stack) {
		return antiBreak.get() && stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= BREAK_DURABILITY;
	}

	private int avoidedLevels(ItemStack stack) {
		int total = 0;
		for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : stack.getEnchantments().getEnchantmentEntries()) {
			for (Identifier id : avoided) {
				if (entry.getKey().matchesId(id)) total += entry.getIntValue();
			}
		}
		return total;
	}

	private static boolean hasEnchantment(ItemStack stack, RegistryKey<Enchantment> enchantment) {
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
