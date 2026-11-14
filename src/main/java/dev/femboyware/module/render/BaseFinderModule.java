package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.Mc;
import dev.femboyware.util.RenderUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Scores every chunk for how much it looks like someone lives there. Blocks only players make
 * (enchanting tables, beacons, anvils, shulkers, concrete, ender chests) count a lot; blocks that
 * also turn up in villages and mineshafts (beds, crafting tables, torches, chests) count a little.
 * Chunks over the score are boxed, labelled with what's there, and announced.
 */
public class BaseFinderModule extends Module {
	private static final int CHUNKS_PER_TICK = 3;
	private static final long RESCAN_MILLIS = 8_000;

	private record Base(int score, int x, int z, int minY, int maxY, String summary) {}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final NumberSetting minScore = add(new NumberSetting("Min Score", "How base-like a chunk must be to show", 40, 5, 300, 5));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to bases", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Tell you when a base is found", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0xFFFF55AA));

	private final ChunkScanner<Base> scanner = new ChunkScanner<>(this::scan);
	private final Set<ChunkPos> notified = new HashSet<>();
	/** Points per block, worked out once per block type. */
	private static final Map<Block, Integer> WEIGHTS = new HashMap<>();

	public BaseFinderModule() {
		super("Base Finder", "Scores chunks for player bases and points you to them", Category.ESP);
	}

	@Override
	public String getInfo() {
		int n = 0;
		for (Base b : scanner.results().values()) if (b.score >= minScore.getInt()) n++;
		return Integer.toString(n);
	}

	@Override
	protected void onDisable() {
		scanner.clear();
		notified.clear();
	}

	private static int weight(BlockState state) {
		return WEIGHTS.computeIfAbsent(state.getBlock(), block -> {
			if (block == Blocks.BEACON || block == Blocks.CONDUIT || block == Blocks.DRAGON_EGG) return 40;
			if (block == Blocks.ENCHANTING_TABLE || block == Blocks.RESPAWN_ANCHOR) return 15;
			if (block == Blocks.ENDER_CHEST || block == Blocks.LODESTONE || block == Blocks.NETHERITE_BLOCK || block == Blocks.DIAMOND_BLOCK) return 12;
			if (state.is(BlockTags.SHULKER_BOXES)) return 10;
			if (state.is(BlockTags.ANVIL) || block == Blocks.SMITHING_TABLE || block == Blocks.GRINDSTONE) return 6;
			if (block == Blocks.BREWING_STAND || block == Blocks.HOPPER || block == Blocks.OBSERVER || block == Blocks.STICKY_PISTON
				|| block == Blocks.REPEATER || block == Blocks.COMPARATOR || block == Blocks.DROPPER) return 5;
			if (block == Blocks.CRAFTER || block == Blocks.SCAFFOLDING) return 4;
			if (state.is(BlockTags.BANNERS) || state.is(BlockTags.ALL_SIGNS) || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) return 3;
			if (state.is(BlockTags.BEDS) || block == Blocks.CRAFTING_TABLE || block == Blocks.CHEST || block == Blocks.BARREL || block == Blocks.TRAPPED_CHEST) return 2;
			String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
			if (id.endsWith("concrete") || id.endsWith("glazed_terracotta") || id.contains("stained_glass") || id.startsWith("quartz")) return 1;
			if (block == Blocks.GLASS || block == Blocks.SEA_LANTERN) return 1;
			return 0;
		});
	}

	private Base scan(LevelChunk chunk) {
		int[] score = {0}, minY = {Integer.MAX_VALUE}, maxY = {Integer.MIN_VALUE};
		Map<String, Integer> found = new HashMap<>();
		ChunkScanner.forEachBlock(chunk, state -> weight(state) > 0, (pos, state) -> {
			int w = weight(state);
			score[0] += w;
			minY[0] = Math.min(minY[0], pos.getY());
			maxY[0] = Math.max(maxY[0], pos.getY());
			// Remember the telling ones for the label
			if (w >= 5) found.merge(state.getBlock().getName().getString(), 1, Integer::sum);
		});
		if (score[0] == 0) return null;
		StringBuilder summary = new StringBuilder();
		found.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(e -> {
			if (!summary.isEmpty()) summary.append(", ");
			summary.append(e.getValue()).append(" ").append(e.getKey());
		});
		ChunkPos pos = chunk.getPos();
		return new Base(score[0], pos.getMinBlockX(), pos.getMinBlockZ(), minY[0], maxY[0] + 1, summary.toString());
	}

	@Override
	public void onTick() {
		scanner.tick(range.getInt(), CHUNKS_PER_TICK, RESCAN_MILLIS);
		if (!notify.get()) return;
		for (var entry : scanner.results().entrySet()) {
			Base base = entry.getValue();
			if (base.score < minScore.getInt() || !notified.add(entry.getKey())) continue;
			ChatUtil.info("Base Finder: possible base at " + (base.x + 8) + ", " + base.minY + ", " + (base.z + 8) + " (score " + base.score
				+ (base.summary.isEmpty() ? "" : ", " + base.summary) + ")");
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 origin = RenderUtil.tracerOrigin(event);
		int fill = (color.get() & 0x00FFFFFF) | 0x18000000;
		for (Base base : scanner.results().values()) {
			if (base.score < minScore.getInt()) continue;
			AABB box = new AABB(base.x, base.minY, base.z, base.x + 16, base.maxY, base.z + 16);
			RenderUtil.drawBox(event, box, fill, color.get(), 1.5f, true);
			Vec3 center = box.getCenter();
			if (tracers.get()) RenderUtil.drawLine(event, origin, center, color.get(), 1.5f, true);
			RenderUtil.drawText(event, "Base " + base.score + (base.summary.isEmpty() ? "" : ": " + base.summary), new Vec3(center.x, box.maxY + 1.5, center.z), color.get() | 0xFF000000, 0.6f);
		}
	}
}
