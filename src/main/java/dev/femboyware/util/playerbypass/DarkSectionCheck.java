package dev.femboyware.util.playerbypass;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
final class DarkSectionCheck {
	private static final int[][] SIDES = new int[][]{{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};

	private DarkSectionCheck() {
	}

	static boolean airlessAround(ClientLevel level, int chunkX, int sectionY, int chunkZ) {
		if (!airless(level, chunkX, sectionY, chunkZ)) {
			return false;
		}

		for (int[] side : SIDES) {
			if (!airless(level, chunkX + side[0], sectionY + side[1], chunkZ + side[2])) {
				return false;
			}
		}

		return true;
	}

	private static boolean airless(ClientLevel level, int chunkX, int sectionY, int chunkZ) {
		if (sectionY >= level.getMinSectionY() && sectionY <= level.getMaxSectionY()) {
			LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (chunk == null) {
				return false;
			}

			LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
			if (section.hasOnlyAir()) {
				return false;
			}

			if (!section.getStates().maybeHas(BlockStateBase::isAir)) {
				return true;
			}

			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (section.getBlockState(x, y, z).isAir()) {
							return false;
						}
					}
				}
			}

			return true;
		} else {
			return true;
		}
	}
}
