package dev.femboyware.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Turns a setting like {@code "crafting_table, #beds"} into a block test. */
public final class BlockMatcher {
	private BlockMatcher() {
	}

	/** Comma separated block ids and #tags into one test. Unknown ids are skipped. */
	public static Predicate<BlockState> parse(String text) {
		Set<Block> blocks = new HashSet<>();
		List<TagKey<Block>> tags = new ArrayList<>();
		for (String part : text.split(",")) {
			String entry = part.trim().toLowerCase();
			if (entry.isEmpty()) continue;
			boolean tag = entry.startsWith("#");
			Identifier id = Identifier.tryParse(tag ? entry.substring(1) : entry);
			if (id == null) continue;
			if (tag) tags.add(TagKey.of(RegistryKeys.BLOCK, id));
			else Registries.BLOCK.getOptionalValue(id).ifPresent(blocks::add);
		}
		return state -> {
			if (blocks.contains(state.getBlock())) return true;
			for (TagKey<Block> tag : tags) {
				if (state.isIn(tag)) return true;
			}
			return false;
		};
	}
}
