package dev.femboyware.util;

import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * Whether a light leak is most likely the world's own light rather than someone's base. Anti-xray
 * shows hidden blocks as stone but the server still sends their real light, so lava pockets, lit
 * redstone ore, glow berries, trial chamber spawners and vaults, ancient city soul lanterns and
 * stronghold torches all look like hidden light. This finds the brightness at the source (by
 * climbing the light up to its peak, which is the emitter's own level) and what's around it.
 * femboyware addition to the ported light leak scanner.
 */
final class NaturalLight {
	private static final int LAVA = 1, LUSH = 2, TRIAL = 4, ANCIENT = 8, STRONGHOLD = 16, GEODE = 32, NETHER_PORTAL = 64;

	private NaturalLight() {
	}

	/** Why the light at {@code pos} is natural, or null if nothing natural explains it. */
	static String explain(ClientLevel level, LayerLightEventListener light, BlockPos pos, Map<Long, Integer> flagCache) {
		// Climb to the brightest spot: that's where the source is, and its light is the source's own level
		BlockPos peak = pos;
		int value = light.getLightValue(pos);
		for (int step = 0; step < 16 && value < 15; step++) {
			BlockPos best = null;
			int bestValue = value;
			for (BlockPos next : new BlockPos[]{peak.above(), peak.below(), peak.north(), peak.south(), peak.east(), peak.west()}) {
				int v = light.getLightValue(next);
				if (v > bestValue) {
					best = next;
					bestValue = v;
				}
			}
			if (best == null) break;
			peak = best;
			value = bestValue;
		}

		// Nothing a player lights a base with gives 9 or less except redstone torches; lit redstone ore (9),
		// trial spawners (8), sculk catalysts (6), amethyst buds and magma all do
		if (value <= 9) return "dim natural source (" + value + ")";

		int flags = 0;
		int sx = peak.getX() >> 4, sy = peak.getY() >> 4, sz = peak.getZ() >> 4;
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++)
					flags |= flagCache.computeIfAbsent(SectionPos.asLong(sx + dx, sy + dy, sz + dz), key -> flagsIn(level, key));

		// Structures that come lit: vaults (12), copper bulbs, soul lanterns (10), stronghold torches (14)
		if ((flags & TRIAL) != 0) return "trial chamber";
		if ((flags & ANCIENT) != 0) return "ancient city";
		if (value == 14 && (flags & STRONGHOLD) != 0) return "stronghold torch";
		if ((value == 10 || value == 11) && (flags & NETHER_PORTAL) != 0) return "ruined portal";
		// Glow berries in lush caves give 14
		if (value == 14 && (flags & LUSH) != 0) return "glow berries";
		if (value == 15) {
			if ((flags & LAVA) != 0) return "lava nearby";
			// Lava lakes sit at y -55 under the deepslate caves
			if (peak.getY() <= -50) return "lava lake level";
			// A pool: several blocks at full light side by side on one layer
			int full = 0;
			for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++)
					if (light.getLightValue(peak.offset(dx, 0, dz)) == 15) full++;
			if (full >= 4) return "lava pool";
		}
		return null;
	}

	private static int flagsIn(ClientLevel level, long key) {
		LevelChunk chunk = level.getChunkSource().getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false);
		if (chunk == null) return 0;
		int index = chunk.getSectionIndexFromSectionY(SectionPos.y(key));
		if (index < 0 || index >= chunk.getSections().length) return 0;
		LevelChunkSection section = chunk.getSection(index);
		if (section.hasOnlyAir()) return 0;
		int[] flags = {0};
		// Cheap first: skip sections with none of the telling blocks
		if (!section.maybeHas(state -> flag(state) != 0)) return 0;
		for (int y = 0; y < 16; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++) flags[0] |= flag(section.getBlockState(x, y, z));
		return flags[0];
	}

	private static final Map<net.minecraft.world.level.block.Block, Integer> FLAGS = new java.util.concurrent.ConcurrentHashMap<>();

	private static int flag(BlockState state) {
		return FLAGS.computeIfAbsent(state.getBlock(), block -> {
			String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
			if (id.equals("lava") || id.equals("magma_block") || id.equals("obsidian")) return LAVA;
			if (id.equals("moss_block") || id.equals("moss_carpet") || id.contains("azalea") || id.startsWith("cave_vines") || id.equals("spore_blossom")
				|| id.contains("dripleaf") || id.equals("rooted_dirt") || id.equals("hanging_roots")) return LUSH;
			if (id.contains("tuff_brick") || id.equals("chiseled_tuff") || id.equals("polished_tuff") || id.equals("trial_spawner") || id.equals("vault")
				|| id.contains("copper_bulb") || id.contains("copper_grate") || id.equals("heavy_core") || id.contains("chiseled_copper")) return TRIAL;
			// Not deepslate bricks or tiles: people build bases out of those
			if (id.equals("reinforced_deepslate") || id.startsWith("sculk")) return ANCIENT;
			if (id.equals("end_portal_frame") || id.equals("mossy_stone_bricks") || id.equals("cracked_stone_bricks") || id.startsWith("infested_")
				&& id.contains("brick")) return STRONGHOLD;
			if (id.equals("amethyst_block") || id.equals("budding_amethyst") || id.equals("calcite")) return GEODE;
			if (id.equals("crying_obsidian") || id.equals("nether_portal")) return NETHER_PORTAL;
			return 0;
		});
	}
}
