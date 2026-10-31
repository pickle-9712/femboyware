package dev.femboyware.util;

import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.utility.FriendsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

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

	/** Closest living player other than us within {@code range} blocks, friend or not, or null. */
	public static PlayerEntity closestAny(double range) {
		PlayerEntity best = null;
		double bestDistance = range * range;
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (player == mc.player || !player.isAlive() || player.isSpectator()) continue;
			double distance = mc.player.squaredDistanceTo(player);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = player;
			}
		}
		return best;
	}

	/**
	 * Whether {@code player} is looking towards {@code point}: within 90 degrees either side of where
	 * they look, the same test the game uses for what a raised shield covers.
	 */
	public static boolean isFacing(PlayerEntity player, Vec3d point) {
		Vec3d to = point.subtract(player.getEyePos());
		Vec3d look = player.getRotationVec(1);
		return new Vec3d(to.x, 0, to.z).normalize().dotProduct(new Vec3d(look.x, 0, look.z).normalize()) > 0;
	}

	public static boolean isValid(PlayerEntity player) {
		return player != mc.player && player.isAlive() && !player.isSpectator() && !player.isRemoved() && !FriendsModule.protects(player)
			&& !BodyguardModule.protects(player);
	}
}
