package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.BlockMatcher;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Flags chunks full of blocks that worldgen never places, which is what bases look like.
 *
 * <p>Strong blocks (ender chests, shulker boxes, beacons...) never generate and count three each.
 * Weak blocks (crafting tables, furnaces, beds, signs...) also show up in villages and igloos and
 * count one. Within a chunk of a village bell only strong blocks count, so villages stay quiet.
 */
public class BaseEspModule extends Module {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 5_000;
	private static final int STRONG_WEIGHT = 3;

	private record Base(Box bounds, int strong, int weak, boolean bell, Map<Block, Integer> counts) {}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final NumberSetting minScore = add(new NumberSetting("Min Score", "Strong blocks count 3, weak blocks 1; flag chunks scoring at least this", 6, 1, 100, 1));
	private final StringSetting strongBlocks = add(new StringSetting("Strong Blocks", "Comma separated ids or #tags of blocks that never generate",
		"ender_chest, #shulker_boxes, beacon, respawn_anchor, enchanting_table, lodestone, conduit, netherite_block, diamond_block, emerald_block"));
	private final StringSetting weakBlocks = add(new StringSetting("Weak Blocks", "Comma separated ids or #tags of player blocks that villages also have",
		"crafting_table, furnace, blast_furnace, smoker, #beds, #all_signs, #banners, anvil, chipped_anvil, damaged_anvil, brewing_stand, jukebox, note_block"));
	private final BoolSetting ignoreVillages = add(new BoolSetting("Ignore Villages", "Only count strong blocks within a chunk of a bell", true));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "See bases through terrain", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines to bases", false));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when a base is found", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0xFFFFAA33));

	private final ChunkScanner<Base> scanner = new ChunkScanner<>(this::scan);
	private final Set<ChunkPos> notified = new HashSet<>();
	private Predicate<BlockState> strong = state -> false;
	private Predicate<BlockState> weak = state -> false;
	private String parsedStrong;
	private String parsedWeak;

	public BaseEspModule() {
		super("Base Esp", "Finds chunks full of player-placed blocks", Category.ESP);
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
		if (!strongBlocks.get().equals(parsedStrong) || !weakBlocks.get().equals(parsedWeak)) {
			strong = BlockMatcher.parse(strongBlocks.get());
			weak = BlockMatcher.parse(weakBlocks.get());
			parsedStrong = strongBlocks.get();
			parsedWeak = weakBlocks.get();
			scanner.clear();
		}
		scanner.tick(range.getInt(), CHUNKS_PER_TICK, RESCAN_MILLIS);
		if (!notify.get()) return;
		for (Map.Entry<ChunkPos, Base> entry : scanner.results().entrySet()) {
			if (!isFlagged(entry.getKey(), entry.getValue()) || !notified.add(entry.getKey())) continue;
			Base base = entry.getValue();
			ChatUtil.info(String.format("Possible base at %s (score %d: %s)",
				ChatUtil.coords(BlockPos.ofFloored(base.bounds.getCenter())), score(entry.getKey(), base), summary(base)));
		}
	}

	private Base scan(WorldChunk chunk) {
		int[] counts = new int[2];
		boolean[] bell = {false};
		Map<Block, Integer> blocks = new HashMap<>();
		List<BlockPos> positions = new ArrayList<>();
		ChunkScanner.forEachBlock(chunk, state -> state.isOf(Blocks.BELL) || strong.test(state) || weak.test(state), (pos, state) -> {
			if (state.isOf(Blocks.BELL)) {
				bell[0] = true;
				return;
			}
			if (strong.test(state)) counts[0]++;
			else counts[1]++;
			blocks.merge(state.getBlock(), 1, Integer::sum);
			positions.add(pos);
		});
		if (positions.isEmpty()) return bell[0] ? new Base(null, 0, 0, true, Map.of()) : null;
		Box bounds = new Box(positions.getFirst());
		for (BlockPos pos : positions) bounds = bounds.union(new Box(pos));
		return new Base(bounds, counts[0], counts[1], bell[0], blocks);
	}

	private int score(ChunkPos pos, Base base) {
		boolean nearVillage = ignoreVillages.get() && nearBell(pos);
		return base.strong * STRONG_WEIGHT + (nearVillage ? 0 : base.weak);
	}

	private boolean nearBell(ChunkPos center) {
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				Base neighbour = scanner.results().get(new ChunkPos(center.x + x, center.z + z));
				if (neighbour != null && neighbour.bell) return true;
			}
		}
		return false;
	}

	private boolean isFlagged(ChunkPos pos, Base base) {
		return base.bounds != null && score(pos, base) >= minScore.getInt();
	}

	/** Chunks flagged as bases so far. */
	public int flaggedCount() {
		int count = 0;
		for (Map.Entry<ChunkPos, Base> entry : scanner.results().entrySet()) {
			if (isFlagged(entry.getKey(), entry.getValue())) count++;
		}
		return count;
	}

	/** The three most common blocks, e.g. "2x ender chest, 1x beacon". */
	private static String summary(Base base) {
		return base.counts.entrySet().stream()
			.sorted(Map.Entry.<Block, Integer>comparingByValue(Comparator.reverseOrder()))
			.limit(3)
			.map(entry -> entry.getValue() + "x " + entry.getKey().getName().getString())
			.collect(Collectors.joining(", "));
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3d tracerStart = RenderUtil.tracerOrigin(event);
		int fill = (color.get() & 0x00FFFFFF) | 0x20000000;
		for (Map.Entry<ChunkPos, Base> entry : scanner.results().entrySet()) {
			Base base = entry.getValue();
			if (!isFlagged(entry.getKey(), base)) continue;
			RenderUtil.drawBox(event, base.bounds, fill, color.get(), 1.5f, throughWalls.get());
			Vec3d center = base.bounds.getCenter();
			if (tracers.get()) RenderUtil.drawLine(event, tracerStart, center, color.get(), 1.5f, throughWalls.get());
			RenderUtil.drawText(event, "Base? " + score(entry.getKey(), base) + " - " + summary(base),
				new Vec3d(center.x, base.bounds.maxY + 1, center.z), color.get() | 0xFF000000, 0.5f);
		}
	}
}
