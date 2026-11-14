package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Hunts respawn anchors. In the overworld nobody places one to respawn (it can't be used there),
 * so an anchor means a player was here: a base, a fight, a trap. Charged ones glow brighter and
 * show their charge; in the Nether a charged anchor is somebody's spawn point.
 */
public class AnchorHunterModule extends Module {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 4_000;

	private record Anchor(BlockPos pos, int charge) {}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final BoolSetting onlyCharged = add(new BoolSetting("Only Charged", "Only show anchors with glowstone in", false));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to anchors", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Tell you when an anchor turns up", true));

	private final ChunkScanner<List<Anchor>> scanner = new ChunkScanner<>(this::scan);
	private final Set<BlockPos> notified = new HashSet<>();

	public AnchorHunterModule() {
		super("Anchor Hunter", "Finds respawn anchors players placed, and their charge", Category.ESP);
	}

	@Override
	public String getInfo() {
		int n = 0;
		for (List<Anchor> list : scanner.results().values()) for (Anchor a : list) if (shown(a)) n++;
		return Integer.toString(n);
	}

	@Override
	protected void onDisable() {
		scanner.clear();
		notified.clear();
	}

	private boolean shown(Anchor a) {
		return !onlyCharged.get() || a.charge > 0;
	}

	private List<Anchor> scan(LevelChunk chunk) {
		List<Anchor> found = new ArrayList<>();
		ChunkScanner.forEachBlock(chunk, state -> state.is(Blocks.RESPAWN_ANCHOR),
			(pos, state) -> found.add(new Anchor(pos, state.getValue(RespawnAnchorBlock.CHARGE))));
		return found.isEmpty() ? null : found;
	}

	@Override
	public void onTick() {
		scanner.tick(range.getInt(), CHUNKS_PER_TICK, RESCAN_MILLIS);
		if (!notify.get()) return;
		for (List<Anchor> list : scanner.results().values()) {
			for (Anchor a : list) {
				if (!shown(a) || !notified.add(a.pos)) continue;
				ChatUtil.info("Anchor Hunter: anchor at " + ChatUtil.coords(a.pos) + (a.charge > 0 ? " (charged " + a.charge + "/4)" : ""));
			}
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 origin = RenderUtil.tracerOrigin(event);
		for (List<Anchor> list : scanner.results().values()) {
			for (Anchor a : list) {
				if (!shown(a)) continue;
				// Dull purple empty, bright orange as it fills
				int color = a.charge == 0 ? 0xFF7A3DCC : a.charge < 4 ? 0xFFFF9933 : 0xFFFFDD33;
				RenderUtil.drawBox(event, new AABB(a.pos), (color & 0x00FFFFFF) | 0x30000000, color, 1.5f, true);
				Vec3 center = Vec3.atCenterOf(a.pos);
				if (tracers.get()) RenderUtil.drawLine(event, origin, center, color, 1.2f, true);
				RenderUtil.drawText(event, "Anchor " + a.charge + "/4", center.add(0, 1, 0), color, 0.45f);
			}
		}
	}
}
