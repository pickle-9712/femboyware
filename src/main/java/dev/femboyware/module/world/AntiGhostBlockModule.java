package dev.femboyware.module.world;

import dev.femboyware.FemboyWare;
import dev.femboyware.mixin.ClientLevelAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;

/**
 * Stops ghost blocks when mining: a broken block stays until the server confirms the break, so a
 * block the server didn't let you break never turns invisible. Ported from the Anubis client's
 * Anti Ghost Block (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class AntiGhostBlockModule extends Module {
	private static final int MAX_PENDING = 256;

	private record Pending(int sequence, long since) {
	}

	private final Map<Long, Pending> pending = new LinkedHashMap<>();
	private ClientLevel world;

	public AntiGhostBlockModule() {
		super("Anti Ghost Block", "Broken blocks stay until the server confirms the break", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		pending.clear();
		world = mc.level;
	}

	@Override
	protected void onDisable() {
		pending.clear();
		world = null;
	}

	private static AntiGhostBlockModule active(ClientLevel level) {
		if (FemboyWare.getModules() == null) return null;
		AntiGhostBlockModule module = FemboyWare.getModules().get(AntiGhostBlockModule.class);
		if (module == null || !module.isEnabled()) return null;
		if (module.world != mc.level) {
			module.pending.clear();
			module.world = mc.level;
		}
		return level != null && level == mc.level && mc.player != null && mc.getConnection() != null && !mc.isLocalServer() ? module : null;
	}

	private static long timeoutNanos() {
		PlayerInfo info = mc.getConnection() != null && mc.player != null ? mc.getConnection().getPlayerInfo(mc.player.getUUID()) : null;
		long ping = info == null ? 0 : Math.max(0, info.getLatency());
		return Math.max(2000L, ping * 3 + 500) * 1_000_000L;
	}

	@Override
	public void onTick() {
		if (active(mc.level) == null) return;
		long now = System.nanoTime(), timeout = timeoutNanos();
		pending.values().removeIf(entry -> now - entry.since >= timeout);
	}

	/** Called before a block breaks client-side: true keeps it (the break waits on the server). */
	public static boolean holdBreak(BlockPos pos) {
		AntiGhostBlockModule module = active(mc.level);
		if (module == null || mc.level.getBlockState(pos).isAir()) return false;
		int sequence = ((ClientLevelAccessor) mc.level).femboyware$getPredictionHandler().currentSequence();
		long key = pos.asLong();
		if (!module.pending.containsKey(key)) {
			if (module.pending.size() >= MAX_PENDING) {
				Iterator<Long> oldest = module.pending.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
			module.pending.put(key, new Pending(sequence, System.nanoTime()));
		}
		return true;
	}

	/** True while this block's break is still waiting on the server (don't mine it again yet). */
	public static boolean waiting(BlockPos pos) {
		AntiGhostBlockModule module = active(mc.level);
		if (module == null) return false;
		Pending entry = module.pending.get(pos.asLong());
		if (entry == null) return false;
		if (System.nanoTime() - entry.since < timeoutNanos()) return true;
		module.pending.remove(pos.asLong());
		return false;
	}

	public static boolean serverAnswered(ClientLevel level, BlockPos pos) {
		AntiGhostBlockModule module = active(level);
		return module != null && module.pending.remove(pos.asLong()) != null;
	}

	public static void acknowledged(ClientLevel level, int sequence) {
		AntiGhostBlockModule module = active(level);
		if (module != null) module.pending.values().removeIf(entry -> entry.sequence <= sequence);
	}
}
