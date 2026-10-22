package dev.femboyware.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

public final class TargetUtil {
	private static final MinecraftClient mc = MinecraftClient.getInstance();

	private TargetUtil() {
	}

	/** Closest living, non-spectator player other than us within {@code range} blocks, or null. */
	public static PlayerEntity closestPlayer(double range) {
		PlayerEntity best = null;
		double bestDistance = range * range;
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (!isValid(player)) continue;
			double distance = mc.player.squaredDistanceTo(player);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = player;
			}
		}
		return best;
	}

	public static boolean isValid(PlayerEntity player) {
		return player != mc.player && player.isAlive() && !player.isSpectator() && !player.isRemoved();
	}
}
