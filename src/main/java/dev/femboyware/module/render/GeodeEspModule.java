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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds amethyst geodes that have grown far past how they generate, a sign someone has spent a
 * long time nearby.
 *
 * <p>A fresh geode has a bud on roughly one in twelve faces of its budding amethyst, and about a
 * quarter of those buds are full clusters. Buds only appear and grow on random ticks, and random
 * ticks only happen while a player is within simulation distance. A geode crowded with buds that
 * are mostly clusters has therefore been loaded for a long time, usually by a base close by.
 */
public class GeodeEspModule extends Module {
	/** Chunks scanned per tick. Geodes change slowly, so a lazy pace is plenty. */
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 10_000;

	/** Amethyst counts in one chunk; a geode crossing a chunk border shows as one entry per chunk. */
	private record Geode(AABB bounds, int budding, int buds, int clusters) {
		float clusterShare() {
			return buds == 0 ? 0 : (float) clusters / buds;
		}

		float density() {
			return budding == 0 ? 0 : (float) buds / budding;
		}
	}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final NumberSetting minBuds = add(new NumberSetting("Min Buds", "Ignore geodes with fewer buds and clusters than this", 12, 1, 100, 1));
	private final NumberSetting minClusterShare = add(new NumberSetting("Min Cluster %", "Overgrown when at least this share of buds are full clusters, about 25% when fresh", 50, 30, 100, 1));
	private final NumberSetting minDensity = add(new NumberSetting("Min Density", "Or when there are this many buds per budding block, about 0.5 when fresh", 1.5, 0.6, 6, 0.1));
	private final BoolSetting showNatural = add(new BoolSetting("Show Natural", "Also show geodes that look untouched", false));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "See geodes through terrain", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines to overgrown geodes", false));
	private final BoolSetting labels = add(new BoolSetting("Labels", "Show how grown each geode is", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when an overgrown geode is found", true));
	private final ColorSetting overgrownColor = add(new ColorSetting("Overgrown Color", "Geodes with player activity", 0xFFFF77C8));
	private final ColorSetting naturalColor = add(new ColorSetting("Natural Color", "Geodes that look untouched", 0x80A070FF)).visibleWhen(showNatural::get);

	private final ChunkScanner<Geode> scanner = new ChunkScanner<>(this::scan);
	/** Centres of geodes already announced, so a geode split over chunks is only announced once. */
	private final List<Vec3> notified = new ArrayList<>();

	public GeodeEspModule() {
		super("Geode Esp", "Finds overgrown geodes that point to player activity", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(overgrownCount());
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
		for (Geode geode : scanner.results().values()) {
			if (!isOvergrown(geode)) continue;
			Vec3 center = geode.bounds.getCenter();
			if (notified.stream().anyMatch(seen -> seen.distanceToSqr(center) < 16 * 16)) continue;
			notified.add(center);
			ChatUtil.info(String.format("Overgrown geode at %s (%d%% clusters, %.1f buds per budding block)",
				ChatUtil.coords(BlockPos.containing(center)), Math.round(geode.clusterShare() * 100), geode.density()));
		}
	}

	private Geode scan(LevelChunk chunk) {
		int[] counts = new int[3];
		double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		ChunkScanner.forEachBlock(chunk, GeodeEspModule::isAmethyst, (pos, state) -> {
			if (state.is(Blocks.BUDDING_AMETHYST)) counts[0]++;
			else counts[1]++;
			if (state.is(Blocks.AMETHYST_CLUSTER)) counts[2]++;
			bounds[0] = Math.min(bounds[0], pos.getX());
			bounds[1] = Math.min(bounds[1], pos.getY());
			bounds[2] = Math.min(bounds[2], pos.getZ());
			bounds[3] = Math.max(bounds[3], pos.getX() + 1);
			bounds[4] = Math.max(bounds[4], pos.getY() + 1);
			bounds[5] = Math.max(bounds[5], pos.getZ() + 1);
		});
		if (counts[0] == 0 && counts[1] == 0) return null;
		return new Geode(new AABB(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]), counts[0], counts[1], counts[2]);
	}

	private static boolean isAmethyst(BlockState state) {
		return state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
			|| state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER);
	}

	private boolean isOvergrown(Geode geode) {
		if (geode.buds < minBuds.getInt()) return false;
		return geode.clusterShare() * 100 >= minClusterShare.get() || geode.density() >= minDensity.get();
	}

	/** Chunks holding an overgrown geode, among those scanned so far. */
	public int overgrownCount() {
		int count = 0;
		for (Geode geode : scanner.results().values()) {
			if (isOvergrown(geode)) count++;
		}
		return count;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 tracerStart = RenderUtil.tracerOrigin(event);
		for (Map.Entry<ChunkPos, Geode> entry : scanner.results().entrySet()) {
			Geode geode = entry.getValue();
			boolean overgrown = isOvergrown(geode);
			if (!overgrown && !showNatural.get()) continue;
			int color = overgrown ? overgrownColor.get() : naturalColor.get();
			RenderUtil.drawBox(event, geode.bounds, (color & 0x00FFFFFF) | 0x20000000, color, 1.5f, throughWalls.get());
			Vec3 center = geode.bounds.getCenter();
			if (overgrown && tracers.get()) RenderUtil.drawLine(event, tracerStart, center, color, 1.5f, throughWalls.get());
			if (labels.get()) {
				String text = String.format("%d%% grown, %d buds", Math.round(geode.clusterShare() * 100), geode.buds);
				RenderUtil.drawText(event, text, new Vec3(center.x, geode.bounds.maxY + 0.5, center.z), color | 0xFF000000, 0.5f);
			}
		}
	}
}
