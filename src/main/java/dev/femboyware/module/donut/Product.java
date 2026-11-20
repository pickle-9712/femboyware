package dev.femboyware.module.donut;

import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
record Product(
	Item item,
	String id,
	String search,
	String displayName,
	long minPrice,
	List<Product.Enchant> enchants,
	boolean restock,
	int orderAmount,
	boolean beatTopOrder,
	long fixedPrice,
	long bidIncrement,
	long maxPrice,
	Predicate<ItemStack> filter
) {
	Product(
		Item item,
		String id,
		String search,
		String displayName,
		long minPrice,
		List<Product.Enchant> enchants,
		boolean restock,
		int orderAmount,
		boolean beatTopOrder,
		long fixedPrice,
		long bidIncrement,
		long maxPrice
	) {
		this(item, id, search, displayName, minPrice, enchants, restock, orderAmount, beatTopOrder, fixedPrice, bidIncrement, maxPrice, null);
	}

	static Item resolveItem(String raw) {
		String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (text.isEmpty()) {
			return null;
		}

		if (!text.contains(":")) {
			text = "minecraft:" + text;
		}

		Identifier id = Identifier.tryParse(text);
		if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
			Item item = (Item)BuiltInRegistries.ITEM.getValue(id);
			return item == Items.AIR ? null : item;
		} else {
			return null;
		}
	}

	static String searchText(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
	}

	static List<Product.Enchant> parseEnchants(String raw) {
		List<Product.Enchant> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}

		for (String part : raw.split("[,;]")) {
			String text = part.trim();
			if (!text.isEmpty()) {
				int level = 1;
				int space = text.lastIndexOf(32);
				if (space > 0) {
					String last = text.substring(space + 1).trim();
					int parsed = parseLevel(last);
					if (parsed > 0) {
						level = parsed;
						text = text.substring(0, space).trim();
					}
				}

				String key = ServerText.key(text);
				if (!key.isEmpty()) {
					out.add(new Product.Enchant(key, text, level));
				}
			}
		}

		return out;
	}

	private static int parseLevel(String raw) {
		try {
			int value = Integer.parseInt(raw);
			return value >= 1 && value <= 10 ? value : 0;
		} catch (NumberFormatException e) {
			return ServerText.roman(raw);
		}
	}

	boolean matches(ItemStack stack) {
		if (!stack.isEmpty() && stack.is(this.item)) {
			if (this.filter != null) {
				return this.filter.test(stack);
			}

			if (this.enchants.isEmpty()) {
				return true;
			}

			ItemEnchantments present = EnchantmentHelper.getEnchantmentsForCrafting(stack);

			for (Product.Enchant wanted : this.enchants) {
				if (levelOf(present, wanted.key()) < wanted.level()) {
					return false;
				}
			}

			return true;
		} else {
			return false;
		}
	}

	static boolean enchantsMatch(ItemStack stack, List<Product.Enchant> wanted, boolean exact) {
		ItemEnchantments present = EnchantmentHelper.getEnchantmentsForCrafting(stack);
		if (exact && present.size() != wanted.size()) {
			return false;
		}

		for (Product.Enchant enchant : wanted) {
			int level = levelOf(present, enchant.key());
			if (exact ? level != enchant.level() : level < enchant.level()) {
				return false;
			}
		}

		return true;
	}

	private static int levelOf(ItemEnchantments present, String key) {
		for (Entry<Holder<Enchantment>> entry : present.entrySet()) {
			String path = entry.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
			if (ServerText.key(path).equals(key)) {
				return entry.getIntValue();
			}
		}

		return 0;
	}

	boolean nameMatches(String label, boolean loose) {
		String key = ServerText.key(label);
		String byId = ServerText.key(BuiltInRegistries.ITEM.getKey(this.item).getPath());
		String byName = ServerText.key(this.displayName);
		return !key.equals(byId) && !key.equals(byName) ? loose && (key.endsWith(byId) || !byName.isEmpty() && key.endsWith(byName)) : true;
	}

	String describe() {
		StringBuilder text = new StringBuilder(this.displayName);
		if (!this.enchants.isEmpty()) {
			List<String> names = new ArrayList<>();

			for (Product.Enchant enchant : this.enchants) {
				names.add(enchant.label());
			}

			text.append(" (").append(String.join(", ", names)).append(')');
		}

		return text.toString();
	}
	record Enchant(String key, String name, int level) {
		String label() {
			return this.name + (this.level > 1 ? " " + this.level : "");
		}
	}
}
