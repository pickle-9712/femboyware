package dev.femboyware.module.utility;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.combat.AuraModule;
import dev.femboyware.module.combat.AutoCrystalModule;
import dev.femboyware.module.combat.AutoGappleModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.InventoryUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Eats when your health or hunger drops low.
 * Ported from Meteor Client's AutoEat (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoEatModule extends Module {
	private static final int OFFHAND = -2;

	public enum Threshold { HEALTH, HUNGER, ANY, BOTH }
	public enum Priority { COMBINED, HUNGER, SATURATION }

	private final StringSetting blacklist = add(new StringSetting("Blacklist", "Comma separated item ids never to eat",
		"enchanted_golden_apple, golden_apple, chorus_fruit, poisonous_potato, pufferfish, chicken, rotten_flesh, spider_eye, suspicious_stew"));
	private final BoolSetting pauseAuras = add(new BoolSetting("Pause Auras", "Turn off Aura and Auto Crystal while eating", true));
	private final BoolSetting searchInventory = add(new BoolSetting("Search Inventory", "Also look for food outside the hotbar", false));
	private final EnumSetting<Priority> priority = add(new EnumSetting<>("Priority", "What to look for in food", Priority.SATURATION));
	private final EnumSetting<Threshold> threshold = add(new EnumSetting<>("Threshold", "Any eats on low health or hunger, Both needs both", Threshold.ANY));
	private final NumberSetting healthThreshold = add(new NumberSetting("Health", "Eat at or below this health", 10, 1, 19, 0.5)).visibleWhen(() -> !threshold.is(Threshold.HUNGER));
	private final NumberSetting hungerThreshold = add(new NumberSetting("Hunger", "Eat at or below this hunger", 16, 1, 19, 1)).visibleWhen(() -> !threshold.is(Threshold.HEALTH));

	private final List<Module> pausedAuras = new ArrayList<>();
	private Set<Item> blacklisted = Set.of();
	private String parsedFrom;
	private boolean eating;
	private int slot;
	private int previousSlot;

	public AutoEatModule() {
		super("Auto Eat", "Eats food when you're hungry or hurt", Category.AUTO);
	}

	@Override
	protected void onDisable() {
		if (eating && !nullCheck()) stopEating();
		eating = false;
	}

	public boolean isEating() {
		return eating;
	}

	@Override
	public void onTick() {
		if (!blacklist.get().equals(parsedFrom)) {
			blacklisted = parseItems(blacklist.get());
			parsedFrom = blacklist.get();
		}
		AutoGappleModule autoGapple = FemboyWare.getModules().get(AutoGappleModule.class);
		if (autoGapple.isEnabled() && autoGapple.isEating()) return;

		if (eating) {
			if (!shouldEat()) {
				stopEating();
				return;
			}
			if (!isEdible(stackIn(slot))) {
				int next = findSlot();
				if (next == -1 || !prepareSlot(next)) {
					stopEating();
					return;
				}
			}
			eat();
			return;
		}

		if (shouldEat() && Mc.screen() == null) {
			previousSlot = mc.player.getInventory().getSelectedSlot();
			if (!prepareSlot(findSlot())) return;
			eat();
			if (pauseAuras.get()) {
				pausedAuras.clear();
				for (Module module : List.of(FemboyWare.getModules().get(AuraModule.class), FemboyWare.getModules().get(AutoCrystalModule.class))) {
					if (!module.isEnabled()) continue;
					pausedAuras.add(module);
					module.setEnabled(false);
				}
			}
		}
	}

	private void eat() {
		mc.options.keyUse.setDown(true);
		// Use the item directly so looking at a chest or door doesn't open it instead
		if (!mc.player.isUsingItem()) mc.gameMode.useItem(mc.player, slot == OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		eating = true;
	}

	private void stopEating() {
		mc.options.keyUse.setDown(false);
		if (slot != OFFHAND && previousSlot != mc.player.getInventory().getSelectedSlot()) InventoryUtil.select(previousSlot);
		eating = false;
		for (Module module : pausedAuras) module.setEnabled(true);
		pausedAuras.clear();
	}

	/** Gets the food in {@code target} into the hand. Food outside the hotbar moves to an empty hotbar slot. */
	private boolean prepareSlot(int target) {
		if (target == -1) return false;
		if (target == OFFHAND) {
			slot = OFFHAND;
			return true;
		}
		if (target >= Inventory.SELECTION_SIZE) {
			int empty = InventoryUtil.findHotbar(ItemStack::isEmpty);
			if (empty == -1) return false;
			InventoryUtil.swapWithHotbar(target, empty);
			target = empty;
		}
		if (mc.player.getInventory().getSelectedSlot() != target) InventoryUtil.select(target);
		slot = target;
		return true;
	}

	private boolean shouldEat() {
		boolean healthLow = mc.player.getHealth() <= healthThreshold.get();
		boolean hungerLow = mc.player.getFoodData().getFoodLevel() <= hungerThreshold.get();
		boolean wanted = switch (threshold.get()) {
			case HEALTH -> healthLow;
			case HUNGER -> hungerLow;
			case ANY -> healthLow || hungerLow;
			case BOTH -> healthLow && hungerLow;
		};
		return wanted && findSlot() != -1;
	}

	/** Offhand first, then the best food in the hotbar, then the rest of the inventory if allowed. */
	private int findSlot() {
		if (isEdible(mc.player.getOffhandItem())) return OFFHAND;
		int best = findBestFood(0, Inventory.SELECTION_SIZE);
		if (best == -1 && searchInventory.get()) best = findBestFood(Inventory.SELECTION_SIZE, Inventory.INVENTORY_SIZE);
		return best;
	}

	private int findBestFood(int start, int end) {
		int best = -1;
		float bestValue = -1;
		for (int i = start; i < end; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (!isEdible(stack)) continue;
			FoodProperties food = stack.get(DataComponents.FOOD);
			float value = switch (priority.get()) {
				case COMBINED -> food.nutrition() + food.saturation();
				case HUNGER -> food.nutrition();
				case SATURATION -> food.saturation();
			};
			if (value > bestValue) {
				bestValue = value;
				best = i;
			}
		}
		return best;
	}

	private ItemStack stackIn(int slot) {
		return slot == OFFHAND ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
	}

	private boolean isEdible(ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null || blacklisted.contains(stack.getItem())) return false;
		return mc.player.getFoodData().needsFood() || food.canAlwaysEat();
	}

	private static Set<Item> parseItems(String text) {
		Set<Item> result = new HashSet<>();
		for (String part : text.split(",")) {
			if (part.isBlank()) continue;
			Identifier id = Identifier.tryParse(part.trim().toLowerCase());
			if (id != null) BuiltInRegistries.ITEM.getOptional(id).ifPresent(result::add);
		}
		return result;
	}
}
