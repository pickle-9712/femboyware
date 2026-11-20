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
	private int minLight = 8;

	/** Leaks dimmer than this are ignored: natural glowing blocks (glow lichen gives 7) can hide behind anti-xray too. */
	public void minLight(int value) {
		this.minLight = value;
	}

	private boolean naturalFilter = true;

	/** Skip leaks that lava, lit ores, glow berries or a lit structure (trial chamber, ancient city...) explain. */
	public void naturalFilter(boolean value) {
		this.naturalFilter = value;
	}

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
				scan(level, ChunkPos.getX(key), ChunkPos.getZ(key), this.minLight, this.naturalFilter);
			}

		}
	}

	private static void scan(ClientLevel level, int chunkX, int chunkZ, int minLight, boolean naturalFilter) {
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

			// Light that a block you can see (lava, magma, glow lichen, torches...) would give off isn't a hidden block
			java.util.Map<Long, LongArrayList> emitters = new java.util.HashMap<>();
			java.util.Map<Long, Integer> natural = new java.util.HashMap<>();
			for (int i = 0; i < found.size(); i++) {
				BlockPos pos = BlockPos.of(found.getLong(i));
				int needed = needed(level, light, pos);
				if (needed < minLight || explained(level, pos, needed, emitters)) continue;
				if (naturalFilter && NaturalLight.explain(level, light, pos, natural) != null) continue;
				BlockEntityLeakTracker.hint(pos, Blocks.AIR, BlockEntityLeakTracker.Source.LIGHT);
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

	/** The light a visible source would have to reach here: the brightest of the block and its neighbours. */
	private static int needed(ClientLevel level, LayerLightEventListener light, BlockPos pos) {
		int best = light.getLightValue(pos);
		for (BlockPos next : new BlockPos[]{pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west()}) {
			best = Math.max(best, light.getLightValue(next) - 1);
		}
		return best;
	}

	/** Whether a light source you can see, within reach, could light {@code pos} to {@code needed}. */
	private static boolean explained(ClientLevel level, BlockPos pos, int needed, java.util.Map<Long, LongArrayList> cache) {
		if (needed <= 0) return true;
		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++) {
					LongArrayList list = cache.computeIfAbsent(SectionPos.asLong(sx + dx, sy + dy, sz + dz), key -> emittersIn(level, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key)));
					for (int i = 0; i < list.size(); i += 2) {
						int emission = (int) list.getLong(i + 1);
						BlockPos at = BlockPos.of(list.getLong(i));
						int distance = Math.abs(at.getX() - pos.getX()) + Math.abs(at.getY() - pos.getY()) + Math.abs(at.getZ() - pos.getZ());
						if (emission - distance >= needed) return true;
					}
				}
		return false;
	}

	/** Every light-giving block the client sees in a section, as pairs: BlockPos.asLong, then its emission. */
	private static LongArrayList emittersIn(ClientLevel level, int sx, int sy, int sz) {
		LongArrayList out = new LongArrayList();
		LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
		if (chunk == null) return out;
		int index = chunk.getSectionIndexFromSectionY(sy);
		if (index < 0 || index >= chunk.getSections().length) return out;
		LevelChunkSection section = chunk.getSection(index);
		if (section.hasOnlyAir() || !section.maybeHas(state -> state.getLightEmission() > 0)) return out;
		for (int y = 0; y < 16; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++) {
					int emission = section.getBlockState(x, y, z).getLightEmission();
					if (emission > 0) {
						out.add(BlockPos.asLong((sx << 4) + x, (sy << 4) + y, (sz << 4) + z));
						out.add(emission);
					}
				}
		return out;
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
