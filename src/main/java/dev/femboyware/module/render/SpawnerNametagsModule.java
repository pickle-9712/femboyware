package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.gui.Theme;
import dev.femboyware.mixin.SpawnerRangeAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Shows each spawner's mob and activation range over the block, with a ring on the ground at the
 * range (brighter while you're inside it). Ported from the Anubis client (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public class SpawnerNametagsModule extends Module {
	private static final int MAX_LABELS = 48;
	private static final int RING_SEGMENTS = 64;
	private static final float RING_ALPHA_INSIDE = 0.8F;
	private static final float RING_ALPHA_OUTSIDE = 0.4F;
	private static final double LABEL_HEIGHT = 1.2;
	private static final double FADE_SHARE = 0.125;

	private final NumberSetting range = add(new NumberSetting("Range", "How far away to label spawners", 64, 8, 256, 1));
	private final BoolSetting radius = add(new BoolSetting("Activation Radius", "Show how close you have to be for the spawner to run, with a ring", true));

	private record Shown(BlockPos pos, double distance, String mob, int range, boolean inside) {
	}

	private List<Shown> shown = List.of();

	public SpawnerNametagsModule() {
		super("Spawner Nametags", "Shows each spawner's mob and activation range over the block", Category.ESP);
	}

	@Override
	protected void onDisable() {
		shown = List.of();
	}

	@Override
	public void onTick() {
		if (mc.level == null || mc.player == null) {
			shown = List.of();
			return;
		}
		Vec3 player = mc.player.position();
		double max = range.get();
		int chunks = (int) Math.ceil(max / 16) + 1;
		ChunkPos center = mc.player.chunkPosition();
		int cx = dev.femboyware.util.Mc.chunkX(center), cz = dev.femboyware.util.Mc.chunkZ(center);
		List<Shown> found = new ArrayList<>();
		for (int dx = -chunks; dx <= chunks; dx++) {
			for (int dz = -chunks; dz <= chunks; dz++) {
				if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) continue;
				LevelChunk chunk = mc.level.getChunk(cx + dx, cz + dz);
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (!(be instanceof SpawnerBlockEntity spawner)) continue;
					BlockPos pos = spawner.getBlockPos();
					double distanceSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
					if (distanceSq > max * max) continue;
					int activation = ((SpawnerRangeAccessor) spawner.getSpawner()).femboyware$requiredPlayerRange();
					boolean inside = activation < 0 || distanceSq < (double) activation * activation;
					found.add(new Shown(pos, Math.sqrt(distanceSq), mobName(spawner), activation, inside));
				}
			}
		}
		found.sort(Comparator.comparingDouble(Shown::distance));
		shown = found.size() > MAX_LABELS ? found.subList(0, MAX_LABELS) : found;
	}

	private String mobName(SpawnerBlockEntity spawner) {
		Entity mob = spawner.getSpawner().getOrCreateDisplayEntity(mc.level, spawner.getBlockPos());
		return mob == null ? null : mob.getType().getDescription().getString();
	}

	private float fade(double distance) {
		double max = range.get();
		return (float) Math.max(0.0, Math.min(1.0, (max - distance) / (max * FADE_SHARE)));
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (shown.isEmpty()) return;
		int accent = Theme.accent() & 0xFFFFFF;
		for (Shown spawner : shown) {
			float fade = fade(spawner.distance());
			if (fade <= 0.02f) continue;
			Vec3 top = Vec3.atCenterOf(spawner.pos()).add(0, LABEL_HEIGHT, 0);
			String name = spawner.mob() == null ? "Spawner" : spawner.mob() + " Spawner";
			String info = Math.round(spawner.distance()) + "m" + (radius.get() && spawner.range() > 0 ? "  ·  " + spawner.range() + "m range" : "");
			int alpha = Math.round(255 * fade) << 24;
			RenderUtil.drawText(event, name, top.add(0, 0.3, 0), alpha | 0xFFFFFF, 0.5f);
			RenderUtil.drawText(event, info, top, alpha | (spawner.inside() && radius.get() ? accent : 0xDDDDDD), 0.45f);
			if (radius.get() && spawner.range() > 0) {
				float ringAlpha = (spawner.inside() ? RING_ALPHA_INSIDE : RING_ALPHA_OUTSIDE) * fade;
				int color = Math.round(ringAlpha * 255) << 24 | accent;
				if (color >>> 24 >= 4) drawRing(event, Vec3.atCenterOf(spawner.pos()), spawner.range(), color);
			}
		}
	}

	private static void drawRing(Render3DEvent event, Vec3 centre, double radius, int color) {
		Vec3 previous = null;
		for (int i = 0; i <= RING_SEGMENTS; i++) {
			double angle = Math.PI * 2 * i / RING_SEGMENTS;
			Vec3 point = centre.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
			if (previous != null) RenderUtil.drawLine(event, previous, point, color, 1.5f, true);
			previous = point;
		}
	}
}
