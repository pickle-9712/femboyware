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
	private AntiXrayReveal() {
	}

	/**
	 * @param batch the change came in a multi-block section update, the way reveals do; a single
	 *              block changing on its own (what mining looks like) still counts when it opens air
	 */
	static boolean is(BlockState previous, BlockState now, boolean batch) {
		if (filler(previous)) {
			// Stone straight into water or lava is always a reveal: breaking stone gives air first
			if (!now.getFluidState().isEmpty()) return true;
			if (now.isAir()) return batch;
			return natural(now);
		}
		// Fake ores (some anti-xray modes scatter them) turning back into what's really there, and blocks hidden again
		if (fakeOre(previous) && (natural(now) || now.isAir())) return true;
		return filler(now) && (natural(previous) || previous.isAir() || !previous.getFluidState().isEmpty()) && batch;
	}

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
