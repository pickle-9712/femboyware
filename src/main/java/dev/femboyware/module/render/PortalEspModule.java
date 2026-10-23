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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds lit portals, which only players make: ruined portals always generate unlit, and worldgen
 * never fills all twelve eyes of an end portal. Players build nether portals near their bases.
 */
public class PortalEspModule extends Module {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 5_000;

	private record Portals(Box bounds, boolean end) {}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final BoolSetting nether = add(new BoolSetting("Nether Portals", "Show lit nether portals", true));
	private final BoolSetting end = add(new BoolSetting("End Portals", "Show activated end portals", true));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "See portals through terrain", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines to portals", false));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when a portal is found", true));
	private final ColorSetting netherColor = add(new ColorSetting("Nether Color", "Nether portal highlight", 0xFFAA44FF));
	private final ColorSetting endColor = add(new ColorSetting("End Color", "End portal highlight", 0xFF44FFCC));

	private final ChunkScanner<Portals> scanner = new ChunkScanner<>(this::scan);
	private final Set<ChunkPos> notified = new HashSet<>();

	public PortalEspModule() {
		super("Portal Esp", "Finds lit portals players built", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(portalCount());
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
		for (Map.Entry<ChunkPos, Portals> entry : scanner.results().entrySet()) {
			if (!shown(entry.getValue()) || !notified.add(entry.getKey())) continue;
			ChatUtil.info((entry.getValue().end ? "End portal" : "Nether portal") + " at " + ChatUtil.coords(BlockPos.ofFloored(entry.getValue().bounds.getCenter())));
		}
	}

	private Portals scan(WorldChunk chunk) {
		Box[] bounds = new Box[1];
		boolean[] isEnd = {false};
		ChunkScanner.forEachBlock(chunk, PortalEspModule::isPortal, (pos, state) -> {
			if (state.isOf(Blocks.END_PORTAL)) isEnd[0] = true;
			bounds[0] = bounds[0] == null ? new Box(pos) : bounds[0].union(new Box(pos));
		});
		return bounds[0] == null ? null : new Portals(bounds[0], isEnd[0]);
	}

	private static boolean isPortal(BlockState state) {
		return state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL);
	}

	private boolean shown(Portals portals) {
		return portals.end ? end.get() : nether.get();
	}

	/** Chunks with a shown portal so far. */
	public int portalCount() {
		int count = 0;
		for (Portals portals : scanner.results().values()) {
			if (shown(portals)) count++;
		}
		return count;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3d tracerStart = RenderUtil.tracerOrigin(event);
		for (Portals portals : scanner.results().values()) {
			if (!shown(portals)) continue;
			int color = portals.end ? endColor.get() : netherColor.get();
			RenderUtil.drawBox(event, portals.bounds, (color & 0x00FFFFFF) | 0x30000000, color, 1.5f, throughWalls.get());
			Vec3d center = portals.bounds.getCenter();
			if (tracers.get()) RenderUtil.drawLine(event, tracerStart, center, color, 1.5f, throughWalls.get());
		}
	}
}
