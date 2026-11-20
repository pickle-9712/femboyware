package dev.femboyware.util.playerbypass;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tells anti-xray reveals apart from real block changes. Servers like DonutSMP show you hidden
 * blocks as stone or deepslate and swap in the real ones (andesite, dirt, ores, cave air, water) as
 * they come into view, which arrive as block changes even far outside your simulation distance.
 * femboyware addition to the ported Player Bypass.
 */
final class AntiXrayReveal {
	private static final java.util.Set<String> NATURAL_IDS = java.util.Set.of(
		"grass_block", "dirt", "coarse_dirt", "podzol", "mycelium", "rooted_dirt", "mud", "dirt_path", "moss_block", "pale_moss_block",
		"sand", "red_sand", "suspicious_sand", "suspicious_gravel", "gravel", "clay", "stone", "granite", "diorite", "andesite", "tuff",
		"deepslate", "cobbled_deepslate", "calcite", "dripstone_block", "sandstone", "red_sandstone", "snow_block", "ice", "packed_ice",
		"blue_ice", "powder_snow", "netherrack", "end_stone", "basalt", "blackstone", "soul_sand", "soul_soil", "magma_block");

	private AntiXrayReveal() {
	}

	/**
	 * @param batch the change came in a multi-block section update, the way reveals do; a single
	 *              block changing on its own (what mining looks like) still counts when it opens air
	 */
	static boolean is(BlockState previous, BlockState now, boolean batch) {
		// One natural block straight into another, or into water or lava, is never a player: placing needs
		// air first and breaking leaves air (gravel falling leaves air too). Covers stone -> grass_block on
		// the surface and gravel -> water on the sea floor
		if (natural(previous) && !tickable(previous) && (natural(now) || !now.getFluidState().isEmpty())) return true;
		// Stone straight into any block (a tree, a chest...) can't be a player either: they'd break it to air first
		if (filler(previous) && !now.isAir()) return true;
		if (filler(previous) && now.isAir()) return batch;
		// Fake ores (some anti-xray modes scatter them) turning back into air
		if (fakeOre(previous) && now.isAir()) return true;
		// Blocks hidden again
		return filler(now) && (natural(previous) || previous.isAir() || !previous.getFluidState().isEmpty()) && batch;
	}

	/** Natural blocks that do change by themselves near players (grass spreading, ice melting): those changes stay signals. */
	private static boolean tickable(BlockState state) {
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return TICKABLE_IDS.contains(id);
	}

	private static final java.util.Set<String> TICKABLE_IDS = java.util.Set.of(
		"dirt", "grass_block", "mycelium", "podzol", "ice", "powder_snow", "mud", "dirt_path", "magma_block");

	/** What anti-xray shows hidden blocks as. */
	private static boolean filler(BlockState state) {
		return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.NETHERRACK) || state.is(Blocks.END_STONE);
	}

	private static boolean fakeOre(BlockState state) {
		// By id, since the ore tags were renamed on 26.x: coal_ore, deepslate_iron_ore, nether_gold_ore...
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore");
	}

	/** Blocks that generate underground on their own; nobody's needed for one to be there. */
	private static boolean natural(BlockState state) {
		if (fakeOre(state) || filler(state)) return true;
		// By id too, since tags move between versions (grass_block left #dirt on some)
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (NATURAL_IDS.contains(id) || (id.endsWith("terracotta") && !id.contains("glazed"))) return true;
		if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
			|| state.is(BlockTags.TERRACOTTA)) return true;
		return state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.CALCITE)
			|| state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.SMOOTH_BASALT) || state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)
			|| state.is(Blocks.OBSIDIAN) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.BEDROCK) || state.is(Blocks.MOSSY_COBBLESTONE)
			|| state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.GLOW_LICHEN) || state.is(Blocks.RAW_IRON_BLOCK)
			|| state.is(Blocks.RAW_COPPER_BLOCK) || state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.NETHER_QUARTZ_ORE)
			|| state.is(Blocks.BASALT) || state.is(Blocks.BLACKSTONE) || state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL)
			|| state.is(Blocks.INFESTED_STONE) || state.is(Blocks.INFESTED_DEEPSLATE) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.PACKED_ICE)
			|| state.is(Blocks.ICE) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SCULK) || state.is(Blocks.SCULK_VEIN);
	}
}
