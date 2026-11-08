package dev.femboyware.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Scans loaded chunks around the player a few at a time and keeps one result per chunk, so ESPs
 * that look at whole chunks don't freeze the game. Chunks are rescanned now and then to pick up
 * changes, and everything is dropped on a dimension change.
 */
public final class ChunkScanner<T> {
	private static final Minecraft mc = Minecraft.getInstance();

	private final Function<LevelChunk, T> scan;
	private final Map<ChunkPos, T> results = new HashMap<>();
	private final Map<ChunkPos, Long> scannedAt = new HashMap<>();
	private ResourceKey<Level> dimension;

	/** {@code scan} returns null for chunks with nothing of interest. */
	public ChunkScanner(Function<LevelChunk, T> scan) {
		this.scan = scan;
	}

	public void tick(int radius, int chunksPerTick, long rescanMillis) {
		if (mc.level.dimension() != dimension) {
			clear();
			dimension = mc.level.dimension();
		}
		ChunkPos center = mc.player.chunkPosition();
		results.keySet().removeIf(pos -> pos.getChessboardDistance(center) > radius);
		scannedAt.keySet().removeIf(pos -> pos.getChessboardDistance(center) > radius);

		// Stalest chunks first, nearest breaking ties
		long now = System.currentTimeMillis();
		List<ChunkPos> due = new ArrayList<>();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				ChunkPos pos = new ChunkPos(Mc.chunkX(center) + x, Mc.chunkZ(center) + z);
				if (!mc.level.getChunkSource().hasChunk(Mc.chunkX(pos), Mc.chunkZ(pos))) continue;
				if (now - scannedAt.getOrDefault(pos, 0L) >= rescanMillis) due.add(pos);
			}
		}
		due.sort(Comparator.<ChunkPos>comparingLong(pos -> scannedAt.getOrDefault(pos, 0L))
			.thenComparingInt(pos -> pos.getChessboardDistance(center)));
		for (int i = 0; i < Math.min(chunksPerTick, due.size()); i++) {
			ChunkPos pos = due.get(i);
			T result = scan.apply(mc.level.getChunk(Mc.chunkX(pos), Mc.chunkZ(pos)));
			if (result == null) results.remove(pos);
			else results.put(pos, result);
			scannedAt.put(pos, now);
		}
	}

	public Map<ChunkPos, T> results() {
		return Collections.unmodifiableMap(results);
	}

	public void clear() {
		results.clear();
		scannedAt.clear();
		dimension = null;
	}

	/**
	 * Calls {@code action} for every block in {@code chunk} matching {@code filter}. Sections whose
	 * palette holds no match are skipped without looking at their blocks.
	 */
	public static void forEachBlock(LevelChunk chunk, Predicate<BlockState> filter, BiConsumer<BlockPos, BlockState> action) {
		LevelChunkSection[] sections = chunk.getSections();
		int startX = chunk.getPos().getMinBlockX();
		int startZ = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(filter)) continue;
			int startY = mc.level.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (filter.test(state)) action.accept(new BlockPos(startX + x, startY + y, startZ + z), state);
					}
				}
			}
		}
	}
}
