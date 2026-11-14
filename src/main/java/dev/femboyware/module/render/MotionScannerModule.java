package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Picks out everything moving round you: players and mobs on the move, minecarts, item streams,
 * falling blocks, lit TNT and pistons mid-push (a running farm or flying machine). Each gets a box
 * and a trail of where it's been; players moving nearby are announced.
 */
public class MotionScannerModule extends Module {
	private static final int TRAIL = 20;

	private final NumberSetting range = add(new NumberSetting("Range", "Blocks around you to scan", 64, 8, 256, 8));
	private final NumberSetting minSpeed = add(new NumberSetting("Min Speed", "Blocks per second to count as moving", 1, 0.1, 10, 0.1));
	private final BoolSetting players = add(new BoolSetting("Players", "Moving players", true));
	private final BoolSetting mobs = add(new BoolSetting("Mobs", "Moving mobs", false));
	private final BoolSetting vehicles = add(new BoolSetting("Vehicles", "Minecarts and boats", true));
	private final BoolSetting items = add(new BoolSetting("Items", "Items in transit, falling blocks and lit TNT", true));
	private final BoolSetting pistons = add(new BoolSetting("Pistons", "Pistons mid-push", true));
	private final BoolSetting trails = add(new BoolSetting("Trails", "Show where things have been", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Tell you about players moving nearby", true));

	private final Map<UUID, ArrayDeque<Vec3>> history = new HashMap<>();
	private final List<Entity> moving = new ArrayList<>();
	private final List<BlockPos> pushing = new ArrayList<>();
	private final Set<UUID> announced = new HashSet<>();

	public MotionScannerModule() {
		super("Motion Scanner", "Highlights anything moving: players, carts, items, pistons", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(moving.size() + pushing.size());
	}

	@Override
	protected void onDisable() {
		history.clear();
		moving.clear();
		pushing.clear();
		announced.clear();
	}

	/** Minecarts, boats and rafts (their classes moved between versions, so by name). */
	private static boolean vehicle(Entity e) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		return id.contains("minecart") || id.endsWith("boat") || id.endsWith("raft");
	}

	private int colorOf(Entity e) {
		if (e instanceof Player) return 0xFFFF4466;
		if (vehicle(e)) return 0xFF55AAFF;
		if (e instanceof ItemEntity || e instanceof FallingBlockEntity || e instanceof PrimedTnt) return 0xFFFFCC33;
		return 0xFF66EE88;
	}

	private boolean wanted(Entity e) {
		if (e == mc.player) return false;
		if (e instanceof Player) return players.get();
		if (vehicle(e)) return vehicles.get();
		if (e instanceof ItemEntity || e instanceof FallingBlockEntity || e instanceof PrimedTnt) return items.get();
		return mobs.get() && e instanceof net.minecraft.world.entity.Mob;
	}

	@Override
	public void onTick() {
		moving.clear();
		double rangeSq = range.get() * range.get();
		Set<UUID> seen = new HashSet<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!wanted(e) || e.distanceToSqr(mc.player) > rangeSq) continue;
			seen.add(e.getUUID());
			ArrayDeque<Vec3> trail = history.computeIfAbsent(e.getUUID(), id -> new ArrayDeque<>());
			trail.addLast(e.position());
			while (trail.size() > TRAIL) trail.removeFirst();
			// Speed over the last half second
			Vec3 then = trail.size() > 10 ? trail.toArray(new Vec3[0])[trail.size() - 11] : trail.peekFirst();
			double speed = then.distanceTo(e.position()) / (Math.min(10, trail.size() - 1) / 20.0 + 1e-6);
			if (trail.size() < 2 || speed < minSpeed.get()) continue;
			moving.add(e);
			if (notify.get() && e instanceof Player player && announced.add(e.getUUID())) {
				ChatUtil.info("Motion: " + player.getGameProfile().name() + " moving at " + ChatUtil.coords(e.blockPosition())
					+ String.format(java.util.Locale.ROOT, " (%.0f m away)", Math.sqrt(e.distanceToSqr(mc.player))));
			}
		}
		history.keySet().retainAll(seen);
		announced.retainAll(seen);

		if (pistons.get() && mc.player.tickCount % 2 == 0) {
			pushing.clear();
			int chunks = Math.min(8, (int) Math.ceil(range.get() / 16));
			ChunkPos center = mc.player.chunkPosition();
			for (int x = -chunks; x <= chunks; x++) {
				for (int z = -chunks; z <= chunks; z++) {
					int cx = dev.femboyware.util.Mc.chunkX(center) + x, cz = dev.femboyware.util.Mc.chunkZ(center) + z;
					if (!mc.level.getChunkSource().hasChunk(cx, cz)) continue;
					for (BlockEntity be : mc.level.getChunk(cx, cz).getBlockEntities().values()) {
						if (be instanceof PistonMovingBlockEntity) pushing.add(be.getBlockPos());
					}
				}
			}
		} else if (!pistons.get()) pushing.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		for (Entity e : moving) {
			int color = colorOf(e);
			AABB box = e.getBoundingBox();
			RenderUtil.drawBox(event, box, (color & 0x00FFFFFF) | 0x20000000, color, 1.5f, true);
			ArrayDeque<Vec3> trail = history.get(e.getUUID());
			if (!trails.get() || trail == null) continue;
			Vec3 last = null;
			for (Vec3 point : trail) {
				if (last != null) RenderUtil.drawLine(event, last.add(0, 0.1, 0), point.add(0, 0.1, 0), color, 1.5f, true);
				last = point;
			}
		}
		for (BlockPos pos : pushing) RenderUtil.drawBox(event, new AABB(pos), 0x30AA66FF, 0xFFAA66FF, 1.5f, true);
	}
}
