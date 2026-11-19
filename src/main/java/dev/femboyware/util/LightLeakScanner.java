package dev.femboyware.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class LightLeakScanner {
	private static final int MIN_INSIDE = 2;
	private static final int DARK_GAP = 3;
	private static final long BUDGET_NANOS = 1500000L;
	private static final int NOISY_CHUNK = 512;
	private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();

	public void clear() {
		this.queue.clear();
	}

	public void queueLoaded(ClientLevel level, BlockPos around, int radius) {
		int px = around.getX() >> 4;
		int pz = around.getZ() >> 4;
		LongArrayList found = new LongArrayList();

		for (int ring = 0; ring <= radius; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == ring && level.hasChunk(px + dx, pz + dz)) {
						found.add(chunkKey(px + dx, pz + dz));
					}
				}
			}
		}

		for (int i = 0; i < found.size(); i++) {
			this.queue.add(found.getLong(i));
		}
	}

	public void onPacketApplied(Packet<?> packet) {
		if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
			//? if >=26.3 {
			/*this.queue.add(chunkKey(chunk.x(), chunk.z()));
			*///?} else {
			this.queue.add(chunkKey(chunk.getX(), chunk.getZ()));
			//?}
		} else if (packet instanceof ClientboundLightUpdatePacket light) {
			//? if >=26.3 {
			/*this.queue.add(chunkKey(light.x(), light.z()));
			*///?} else {
			this.queue.add(chunkKey(light.getX(), light.getZ()));
			//?}
		}
	}

	public void tick(ClientLevel level) {
		if (!this.queue.isEmpty()) {
			long deadline = System.nanoTime() + BUDGET_NANOS;

			while (!this.queue.isEmpty() && System.nanoTime() < deadline) {
				long key = this.queue.removeFirstLong();
				scan(level, ChunkPos.getX(key), ChunkPos.getZ(key));
			}

		}
	}

	private static void scan(ClientLevel level, int chunkX, int chunkZ) {
		LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (chunk != null) {
			LevelChunkSection[] sections = chunk.getSections();
			LayerLightEventListener light = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
			LongArrayList found = new LongArrayList();

			for (int i = 0; i < sections.length; i++) {
				LevelChunkSection section = sections[i];
				if (!section.hasOnlyAir()) {
					int sectionY = chunk.getSectionYFromSectionIndex(i);
					DataLayer data = light.getDataLayerData(SectionPos.of(chunkX, sectionY, chunkZ));
					if (data != null && !data.isEmpty() && anyLight(data)) {
						scanSection(level, section, data, chunkX << 4, sectionY << 4, chunkZ << 4, found);
						if (found.size() > 512) {
							return;
						}
					}
				}
			}

			for (int i = 0; i < found.size(); i++) {
				BlockEntityLeakTracker.hint(BlockPos.of(found.getLong(i)), Blocks.AIR, BlockEntityLeakTracker.Source.LIGHT);
			}
		}
	}

	private static void scanSection(ClientLevel level, LevelChunkSection section, DataLayer data, int baseX, int baseY, int baseZ, LongArrayList found) {
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = section.getBlockState(x, y, z);
					int inside = data.get(x, y, z);
					if (lightBlock(state) >= 15) {
						if (inside >= 2 && inside > state.getLightEmission()) {
							found.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
						}
					} else if (state.isAir() && inside <= 12 && brightestInside(data, x, y, z) - inside >= 3) {
						BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
						if (!nextToGeode(level, pos)) {
							found.add(pos.asLong());
						}
					}
				}
			}
		}
	}

	private static int brightestInside(DataLayer data, int x, int y, int z) {
		int best = 0;
		if (x > 0) {
			best = Math.max(best, data.get(x - 1, y, z));
		}

		if (x < 15) {
			best = Math.max(best, data.get(x + 1, y, z));
		}

		if (y > 0) {
			best = Math.max(best, data.get(x, y - 1, z));
		}

		if (y < 15) {
			best = Math.max(best, data.get(x, y + 1, z));
		}

		if (z > 0) {
			best = Math.max(best, data.get(x, y, z - 1));
		}

		if (z < 15) {
			best = Math.max(best, data.get(x, y, z + 1));
		}

		return best;
	}

	private static boolean anyLight(DataLayer data) {
		byte[] raw = data.getData();

		for (byte value : raw) {
			if (value != 0) {
				return true;
			}
		}

		return false;
	}

	//? if >=26.1 {
	/*private static long chunkKey(int x, int z) {
		return ChunkPos.pack(x, z);
	}

	private static int lightBlock(BlockState state) {
		return state.getLightDampening();
	}
	*///?} else {
	private static long chunkKey(int x, int z) {
		return ChunkPos.asLong(x, z);
	}

	private static int lightBlock(BlockState state) {
		return state.getLightBlock();
	}
	//?}

	private static boolean nextToGeode(ClientLevel level, BlockPos pos) {
		for (BlockPos next : new BlockPos[]{pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west()}) {
			BlockState state = level.getBlockState(next);
			if (state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.AMETHYST_BLOCK)) {
				return true;
			}
		}

		return false;
	}
}
