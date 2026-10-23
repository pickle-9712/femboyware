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
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Flags chunks packed with storage. Natural structures hold a handful of chests at most (a desert
 * temple has four), so a chunk with many chests, barrels or shulker boxes is a stash.
 */
public class StashEspModule extends Module {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 5_000;

	private record Stash(Box bounds, int chests, int barrels, int shulkers) {
		int total() {
			return chests + barrels + shulkers;
		}

		String summary() {
			return chests + " chests, " + barrels + " barrels, " + shulkers + " shulkers";
		}
	}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final NumberSetting minStorage = add(new NumberSetting("Min Storage", "Storage blocks a chunk needs to count as a stash", 10, 2, 100, 1));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "See stashes through terrain", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines to stashes", false));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when a stash is found", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0xFF33CCFF));

	private final ChunkScanner<Stash> scanner = new ChunkScanner<>(this::scan);
	private final Set<ChunkPos> notified = new HashSet<>();

	public StashEspModule() {
		super("Stash Esp", "Finds chunks packed with chests and shulkers", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(stashCount());
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
		for (Map.Entry<ChunkPos, Stash> entry : scanner.results().entrySet()) {
			Stash stash = entry.getValue();
			if (stash.total() < minStorage.getInt() || !notified.add(entry.getKey())) continue;
			ChatUtil.info("Stash at " + ChatUtil.coords(BlockPos.ofFloored(stash.bounds.getCenter())) + " (" + stash.summary() + ")");
		}
	}

	private Stash scan(WorldChunk chunk) {
		int chests = 0, barrels = 0, shulkers = 0;
		Box bounds = null;
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			if (blockEntity instanceof ChestBlockEntity) chests++;
			else if (blockEntity instanceof BarrelBlockEntity) barrels++;
			else if (blockEntity instanceof ShulkerBoxBlockEntity) shulkers++;
			else continue;
			Box box = new Box(blockEntity.getPos());
			bounds = bounds == null ? box : bounds.union(box);
		}
		return bounds == null ? null : new Stash(bounds, chests, barrels, shulkers);
	}

	/** Chunks counted as stashes so far. */
	public int stashCount() {
		int count = 0;
		for (Stash stash : scanner.results().values()) {
			if (stash.total() >= minStorage.getInt()) count++;
		}
		return count;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3d tracerStart = RenderUtil.tracerOrigin(event);
		int fill = (color.get() & 0x00FFFFFF) | 0x20000000;
		for (Stash stash : scanner.results().values()) {
			if (stash.total() < minStorage.getInt()) continue;
			RenderUtil.drawBox(event, stash.bounds, fill, color.get(), 1.5f, throughWalls.get());
			Vec3d center = stash.bounds.getCenter();
			if (tracers.get()) RenderUtil.drawLine(event, tracerStart, center, color.get(), 1.5f, throughWalls.get());
			RenderUtil.drawText(event, "Stash: " + stash.summary(), new Vec3d(center.x, stash.bounds.maxY + 1, center.z), color.get() | 0xFF000000, 0.5f);
		}
	}
}
