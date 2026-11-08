package dev.femboyware.util;

import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.utility.FriendsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class TargetUtil {
	private static final Minecraft mc = Minecraft.getInstance();

	private TargetUtil() {
	}

	/** Closest living, non-spectator player other than us within {@code range} blocks, or null. */
	public static Player closestPlayer(double range) {
		Player best = null;
		double bestDistance = range * range;
		for (Player player : mc.level.players()) {
			if (!isValid(player)) continue;
			double distance = mc.player.distanceToSqr(player);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = player;
			}
		}
		return best;
	}

	/** Closest living player other than us within {@code range} blocks, friend or not, or null. */
	public static Player closestAny(double range) {
		Player best = null;
		double bestDistance = range * range;
		for (Player player : mc.level.players()) {
			if (player == mc.player || !player.isAlive() || player.isSpectator()) continue;
			double distance = mc.player.distanceToSqr(player);
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
	public static boolean isFacing(Player player, Vec3 point) {
		Vec3 to = point.subtract(player.getEyePosition());
		Vec3 look = player.getViewVector(1);
		return new Vec3(to.x, 0, to.z).normalize().dot(new Vec3(look.x, 0, look.z).normalize()) > 0;
	}

	public static boolean isValid(Player player) {
		return player != mc.player && player.isAlive() && !player.isSpectator() && !player.isRemoved() && !FriendsModule.protects(player)
			&& !BodyguardModule.protects(player);
	}
}
