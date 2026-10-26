package dev.femboyware.manager;

import dev.femboyware.FemboyWare;
import dev.femboyware.mixin.ClientPlayerEntityAccessor;
import dev.femboyware.module.client.MoveFixModule;
import dev.femboyware.util.Rotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * Owns the rotation sent to the server. Modules request a rotation during pre-motion (start of
 * the player tick); the highest priority request wins and is written into the movement packet.
 *
 * <p>The rotation is decided before the player moves so movement can be computed with the same
 * yaw the server sees (see {@link MoveFixModule}), which keeps simulation anti-cheats happy.
 */
public class RotationManager {
	private final MinecraftClient mc = MinecraftClient.getInstance();

	private Rotation requested;
	private int requestedPriority = Integer.MIN_VALUE;
	private float requestedStep;
	private boolean requestedClientLook;
	private boolean requestedMoveFix;

	private Rotation serverRotation;
	/** A silent rotation is in effect for this tick. */
	private boolean silent;
	/** This tick's silent rotation wants movement steered by the server yaw. */
	private boolean silentMoveFix;
	private boolean swapped;
	private float savedYaw;
	private float savedPitch;

	/**
	 * @param maxStep largest change per tick on each axis, 180 for instant
	 * @param clientLook also turn the player's own camera
	 */
	public void request(Rotation rotation, int priority, float maxStep, boolean clientLook) {
		request(rotation, priority, maxStep, clientLook, true);
	}

	/**
	 * @param moveFix whether Move Fix should steer movement by this rotation while it's silent.
	 *                Off for rotations that are just for show, like spinning, so walking stays normal
	 */
	public void request(Rotation rotation, int priority, float maxStep, boolean clientLook, boolean moveFix) {
		if (priority < requestedPriority) return;
		requested = rotation;
		requestedPriority = priority;
		requestedStep = maxStep;
		requestedClientLook = clientLook;
		requestedMoveFix = moveFix;
	}

	/** The rotation the server currently believes we have. */
	public Rotation getServerRotation() {
		if (serverRotation != null) return serverRotation;
		return new Rotation(mc.player.getYaw(), mc.player.getPitch());
	}

	/** Whether the server rotation is within {@code tolerance} degrees of {@code target}. */
	public boolean isFacing(Rotation target, float tolerance) {
		return getServerRotation().distanceTo(target) <= tolerance;
	}

	/** Whether movement this tick should be computed with the server yaw instead of the camera yaw. */
	public boolean isMoveFixActive() {
		return silent && silentMoveFix && FemboyWare.getModules().get(MoveFixModule.class).isEnabled()
			&& !mc.player.hasVehicle() && !mc.player.isGliding();
	}

	public float getServerYaw() {
		return getServerRotation().yaw();
	}

	/** Start of the player tick, after modules made their requests: settle this tick's rotation. */
	public void prepare() {
		silent = false;
		if (requested == null) {
			serverRotation = null;
			return;
		}
		Rotation from = getServerRotation();
		Rotation next = from.stepTowards(requested, requestedStep, requestedStep);
		next = next.applyGcd(from, mc.options.getMouseSensitivity().getValue());
		serverRotation = next;

		if (requestedClientLook) {
			mc.player.setYaw(next.yaw());
			mc.player.setPitch(next.pitch());
		} else {
			silent = true;
			silentMoveFix = requestedMoveFix;
		}
		requested = null;
		requestedPriority = Integer.MIN_VALUE;
	}

	/**
	 * Sends {@code target} in its own look packet right now, for an action that can't wait for the
	 * next movement packet. Most servers accept the extra packet, but it counts towards Grim's
	 * timer check, so modules only do this when the user asks for it.
	 */
	public void sendPacketRotation(Rotation target) {
		Rotation from = getServerRotation();
		Rotation next = from.stepTowards(target, 180f, 180f).applyGcd(from, mc.options.getMouseSensitivity().getValue());
		mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(next.yaw(), next.pitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
		notePacketRotation(next);
	}

	/** Records a look packet that was sent outside this manager, e.g. from the network thread. */
	public void notePacketRotation(Rotation sent) {
		serverRotation = sent;
		// Vanilla compares against what it last sent, so it puts the camera rotation back next tick
		((ClientPlayerEntityAccessor) mc.player).femboyware$setLastYaw(sent.yaw());
		((ClientPlayerEntityAccessor) mc.player).femboyware$setLastPitch(sent.pitch());
	}

	/** Right before the movement packet: put the server rotation on the player so it gets sent. */
	public void applyBeforePacket() {
		if (!silent) return;
		savedYaw = mc.player.getYaw();
		savedPitch = mc.player.getPitch();
		mc.player.setYaw(serverRotation.yaw());
		mc.player.setPitch(serverRotation.pitch());
		swapped = true;
	}

	/** Restores the client camera after the packet went out. */
	public void restoreAfterPacket() {
		if (!swapped) return;
		mc.player.setYaw(savedYaw);
		mc.player.setPitch(savedPitch);
		swapped = false;
	}
}
