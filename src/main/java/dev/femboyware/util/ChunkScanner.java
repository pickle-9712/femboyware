package dev.femboyware.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Scans loaded chunks around the player a few at a time and keeps one result per chunk, so ESPs
 * that look at whole chunks don't freeze the game. Chunks are rescanned now and then to pick up
 * changes, and everything is dropped on a dimension change.
 */
public final class ChunkScanner<T> {
	private static final MinecraftClient mc = MinecraftClient.getInstance();

	private final Function<WorldChunk, T> scan;
	private final Map<ChunkPos, T> results = new HashMap<>();
	private final Map<ChunkPos, Long> scannedAt = new HashMap<>();
	private RegistryKey<World> dimension;

	/** {@code scan} returns null for chunks with nothing of interest. */
	public ChunkScanner(Function<WorldChunk, T> scan) {
		this.scan = scan;
	}

	public void tick(int radius, int chunksPerTick, long rescanMillis) {
		if (mc.world.getRegistryKey() != dimension) {
			clear();
			dimension = mc.world.getRegistryKey();
		}
		ChunkPos center = mc.player.getChunkPos();
		results.keySet().removeIf(pos -> pos.getChebyshevDistance(center) > radius);
		scannedAt.keySet().removeIf(pos -> pos.getChebyshevDistance(center) > radius);

		// Stalest chunks first, nearest breaking ties
		long now = System.currentTimeMillis();
		List<ChunkPos> due = new ArrayList<>();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
				if (!mc.world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;
				if (now - scannedAt.getOrDefault(pos, 0L) >= rescanMillis) due.add(pos);
			}
		}
		due.sort(Comparator.<ChunkPos>comparingLong(pos -> scannedAt.getOrDefault(pos, 0L))
			.thenComparingInt(pos -> pos.getChebyshevDistance(center)));
		for (int i = 0; i < Math.min(chunksPerTick, due.size()); i++) {
			ChunkPos pos = due.get(i);
			T result = scan.apply(mc.world.getChunk(pos.x, pos.z));
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
	public static void forEachBlock(WorldChunk chunk, Predicate<BlockState> filter, BiConsumer<BlockPos, BlockState> action) {
		ChunkSection[] sections = chunk.getSectionArray();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		for (int i = 0; i < sections.length; i++) {
			ChunkSection section = sections[i];
			if (section.isEmpty() || !section.hasAny(filter)) continue;
			int startY = mc.world.sectionIndexToCoord(i) << 4;
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
