package dev.femboyware.util;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What a module's Grim setting changes, so it acts the way Grim expects a real client to:
 * <ul>
 * <li>Its post-motion actions (attacks, placements) go out at the start of the next tick, before
 * that tick's movement packet, as vanilla's clicks do (Grim's Post check), using the rotation the
 * packet before them already carried.</li>
 * <li>Blocks are only placed once the rotation the server has looks at the clicked face, and the
 * click lands where that look meets it (RotationPlace, FabricatedPlace); otherwise it turns there
 * through the rotation manager and places next tick.</li>
 * <li>No rotations in packets of their own, which count towards Grim's Timer check: a module asking
 * for one gets it in the next movement packet instead.</li>
 * <li>Only faces you can see are clicked (PositionPlace), one block per tick (MultiPlace), and
 * attacks stay within vanilla reach.</li>
 * </ul>
 * The helpers here read the Grim setting of whichever module the module manager is running.
 */
public final class Grim {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final int ROTATION_PRIORITY = 95;
	private static final int PLACES_PER_TICK = 1;
	public static final double REACH = 3.0;

	private static final ThreadLocal<Module> current = new ThreadLocal<>();
	private static long tick = -1;
	private static int places;
	private static boolean requested;

	private Grim() {
	}

	/** Set by the module manager around each module it runs; returns the one running before. */
	public static Module running(Module module) {
		Module outer = current.get();
		current.set(module);
		return outer;
	}

	/** Whether the module being run has Grim on. */
	public static boolean on() {
		Module module = current.get();
		return module != null && module.isGrim() && mc.player != null;
	}

	/** Attack range to use, at most vanilla's when Grim is on. */
	public static double reach(double wanted) {
		return on() ? Math.min(wanted, REACH) : wanted;
	}

	private static void roll() {
		long now = mc.level == null ? 0 : mc.level.getGameTime();
		if (now != tick) {
			tick = now;
			places = 0;
			requested = false;
		}
	}

	/**
	 * The click to place with, or null to wait: {@code planned} has to be met by the rotation the
	 * server has; if it isn't, the first such placement this tick turns there.
	 */
	public static BlockHitResult placement(BlockHitResult planned) {
		roll();
		if (places >= PLACES_PER_TICK) return null;
		Vec3 eyes = mc.player.getEyePosition();
		Rotation server = FemboyWare.getRotations().getServerRotation();
		double range = mc.player.blockInteractionRange();
		BlockHitResult seen = mc.level.clip(new ClipContext(eyes, eyes.add(server.toVector().scale(range)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (seen.getType() == HitResult.Type.BLOCK && seen.getBlockPos().equals(planned.getBlockPos()) && seen.getDirection() == planned.getDirection()) {
			places++;
			return new BlockHitResult(seen.getLocation(), seen.getDirection(), seen.getBlockPos(), false);
		}
		turnTo(planned.getLocation());
		return null;
	}

	/** Turns to {@code point} in the next movement packet, unless a placement already asked to this tick. */
	public static void turnTo(Vec3 point) {
		roll();
		if (requested) return;
		requested = true;
		FemboyWare.getRotations().request(Rotation.between(mc.player.getEyePosition(), point), ROTATION_PRIORITY, 180f, false);
	}
}
