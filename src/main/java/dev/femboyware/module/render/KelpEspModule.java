package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.RenderUtil;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds kelp that has grown since it generated, a sign a chunk has been loaded by a player for a
 * long time.
 *
 * <p>Kelp generates at most 11 blocks tall, and its top block starts at growth age 20 to 23. It
 * only grows on random ticks, which only happen near a player, and every growth raises the age
 * by one up to 25. A top at age 24 or 25, or a column taller than 11, has grown since generation.
 * One grown column means little; many in a chunk mean someone stayed around.
 */
public class KelpEspModule extends Module {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 10_000;
	/** Tallest kelp worldgen makes. */
	private static final int NATURAL_MAX_HEIGHT = 11;
	/** Highest age worldgen gives a kelp top. */
	private static final int NATURAL_MAX_AGE = 23;

	/** Grown kelp columns in one chunk, by the position of each top. */
	private record GrownKelp(List<BlockPos> tops, Box bounds) {}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final NumberSetting minColumns = add(new NumberSetting("Min Columns", "Grown kelp columns a chunk needs before it's flagged", 4, 1, 64, 1));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "See flagged chunks through terrain", true));
	private final BoolSetting showColumns = add(new BoolSetting("Show Columns", "Mark each grown kelp top", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines to flagged chunks", false));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when a chunk with grown kelp is found", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0xFF55FFAA));

	private final ChunkScanner<GrownKelp> scanner = new ChunkScanner<>(this::scan);
	private final Set<ChunkPos> notified = new HashSet<>();

	public KelpEspModule() {
		super("Kelp Esp", "Finds grown kelp that points to long player activity", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(flaggedCount());
	}

	@Override
	protected void onDisable() {
		scanner.clear();
		notified.clear();
	}

	@Override
	public void onTick() {
		scanner.tick(range.getInt(), CHUNKS_PER_TICK, RESCAN_MILLIS);
		if (!notify.get()) return;
		for (Map.Entry<ChunkPos, GrownKelp> entry : scanner.results().entrySet()) {
			if (!isFlagged(entry.getValue()) || !notified.add(entry.getKey())) continue;
			ChatUtil.info(String.format("Grown kelp at %s (%d columns)",
				ChatUtil.coords(BlockPos.ofFloored(entry.getValue().bounds.getCenter())), entry.getValue().tops.size()));
		}
	}

	private GrownKelp scan(WorldChunk chunk) {
		List<BlockPos> tops = new ArrayList<>();
		ChunkScanner.forEachBlock(chunk, state -> state.isOf(Blocks.KELP), (pos, state) -> {
			if (state.get(Properties.AGE_25) > NATURAL_MAX_AGE || height(chunk, pos) > NATURAL_MAX_HEIGHT) tops.add(pos);
		});
		if (tops.isEmpty()) return null;
		Box bounds = new Box(tops.getFirst());
		for (BlockPos top : tops) bounds = bounds.union(new Box(top));
		return new GrownKelp(tops, bounds);
	}

	/** Length of the kelp column ending at {@code top}. */
	private static int height(WorldChunk chunk, BlockPos top) {
		int height = 1;
		BlockPos.Mutable pos = top.mutableCopy().move(0, -1, 0);
		while (chunk.getBlockState(pos).isOf(Blocks.KELP_PLANT)) {
			height++;
			pos.move(0, -1, 0);
		}
		return height;
	}

	private boolean isFlagged(GrownKelp kelp) {
		return kelp.tops.size() >= minColumns.getInt();
	}

	/** Chunks flagged so far. */
	public int flaggedCount() {
		int count = 0;
		for (GrownKelp kelp : scanner.results().values()) {
			if (isFlagged(kelp)) count++;
		}
		return count;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3d tracerStart = RenderUtil.tracerOrigin(event);
		int fill = (color.get() & 0x00FFFFFF) | 0x20000000;
		for (GrownKelp kelp : scanner.results().values()) {
			if (!isFlagged(kelp)) continue;
			RenderUtil.drawBox(event, kelp.bounds, fill, color.get(), 1.5f, throughWalls.get());
			if (showColumns.get()) {
				for (BlockPos top : kelp.tops) RenderUtil.drawBox(event, new Box(top), 0, color.get(), 1f, throughWalls.get());
			}
			Vec3d center = kelp.bounds.getCenter();
			if (tracers.get()) RenderUtil.drawLine(event, tracerStart, center, color.get(), 1.5f, throughWalls.get());
			RenderUtil.drawText(event, kelp.tops.size() + " grown kelp", new Vec3d(center.x, kelp.bounds.maxY + 1, center.z), color.get() | 0xFF000000, 0.5f);
		}
	}
}
