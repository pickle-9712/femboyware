package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.larp.ShapeBuilder.Station;
import dev.femboyware.module.utility.ServerLarpModule;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A full-size private jet (about 30 blocks long, like a Gulfstream) parked next to you. With Server
 * Larp on you can board it (right-click at the foot of its airstair, or {@code .jet fly}) and walk
 * round its cabin while it rolls down a runway, rotates, climbs out, flies a lap, lands and rolls to
 * a stop right back where it was. The windows are glass looking out at the real sky. You step back
 * out where you got on. The server never sees you move, so it only works while larping.
 */
public class JetModule extends Module {
	private final ColorSetting paint = add(new ColorSetting("Paint", "The jet's body colour", 0xFFF4F5F7));
	private final ColorSetting stripe = add(new ColorSetting("Stripe", "The cheat line down its side", 0xFFC9A14A));
	private final ColorSetting leather = add(new ColorSetting("Leather", "The cabin's seats", 0xFFEDE3CE));
	private final NumberSetting laps = add(new NumberSetting("Laps", "How many laps it flies before landing", 1, 1, 4, 1));
	private final dev.femboyware.setting.BoolSetting farGround = add(new dev.femboyware.setting.BoolSetting("Far Ground",
		"While flying, draws a stand-in ground where the server hasn't sent any (Server Larp keeps you where you boarded)", false));
	private final NumberSetting altitude = add(new NumberSetting("Altitude", "How high it cruises, in blocks", 45, 20, 90, 5));

	/** Cruise speed (blocks a second), the takeoff roll, where the wheels leave, the climb and descent, the final approach and the rollout. */
	private double SPEED = 30, RUNWAY = 130, LIFTOFF = 95, CLIMB = 300, DESCENT = 380, APPROACH = 220, ROLLOUT = 110, RADIUS = 70;

	/** Ticks between getting on and starting the takeoff roll, while the door shuts. */
	private static final int BOARDING_TICKS = 80;
	/** The cabin floor's height in the jet, and how far you can walk. */
	private static final float FLOOR = 1.75f, AISLE = 0.8f, BACK = -5.1f, FRONT = 10.9f;

	private Vec3 parked;
	private float parkedYaw;

	/** The flight path: straight lines and half circles, in order. */
	private record Segment(double startX, double startZ, double dirX, double dirZ, double centerX, double centerZ, double startAngle, double sweep, double length, boolean arc) {
		double[] point(double d) {
			if (!arc) return new double[]{startX + dirX * d, startZ + dirZ * d, dirX, dirZ};
			double angle = startAngle + sweep * d / length;
			double tangent = Math.signum(sweep);
			double radius = length / Math.abs(sweep);
			return new double[]{centerX + Math.cos(angle) * radius, centerZ + Math.sin(angle) * radius, -Math.sin(angle) * tangent, Math.cos(angle) * tangent};
		}
	}

	private final List<Segment> path = new ArrayList<>();
	private double pathLength;

	private boolean boarded;
	/** The airstair door: whether it's swinging open (or shut), and when it started. */
	private boolean doorOpening = true;
	private long doorChanged;
	private static final long DOOR_NANOS = 2_200_000_000L;
	private int ticks;
	private long lastTickNanos;
	private Vec3 boardedFrom;
	/** Where you're standing in the cabin, in the jet's own space (only x and z change), this tick and last. */
	private final Vector3f local = new Vector3f(), prevLocal = new Vector3f();
	/** The jet's heading at the last tick, so turning it turns you with it. */
	private float tickYaw;
	/** This frame's flight time, worked out once for the camera so the jet's drawn at exactly the same moment. */
	private double frameSeconds = -1;
	private int nextEngineTick;

	/** The captain: greets you at the foot of the airstair while it's parked, flies it from the left seat. */
	private net.minecraft.client.player.RemotePlayer pilot;
	private static final int PILOT_ID = -31000;
	public static final net.minecraft.world.entity.player.PlayerSkin CAPTAIN = new net.minecraft.world.entity.player.PlayerSkin(
		new net.minecraft.core.ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("femboyware", "pilot_skin"),
			Identifier.fromNamespaceAndPath("femboyware", "textures/larp/pilot_skin.png")), null, null, net.minecraft.world.entity.player.PlayerModelType.WIDE, true);

	public JetModule() {
		super("Private Jet", "A full-size private jet you can board and walk round while it flies, while larping. Use " + CommandManager.PREFIX + "jet", Category.LARP);
	}

	@Override
	public String getInfo() {
		return boarded ? (ticks < BOARDING_TICKS ? "Boarding" : "Flying") : parked != null ? "Parked" : null;
	}

	/** Whether a jet's parked, for the emote wheel to offer boarding it. */
	public boolean parked() {
		return parked != null && !boarded;
	}

	private static boolean larping() {
		return FemboyWare.getModules().get(ServerLarpModule.class).isLarping();
	}

	// ---------------------------------------------------------------- commands

	/** Parks the jet at {@code at} facing {@code yaw} (squared to a compass direction), for the hangar. */
	public void parkAt(Vec3 at, float yaw) {
		if (boarded) return;
		parked = at;
		parkedYaw = Math.round(yaw / 90f) * 90f;
		doorOpening = false;
		doorChanged = 0;
		swingDoor(true);
		if (!isEnabled()) setEnabled(true);
	}

	/** Takes the jet away (and the captain), quietly, for the hangar. */
	public void clear() {
		if (boarded) disembark(null);
		parked = null;
		removePilot();
	}

	/** Handles {@code .jet [spawn|fly|land|remove]}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "spawn";
		switch (action) {
			case "spawn", "park" -> {
				if (mc.player == null) return;
				if (boarded) {
					ChatUtil.info("Land first");
					return;
				}
				// Off to your left, pointing the way you face, its airstair coming down right by you
				// Squared to the nearest compass direction, so its collision boxes fit it exactly
				float yaw = Math.round(mc.player.getYRot() / 90f) * 90f;
				double rad = Math.toRadians(yaw);
				double rightX = -Math.cos(rad), rightZ = -Math.sin(rad), fwdX = -Math.sin(rad), fwdZ = Math.cos(rad);
				parked = mc.player.position().add(-rightX * 5.5 - fwdX * 9.9, 0, -rightZ * 5.5 - fwdZ * 9.9);
				parkedYaw = yaw;
				// It arrives shut, then the airstair comes down
				doorOpening = false;
				doorChanged = 0;
				swingDoor(true);
				if (!isEnabled()) setEnabled(true);
				ChatUtil.info("Your jet's parked. " + (larping() ? "Walk up the stairs, then right-click the jet to take off" : "Turn on Server Larp to board and fly it"));
			}
			case "fly", "board" -> board();
			case "land", "stop" -> {
				if (boarded) disembark("Back on the ground");
			}
			case "remove", "clear" -> {
				if (boarded) disembark(null);
				parked = null;
				removePilot();
				ChatUtil.info("Jet's gone back to the hangar");
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "jet <spawn|fly|land|remove>");
		}
	}

	// ---------------------------------------------------------------- flying

	private void board() {
		if (parked == null) {
			ChatUtil.info("Park a jet first: " + CommandManager.PREFIX + "jet spawn");
			return;
		}
		if (!larping()) {
			ChatUtil.info("The jet only flies while Server Larp is on, so the server never sees you leave");
			return;
		}
		if (boarded) return;
		buildPath();
		boarded = true;
		ticks = 0;
		lastTickNanos = System.nanoTime();
		boardedFrom = mc.player.position();
		tickYaw = parkedYaw;
		Vector3f in = toJet(mc.player.position());
		if (inCabin(in)) {
			// Already walked aboard: take off from right where you're standing
			local.set(Mth.clamp(in.x, -AISLE, AISLE), FLOOR, Mth.clamp(in.z, BACK, FRONT));
		} else {
			// Just inside the door, facing down the cabin
			local.set(-0.6f, FLOOR, 9.9f);
			mc.player.setYRot(parkedYaw + 180);
		}
		prevLocal.set(local);
		mc.player.setNoGravity(true);
		nextEngineTick = BOARDING_TICKS - 30;
		swingDoor(false);
		dev.femboyware.util.Mc.addChat(net.minecraft.network.chat.Component.literal("<Captain> Welcome aboard. Doors are closing, we'll be wheels up shortly."));
	}

	private void disembark(String message) {
		disembark(message, false);
	}

	/**
	 * Off the flight. Landed properly ({@code stayAboard}), you're left stood in the parked cabin
	 * where you'd walked to; cut short, you're back where you got on, with the door open.
	 */
	private void disembark(String message, boolean stayAboard) {
		boarded = false;
		frameSeconds = -1;
		if (!stayAboard) {
			doorOpening = true;
			doorChanged = System.nanoTime() - DOOR_NANOS;
		}
		if (mc.player == null) return;
		mc.player.setNoGravity(false);
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.fallDistance = 0;
		Vec3 to = boardedFrom;
		if (stayAboard) {
			Vector3f spot = new Vector3f(local.x, FLOOR, local.z).rotate(new Quaternionf().rotateY((float) Math.toRadians(-parkedYaw)));
			to = parked.add(spot.x, spot.y, spot.z);
		}
		if (to != null) mc.player.snapTo(to.x, to.y, to.z, mc.player.getYRot(), mc.player.getXRot());
		if (message != null) ChatUtil.info(message);
	}

	/** Starts the door swinging open or shut, from wherever it is now. */
	private void swingDoor(boolean open) {
		if (doorOpening == open) return;
		float now = doorAmount();
		doorOpening = open;
		// Carry on from where it got to, rather than jumping
		float from = open ? now : 1 - now;
		doorChanged = System.nanoTime() - (long) (from * DOOR_NANOS);
	}

	/** How open the door is, 0 shut to 1 fully down as an airstair. */
	private float doorAmount() {
		if (doorOverride >= 0) return doorOverride;
		float progress = Mth.clamp((System.nanoTime() - doorChanged) / (float) DOOR_NANOS, 0, 1);
		return doorOpening ? progress : 1 - progress;
	}

	/**
	 * A racetrack lap from the parking spot: down the runway, a right turn, back up the far side, a
	 * right turn onto a long final, and the landing roll ending exactly where it started. Extra laps
	 * fly over the runway and go round again.
	 */
	private void buildPath() {
		path.clear();
		double rad = Math.toRadians(parkedYaw);
		double fx = -Math.sin(rad), fz = Math.cos(rad), rx = -Math.cos(rad), rz = -Math.sin(rad);
		double sx = parked.x, sz = parked.z;
		addLine(sx, sz, fx, fz, RUNWAY);
		int count = laps.getInt();
		for (int lap = 0; lap < count; lap++) {
			double bx = sx + fx * RUNWAY, bz = sz + fz * RUNWAY;
			addArc(bx + rx * RADIUS, bz + rz * RADIUS, bx, bz, fx, fz);
			double cx = bx + rx * 2 * RADIUS, cz = bz + rz * 2 * RADIUS;
			addLine(cx, cz, -fx, -fz, RUNWAY + APPROACH);
			double dx = cx - fx * (RUNWAY + APPROACH), dz = cz - fz * (RUNWAY + APPROACH);
			addArc(dx - rx * RADIUS, dz - rz * RADIUS, dx, dz, -fx, -fz);
			double tx = sx - fx * APPROACH, tz = sz - fz * APPROACH;
			// Over the runway and round again, or down onto it
			addLine(tx, tz, fx, fz, lap == count - 1 ? APPROACH : APPROACH + RUNWAY);
		}
		pathLength = 0;
		for (Segment s : path) pathLength += s.length;
	}

	private void addLine(double x, double z, double dx, double dz, double length) {
		path.add(new Segment(x, z, dx, dz, 0, 0, 0, 0, length, false));
	}

	/** A half circle round (cx, cz) starting at (x, z) heading (dx, dz). */
	private void addArc(double cx, double cz, double x, double z, double dx, double dz) {
		double start = Math.atan2(z - cz, x - cx);
		double sign = Math.signum(-Math.sin(start) * dx + Math.cos(start) * dz);
		path.add(new Segment(0, 0, 0, 0, cx, cz, start, sign * Math.PI, Math.PI * RADIUS, true));
	}

	private double rollTime() {
		return 2 * RUNWAY / SPEED;
	}

	private double stopTime() {
		return 2 * ROLLOUT / SPEED;
	}

	private double flightTime() {
		return rollTime() + (pathLength - RUNWAY - ROLLOUT) / SPEED + stopTime();
	}

	/** How far along the path it is after {@code seconds}: speeding up down the runway, slowing to a stop after touchdown. */
	private double distanceAt(double seconds) {
		double roll = rollTime(), cruiseEnd = roll + (pathLength - RUNWAY - ROLLOUT) / SPEED;
		if (seconds <= 0) return 0;
		if (seconds < roll) return 0.5 * (SPEED / roll) * seconds * seconds;
		if (seconds < cruiseEnd) return RUNWAY + SPEED * (seconds - roll);
		double tau = Math.min(seconds - cruiseEnd, stopTime());
		return pathLength - ROLLOUT + SPEED * tau - 0.5 * (SPEED / stopTime()) * tau * tau;
	}

	private double touchdown() {
		return pathLength - ROLLOUT;
	}

	/** Height above the ground at distance {@code d}: a long shallow climb after liftoff, cruise with a gentle rise and fall, and a long descent to touchdown. */
	private double heightAt(double d) {
		double cruise = altitude.get(), touchdown = touchdown();
		if (d < LIFTOFF || d >= touchdown) return 0;
		double up = smooth((d - LIFTOFF) / CLIMB), down = smooth((touchdown - d) / DESCENT);
		return Math.min(up, down) * (cruise + 3 * Math.sin(d * 0.02));
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	/** Where the jet is and how it's turned: position, yaw, pitch, roll (degrees), and whether its gear's down. */
	private record Pose(Vec3 pos, float yaw, float pitch, float roll, boolean gearDown) {}

	private Pose poseAt(double d) {
		double left = d;
		Segment segment = path.getLast();
		for (Segment s : path) {
			if (left <= s.length) {
				segment = s;
				break;
			}
			left -= s.length;
		}
		double[] p = segment.point(Math.min(left, segment.length));
		double height = heightAt(d), slope = (heightAt(d + 2) - height) / 2;
		float yaw = (float) Math.toDegrees(Math.atan2(-p[2], p[3]));
		// Nose up with the climb; nearly level coming down, like a real approach
		float pitch = (float) Math.toDegrees(Math.atan(slope));
		if (pitch < 0) pitch *= 0.35f;
		// Rotating: the nose comes up just before the wheels leave the runway
		if (d > LIFTOFF - 30 && d < LIFTOFF + 70) pitch = Math.max(pitch, (float) (8 * smooth((d - (LIFTOFF - 30)) / 30)));
		// The flare before touchdown, and the nose settling after
		double touchdown = touchdown();
		if (d > touchdown - 45 && d < touchdown) pitch = Math.max(pitch, (float) (4 * smooth((d - (touchdown - 45)) / 35)));
		if (d >= touchdown && d < touchdown + 25) pitch = (float) (4 * (1 - smooth((d - touchdown) / 25)));
		// Bank into the turns, easing in and out at their ends
		float roll = 0;
		if (segment.arc) {
			double through = left / segment.length;
			roll = (float) (Math.signum(segment.sweep) * -25 * smooth(Math.min(through, 1 - through) * 5));
		}
		return new Pose(new Vec3(p[0], parked.y + height, p[1]), yaw, pitch, roll, height < 12);
	}

	/** Seconds into the flight itself (after boarding) at a given tick count plus fraction. */
	private double flightSeconds(double tickTime) {
		return Math.max(0, tickTime - BOARDING_TICKS) / 20;
	}

	private Pose poseAtSeconds(double seconds) {
		return poseAt(distanceAt(Math.min(seconds, flightTime())));
	}

	/** Where the jet is right now, between ticks, smoothly. */
	private double smoothSeconds() {
		double fraction = Mth.clamp((System.nanoTime() - lastTickNanos) / 50_000_000.0, 0, 1);
		return flightSeconds(ticks + fraction);
	}

	private Pose renderPose() {
		if (!boarded) return new Pose(parked, parkedYaw, 0, 0, true);
		return poseAtSeconds(frameSeconds >= 0 ? frameSeconds : smoothSeconds());
	}

	private static Quaternionf rotation(Pose pose) {
		return new Quaternionf().rotateY((float) Math.toRadians(-pose.yaw)).rotateX((float) Math.toRadians(-pose.pitch)).rotateZ((float) Math.toRadians(pose.roll));
	}

	@Override
	public void onTick() {
		if (parked == null || mc.player == null) return;
		if (!boarded) {
			boolean aboard = inCabin(toJet(mc.player.position()));
			if (aboard && !wasAboard && larping()) ChatUtil.info("Welcome aboard. Right-click when you're ready to take off");
			wasAboard = aboard;
			// Right-click the jet to take off: stood inside it, or aiming at it from close by
			if (larping() && dev.femboyware.util.Mc.screen() == null && (aboard || aimingAtJet())) {
				boolean clicked = false;
				while (mc.options.keyUse.consumeClick()) clicked = true;
				if (clicked) board();
			}
			return;
		}
		if (!larping()) {
			disembark("Server Larp went off, so you're back on the ground");
			return;
		}
		ticks++;
		lastTickNanos = System.nanoTime();
		double seconds = flightSeconds(ticks);
		if (seconds >= flightTime() && !doorOpening) swingDoor(true);
		if (seconds >= flightTime() + DOOR_NANOS / 1e9 + 0.3) {
			disembark(null, true);
			dev.femboyware.util.Mc.addChat(net.minecraft.network.chat.Component.literal("<Captain> Welcome home. It was a pleasure flying you."));
			return;
		}
		if (ticks == BOARDING_TICKS) ChatUtil.info("Cleared for takeoff");
		Pose pose = poseAtSeconds(seconds);

		// The jet turning turns you with it
		mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(pose.yaw - tickYaw));
		tickYaw = pose.yaw;

		// Walking round the cabin, in the jet's own space
		prevLocal.set(local);
		Vec2 input = mc.player.input.getMoveVector();
		if (input.lengthSquared() > 1) input = input.normalized();
		float speed = mc.options.keySprint.isDown() ? 0.26f : 0.18f;
		double facing = Math.toRadians(mc.player.getYRot() - pose.yaw);
		float fwdX = (float) -Math.sin(facing), fwdZ = (float) Math.cos(facing), leftX = (float) Math.cos(facing), leftZ = (float) Math.sin(facing);
		local.x = Mth.clamp(local.x + (fwdX * input.y + leftX * input.x) * speed, -AISLE, AISLE);
		local.z = Mth.clamp(local.z + (fwdZ * input.y + leftZ * input.x) * speed, BACK, FRONT);

		Vector3f at = new Vector3f(local.x, FLOOR, local.z).rotate(rotation(pose));
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.setPos(pose.pos.x + at.x, pose.pos.y + at.y, pose.pos.z + at.z);
		mc.player.setOnGround(true);
		mc.player.fallDistance = 0;

		if (--nextEngineTick <= 0) {
			LarpSounds.play(LarpSounds.JET_ENGINE, 0.4f);
			nextEngineTick = 70;
		}
	}

	private boolean wasAboard;

	/** A world point in the parked jet's own space. */
	private Vector3f toJet(Vec3 world) {
		Vec3 d = world.subtract(parked);
		return new Vector3f((float) d.x, (float) d.y, (float) d.z).rotate(new Quaternionf().rotateY((float) Math.toRadians(parkedYaw)));
	}

	/** Whether a point (jet space) is stood in the cabin or cockpit. */
	private static boolean inCabin(Vector3f p) {
		return Math.abs(p.x) < 1.35f && p.z > -5.6f && p.z < 12.7f && p.y > FLOOR - 0.3f && p.y < 4.3f;
	}

	/** Where the door is along the jet: the airstair comes down from it and the walls open for it. */
	private static final float DOOR_FRONT = 10.35f, DOOR_BACK = 9.45f, DOOR_TOP = -0.62f, DOOR_SILL = -2.13f;

	/**
	 * The parked jet as solid boxes in the world: its airstair, the cabin floor and walls with the
	 * doorway open, the cockpit, nose and tail. Exact, since it always parks facing a compass direction.
	 */
	private List<net.minecraft.world.phys.AABB> worldBoxes() {
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-parkedYaw));
		List<float[]> boxes = new ArrayList<>(List.of(
			new float[]{-1.4f, 1.0f, -6.0f, 1.4f, FLOOR, 11.0f},             // under the cabin floor
			new float[]{-1.0f, 1.2f, 11.0f, 1.0f, FLOOR, 12.6f},             // cockpit floor
			new float[]{-1.7f, FLOOR, -6.0f, -1.3f, 4.2f, DOOR_BACK},        // left wall, up to the door
			new float[]{-1.7f, FLOOR, DOOR_FRONT, -1.3f, 4.2f, 12.6f},       // left wall, past the door
			new float[]{-1.7f, 3.9f, DOOR_BACK, -1.3f, 4.2f, DOOR_FRONT},    // over the door
			new float[]{1.3f, FLOOR, -6.0f, 1.7f, 4.2f, 12.6f},              // right wall
			new float[]{-1.7f, 4.1f, -6.0f, 1.7f, 4.4f, 12.6f},              // roof
			new float[]{-1.4f, FLOOR, -5.65f, 1.4f, 4.2f, -5.4f},            // back wall
			new float[]{-1.0f, FLOOR, 11.95f, 1.0f, FLOOR + 1.1f, 12.6f},    // instrument panel
			new float[]{0.55f, FLOOR, 8.95f, 1.4f, FLOOR + 0.95f, 10.45f},   // galley
			new float[]{-1.0f, 1.3f, 12.6f, 1.0f, 3.6f, 14.8f},              // nose
			new float[]{-1.3f, 1.4f, -14.0f, 1.3f, 4.0f, -6.0f}));           // tail
		for (int i = 0; i < 7; i++) {
			float x = -1.36f - i * 0.33f;
			boxes.add(new float[]{x - 0.33f, 0, DOOR_BACK + 0.05f, x, FLOOR - i * 0.25f, DOOR_FRONT - 0.05f});
		}
		// The wings: swept and angled up, so a slice of box per block of span, following the sweep and the rise
		for (int side = -1; side <= 1; side += 2) {
			for (float x0 = 1.3f; x0 < 14.5f; x0 += 1) {
				float x1 = Math.min(x0 + 1, 14.5f);
				float[] a = wingAt(x0), c = wingAt(x1);
				float thick = Math.max(a[3], c[3]) / 2;
				boxes.add(new float[]{Math.min(side * x0, side * x1), Math.min(a[2], c[2]) - thick, Math.min(a[1], c[1]),
					Math.max(side * x0, side * x1), Math.max(a[2], c[2]) + thick, Math.max(a[0], c[0])});
			}
			// Winglet, and the engine on its pylon
			boxes.add(new float[]{Math.min(side * 14.4f, side * 15.15f), 2.3f, -8.1f, Math.max(side * 14.4f, side * 15.15f), 4.7f, -5.6f});
			boxes.add(new float[]{Math.min(side * 1.3f, side * 3.8f), 2.8f, -11.3f, Math.max(side * 1.3f, side * 3.8f), 4.65f, -6.5f});
		}
		List<net.minecraft.world.phys.AABB> world = new ArrayList<>();
		for (float[] box : boxes) {
			Vector3f a = new Vector3f(box[0], box[1], box[2]).rotate(turn), c = new Vector3f(box[3], box[4], box[5]).rotate(turn);
			world.add(new net.minecraft.world.phys.AABB(parked.x + a.x, parked.y + a.y, parked.z + a.z, parked.x + c.x, parked.y + c.y, parked.z + c.z));
		}
		return world;
	}

	/** The wing's leading edge z, trailing edge z, middle height and thickness, {@code x} out from the middle. */
	private static float[] wingAt(float x) {
		if (x < 6) {
			float t = (x - 1.3f) / 4.7f;
			return new float[]{3.0f - 3.06f * t, -2.8f - 1.5f * t, 1.4f + 0.36f * t, 0.3f};
		}
		float t = (x - 6) / 8.5f;
		return new float[]{-0.06f - 5.54f * t, -4.3f - 2.7f * t, 1.76f + 0.64f * t, 0.18f};
	}

	/**
	 * Called from EntityMixin as you move: the parked jet's boxes, so you can walk up the airstair and
	 * round inside. Only for you, and only while Server Larp is on, since the server can't see the jet.
	 */
	public List<net.minecraft.world.phys.shapes.VoxelShape> collisions(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.AABB area) {
		List<net.minecraft.world.phys.shapes.VoxelShape> shapes = new ArrayList<>();
		if (!isEnabled() || parked == null || boarded || !LarpProps.solidFor(entity)) return shapes;
		if (parked.distanceToSqr(entity.position()) > 30 * 30) return shapes;
		for (net.minecraft.world.phys.AABB box : worldBoxes()) {
			if (box.intersects(area)) shapes.add(net.minecraft.world.phys.shapes.Shapes.create(box));
		}
		return shapes;
	}

	/** Whether you're aiming at the parked jet from within reach. */
	private boolean aimingAtJet() {
		if (parked.distanceToSqr(mc.player.position()) > 30 * 30) return false;
		Vec3 eye = mc.player.getEyePosition(), reach = eye.add(mc.player.getViewVector(1).scale(6));
		for (net.minecraft.world.phys.AABB box : worldBoxes()) {
			if (box.contains(eye) || box.clip(eye, reach).isPresent()) return true;
		}
		return false;
	}

	@Override
	protected void onDisable() {
		if (boarded) disembark(null);
		removePilot();
	}

	// ---------------------------------------------------------------- the pilot

	/** For the skin mixin: the captain wears his uniform. */
	public net.minecraft.world.entity.player.PlayerSkin skinFor(net.minecraft.client.player.AbstractClientPlayer player, net.minecraft.world.entity.player.PlayerSkin textures) {
		return pilot != null && player == pilot ? CAPTAIN : textures;
	}

	private boolean pilotSeated() {
		return boarded;
	}

	/**
	 * Puts the captain where he belongs this frame: at the foot of the airstair facing out while it's
	 * parked, or in the left seat of the cockpit while you fly, moving with the jet exactly (his old
	 * position set too, so he doesn't lag a tick behind it).
	 */
	private void placePilot() {
		if (!isEnabled() || parked == null || mc.level == null) {
			removePilot();
			return;
		}
		if (pilot == null || pilot.level() != mc.level || pilot.isRemoved()) {
			pilot = new net.minecraft.client.player.RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Captain"));
			pilot.setId(PILOT_ID);
			mc.level.addEntity(pilot);
		}
		Vec3 at;
		float yaw;
		if (pilotSeated()) {
			Pose pose = renderPose();
			Vector3f seat = new Vector3f(0.55f, FLOOR + 0.52f - 0.75f, 11.45f).rotate(rotation(pose));
			at = pose.pos.add(seat.x, seat.y, seat.z);
			yaw = pose.yaw;
		} else {
			Vector3f foot = new Vector3f(-4.3f, 0, 10.6f).rotate(new Quaternionf().rotateY((float) Math.toRadians(-parkedYaw)));
			at = parked.add(foot.x, 0, foot.z);
			// Facing out, a little toward the stairs, to greet you
			yaw = parkedYaw + 70;
		}
		pilot.setPos(at.x, at.y, at.z);
		pilot.setOldPosAndRot();
		pilot.setYRot(yaw);
		pilot.yRotO = yaw;
		pilot.yBodyRot = yaw;
		pilot.yBodyRotO = yaw;
		pilot.yHeadRot = yaw;
		pilot.yHeadRotO = yaw;
		pilot.setXRot(0);
	}

	private void removePilot() {
		if (pilot != null && mc.level != null && pilot.level() == mc.level) mc.level.removeEntity(pilot.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		pilot = null;
	}

	/** Called from PlayerModelMixin: the captain sits in his seat, hands on the controls. */
	public void posePilot(net.minecraft.client.model.player.PlayerModel model, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
		if (pilot == null || state.id != PILOT_ID || !pilotSeated()) return;
		poseSeatedPilot(model);
	}

	/** The camera's full turn this frame, roll and all, for CameraMixin to apply; null when it isn't ours. */
	private Quaternionf cameraRotation;

	public Quaternionf cameraRotation() {
		return cameraRotation;
	}

	/**
	 * For CameraMixin. In first person, your eyes in the cabin at this exact moment, turned with the
	 * jet as it climbs, banks and descends, so you glide with it and stay inside it. In third person,
	 * a camera orbiting the jet that you swing round with the mouse.
	 */
	public CinematicCameraModule.View view(float tickProgress) {
		cameraRotation = null;
		placePilot();
		if (!boarded || !isEnabled() || mc.player == null) {
			frameSeconds = -1;
			return null;
		}
		frameSeconds = smoothSeconds();
		Pose pose = poseAtSeconds(frameSeconds);
		// Where you're looking, as the jet turns you: your own look plus how far it's turned since the tick
		float lookYaw = mc.player.getViewYRot(tickProgress) + Mth.wrapDegrees(pose.yaw - tickYaw), lookPitch = mc.player.getViewXRot(tickProgress);
		if (!mc.options.getCameraType().isFirstPerson()) {
			// Orbiting the jet: the mouse swings you round it, and it carries you along with its turns
			boolean front = mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
			float yaw = front ? lookYaw + 180 : lookYaw, pitch = front ? -lookPitch : lookPitch;
			double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
			Vec3 lookDir = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
			Vec3 target = pose.pos.add(0, 3, 0);
			Vec3 cam = target.subtract(lookDir.scale(32));
			// Never under the ground it's sat on
			if (cam.y < parked.y + 0.6) cam = new Vec3(cam.x, parked.y + 0.6, cam.z);
			Vec3 look = target.subtract(cam);
			float camYaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
			float camPitch = (float) Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
			return new CinematicCameraModule.View(cam, camYaw, camPitch);
		}
		// First person: the eye placed in the cabin and turned with it, then your look on top
		Quaternionf jet = rotation(pose);
		Vector3f eye = new Vector3f(Mth.lerp(tickProgress, prevLocal.x, local.x), FLOOR + mc.player.getEyeHeight(), Mth.lerp(tickProgress, prevLocal.z, local.z)).rotate(jet);
		float relativeYaw = lookYaw - pose.yaw;
		// Vanilla turns the camera by yaw Y and pitch P as rotY(-Y) rotX(P) rotY(180); in the jet's own space likewise
		cameraRotation = new Quaternionf(jet).rotateY((float) Math.toRadians(-relativeYaw)).rotateX((float) Math.toRadians(lookPitch)).rotateY((float) Math.PI);
		// The plain yaw and pitch it works out to, for anything that reads those
		Vector3f forward = new Vector3f(0, 0, -1).rotate(cameraRotation);
		float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
		float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(forward.y, -1, 1)));
		return new CinematicCameraModule.View(pose.pos.add(eye.x, eye.y, eye.z), yaw, pitch);
	}

	// ---------------------------------------------------------------- the jet

	/**
	 * The fuselage, nose to tail: a slim pointed radome, then the windshield on a steep rise up to the
	 * cabin roof, the cockpit's side windows, the long round cabin, and the tail sweeping up.
	 */
	private static final Station[] FUSELAGE = {
		new Station(15.1f, 0.04f, 2.15f), new Station(14.8f, 0.38f, 2.18f), new Station(14.3f, 0.68f, 2.25f), new Station(13.6f, 0.92f, 2.33f),
		new Station(12.95f, 1.06f, 2.4f), new Station(12.35f, 1.45f, 2.55f), new Station(11.0f, 1.6f, 2.6f),
		new Station(10.35f, 1.6f, 2.6f), new Station(9.45f, 1.6f, 2.6f),
		new Station(-6.0f, 1.6f, 2.6f), new Station(-9.0f, 1.38f, 2.85f),
		new Station(-11.5f, 0.98f, 3.15f), new Station(-13.5f, 0.5f, 3.4f), new Station(-14.6f, 0.12f, 3.5f)};
	private static final int SIDES = 48;
	private static final float HULL = 1.6f, WALL = 1.5f, CENTER = 2.6f;
	/** The cabin windows: where along, the round-from-the-top angle of their middle, and the oval's and the hole's half sizes. */
	private static final float[] WINDOWS = new float[14];
	private static final float WINDOW_ANGLE = 1.309f, OVAL_Z = 0.26f, OVAL_A = 0.22f, HOLE_Z = 0.33f, HOLE_A = 0.262f;

	static {
		for (int i = 0; i < WINDOWS.length; i++) WINDOWS[i] = 9.0f - i;
	}

	/** The windshield panes, by fuselage segment and angle round: two front panes and a side pane each side, then the side windows. */
	/** The fuselage segment the door's in, and the first of the tail. */
	private static final int DOOR_SEGMENT = 7, TAIL_STATION = 9;

	/** Whether the door's open there: its segment, on the left, sill to top. */
	private static boolean doorway(int segment, double angle) {
		return segment == DOOR_SEGMENT && angle > DOOR_SILL && angle < DOOR_TOP;
	}

	/** The black frame round the windshield: the centre post, the pillars and the edges, as hull faces. */
	private static int windshieldFrame(int segment, double angle) {
		double a = Math.abs(angle);
		boolean frame = segment == 4 && (a < 0.13 || (a > 0.654 && a < 0.785) || (a > 1.309 && a < 1.44))
			|| segment == 5 && ((a > 0.785 && a < 0.916) || (a > 1.309 && a < 1.44));
		return frame ? 0xFF121418 : 0;
	}

	private static boolean windshield(int segment, double angle) {
		double a = Math.abs(angle);
		if (segment == 4) return (a > 0.13 && a < 0.654) || (a > 0.785 && a < 1.309);
		if (segment == 5) return a > 0.916 && a < 1.309;
		return false;
	}

	/** Who's flying in the jet right now (you and the captain), for FarGround to keep drawn. */
	public List<net.minecraft.world.entity.Entity> flyers() {
		if (!boarded || mc.player == null) return List.of();
		return pilot != null ? List.of(mc.player, pilot) : List.of(mc.player);
	}

	// ---------------------------------------------------------------- Client Sync

	/** Someone else's colours (paint, stripe, leather) and door while their jet's drawn, or null and -1 for yours. */
	private int[] look;
	private float doorOverride = -1;

	/** For Client Sync: your jet as it's drawn now (x, y, z, yaw, pitch, roll, door, gear down, flying, boarded), or null. */
	public double[] shareState() {
		if (!isEnabled() || parked == null) return null;
		Pose pose = renderPose();
		return new double[]{pose.pos.x, pose.pos.y, pose.pos.z, pose.yaw, pose.pitch, pose.roll, doorAmount(), pose.gearDown ? 1 : 0,
			boarded && ticks >= BOARDING_TICKS ? 1 : 0, boarded ? 1 : 0};
	}

	/** Someone else's parked jet as solid boxes, so you can walk up its airstair and round its cabin; nothing while it's flying. */
	public List<net.minecraft.world.phys.shapes.VoxelShape> sharedCollisions(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.AABB area, double[] s) {
		List<net.minecraft.world.phys.shapes.VoxelShape> shapes = new ArrayList<>();
		if (s[9] > 0.5 || new Vec3(s[0], s[1], s[2]).distanceToSqr(entity.position()) > 30 * 30) return shapes;
		Vec3 wasParked = parked;
		float wasYaw = parkedYaw;
		parked = new Vec3(s[0], s[1], s[2]);
		parkedYaw = (float) s[3];
		try {
			for (net.minecraft.world.phys.AABB box : worldBoxes()) {
				if (box.intersects(area)) shapes.add(net.minecraft.world.phys.shapes.Shapes.create(box));
			}
		} finally {
			parked = wasParked;
			parkedYaw = wasYaw;
		}
		return shapes;
	}

	/** A world point in a shared jet's own space, tilt and roll and all. */
	public static Vector3f sharedLocal(double[] s, Vec3 world) {
		Pose pose = sharedPose(s);
		Vec3 d = world.subtract(pose.pos);
		return new Vector3f((float) d.x, (float) d.y, (float) d.z).rotate(new Quaternionf(rotation(pose)).conjugate());
	}

	/** Whether a point in a jet's own space is stood in its cabin or cockpit. */
	public static boolean sharedInCabin(Vector3f local) {
		return inCabin(local);
	}

	/** Where you can stand in a cabin: across the aisle, and from the back wall to the cockpit; returns {x, floor, z}. */
	public static float[] cabinSpot(float x, float z) {
		return new float[]{Mth.clamp(x, -AISLE, AISLE), FLOOR, Mth.clamp(z, BACK, FRONT)};
	}

	/** Where the captain of someone else's jet is (x, y, z, yaw): in the left seat once it's boarded, otherwise greeting at the airstair. */
	public static double[] sharedPilot(double[] s) {
		Pose pose = sharedPose(s);
		if (s[9] > 0.5) {
			Vector3f seat = new Vector3f(0.55f, FLOOR + 0.52f - 0.75f, 11.45f).rotate(rotation(pose));
			return new double[]{pose.pos.x + seat.x, pose.pos.y + seat.y, pose.pos.z + seat.z, pose.yaw};
		}
		Vector3f foot = new Vector3f(-4.3f, 0, 10.6f).rotate(new Quaternionf().rotateY((float) Math.toRadians(-pose.yaw)));
		return new double[]{pose.pos.x + foot.x, pose.pos.y, pose.pos.z + foot.z, pose.yaw + 70};
	}

	/** The captain's sat at the controls: for other people's captains too, from Client Sync. */
	public static void poseSeatedPilot(net.minecraft.client.model.player.PlayerModel model) {
		model.rightLeg.xRot = -1.45f;
		model.leftLeg.xRot = -1.45f;
		model.rightLeg.yRot = 0.1f;
		model.leftLeg.yRot = -0.1f;
		model.rightArm.xRot = -0.9f;
		model.leftArm.xRot = -0.9f;
		model.rightArm.zRot = 0;
		model.leftArm.zRot = 0;
	}

	public int[] shareLook() {
		return new int[]{paint.get(), stripe.get(), leather.get()};
	}

	/** For Client Sync: where you're stood in the flying jet, in its own space, or null if you're not aboard. */
	public float[] shareSeat() {
		return boarded ? new float[]{local.x, FLOOR, local.z} : null;
	}

	/** Someone else's jet, from what {@link #shareState} and {@link #shareLook} gave on their end. */
	public void drawShared(Render3DEvent event, double[] s, int[] colors) {
		int[] wasLook = look;
		float wasDoor = doorOverride;
		look = colors.length >= 3 ? colors : null;
		doorOverride = (float) Mth.clamp(s[6], 0, 1);
		try {
			drawPose(event, sharedPose(s), false, s[8] > 0.5);
		} finally {
			look = wasLook;
			doorOverride = wasDoor;
		}
	}

	private static Pose sharedPose(double[] s) {
		return new Pose(new Vec3(s[0], s[1], s[2]), (float) s[3], (float) s[4], (float) s[5], s[7] > 0.5);
	}

	/** Where someone stood at {@code seat} (jet space) in a shared jet is in the world. */
	public static Vec3 sharedSeat(double[] s, float[] seat) {
		Pose pose = sharedPose(s);
		Vector3f at = new Vector3f(seat[0], seat[1], seat[2]).rotate(rotation(pose));
		return pose.pos.add(at.x, at.y, at.z);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (parked == null) return;
		Pose pose = renderPose();
		if (boarded && farGround.get()) FarGround.draw(event, parked.y, net.minecraft.core.BlockPos.containing(parked));
		frameSeconds = -1;
		drawPose(event, pose, boarded && mc.options.getCameraType().isFirstPerson(), boarded && ticks >= BOARDING_TICKS);
	}

	/** Draws the jet at {@code pose}; {@code riding} when you're aboard in first person, so the cabin's drawn round you. */
	private void drawPose(Render3DEvent event, Pose pose, boolean riding, boolean flying) {
		Vec3 cam = event.camera().pos;
		Quaternionf rot = rotation(pose);
		float ox = (float) (pose.pos.x - cam.x), oy = (float) (pose.pos.y - cam.y), oz = (float) (pose.pos.z - cam.z);
		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), rot, ox, oy, oz);
		ShapeBuilder in = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), rot, ox, oy, oz).soft();
		ShapeBuilder glass = new ShapeBuilder(null, event.matrices().last(), rot, ox, oy, oz);
		Vector3f camIn = new Vector3f((float) (cam.x - pose.pos.x), (float) (cam.y - pose.pos.y), (float) (cam.z - pose.pos.z)).rotate(new Quaternionf(rot).conjugate());
		boolean inside = inCabin(camIn) || riding;
		boolean near = cam.distanceToSqr(pose.pos) < 45 * 45;
		float door = doorAmount();
		// The airstair door turns about its hinge along the sill
		float swing = (float) Math.toRadians(-134.4 * (1 - smoothDoor(door)));
		Quaternionf doorTurn = new Quaternionf(rot).rotateZ(swing);
		Vector3f hinge = new Vector3f(-1.36f, FLOOR, 0), turned = new Vector3f(hinge).rotate(new Quaternionf().rotateZ(swing));
		Vector3f shift = new Vector3f(hinge).sub(turned).rotate(rot);
		ShapeBuilder doorBuilder = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), doorTurn, ox + shift.x, oy + shift.y, oz + shift.z);
		drawJet(b, glass, pose.gearDown, door, inside);
		if (door > 0) drawAirstair(doorBuilder, Mth.lerp(smoothDoor(door), 0.75f, 1f));
		drawCockpit(in);
		if (inside || near) drawCabin(in, glass, inside);
		{
			// The cockpit's own dark lining inside the nose, open where the windshield is
			in.loft(new Station[]{new Station(12.95f, 1.0f, 2.4f), new Station(12.35f, 1.39f, 2.55f), new Station(11.0f, 1.53f, 2.6f), new Station(10.5f, 1.53f, 2.6f)},
				SIDES, 0xFF4A4C52, -1, 0, 0, (segment, angle) -> segment < 2 && windshield(segment + 4, angle));
			// Closed off in front of the instruments, so you don't see into the nose cone
			float[][] front = new float[24][];
			for (int i = 0; i < 24; i++) {
				double a = Math.PI * 2 * i / 24;
				front[i] = new float[]{(float) Math.sin(a) * 1.02f, 2.4f + (float) Math.cos(a) * 1.02f, 12.93f};
			}
			in.plate(front, 0, 0, 1, 0.02f, 0xFF2A2C31);
		}
		glass.flush(FxRender.quads(event, false, false));

		// Lights: red on the left tip, green on the right, white strobes, red beacons, landing lights, cabin lights
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		long ms = System.currentTimeMillis();
		boolean strobe = ms % 1200 < 90, beacon = ms % 1000 < 400;
		light(event, glow, rot, ox, oy, oz, -14.6f, 2.45f, -6.0f, 0.4f, 0xF0FF2A2A);
		light(event, glow, rot, ox, oy, oz, 14.6f, 2.45f, -6.0f, 0.4f, 0xF02AFF4A);
		if (strobe) {
			light(event, glow, rot, ox, oy, oz, -14.7f, 2.45f, -6.6f, 0.9f, 0xF0FFFFFF);
			light(event, glow, rot, ox, oy, oz, 14.7f, 2.45f, -6.6f, 0.9f, 0xF0FFFFFF);
			light(event, glow, rot, ox, oy, oz, 0, 3.5f, -14.7f, 0.7f, 0xF0FFFFFF);
		}
		if (beacon && !inside) {
			light(event, glow, rot, ox, oy, oz, 0, 4.3f, 1.0f, 0.5f, 0xE0FF3030);
			light(event, glow, rot, ox, oy, oz, 0, 0.9f, 3.0f, 0.5f, 0xE0FF3030);
		}
		if (flying && pose.gearDown && !inside) light(event, glow, rot, ox, oy, oz, 0, 1.1f, 12.3f, 1.2f, 0xC0FFF6E0);
		if (flying) {
			// Hot exhaust glowing out of the engines
			for (int side = -1; side <= 1; side += 2) light(event, glow, rot, ox, oy, oz, side * 2.85f, 3.7f, -11.4f, 0.75f, 0x60FFA040);
		}
		if (inside) {
			// Warm reading lights over the seats
			for (float z = 8.6f; z > -5f; z -= 1.6f) {
				for (int side = -1; side <= 1; side += 2) light(event, glow, rot, ox, oy, oz, side * 0.95f, 3.85f, z, 0.16f, 0x80FFE2B0);
			}
		}
	}

	private static void light(Render3DEvent event, VertexConsumer glow, Quaternionf rot, float ox, float oy, float oz, float x, float y, float z, float size, int color) {
		Vector3f at = new Vector3f(x, y, z).rotate(rot);
		FxRender.billboard(event, glow, ox + at.x, oy + at.y, oz + at.z, size, 0, color);
	}

	/** A point on the cabin's round section: along z, round from the top (positive toward +x), at a radius. */
	private static float[] cyl(float z, double angle, float radius) {
		return new float[]{(float) Math.sin(angle) * radius, CENTER + (float) Math.cos(angle) * radius, z};
	}

	/** A point on a window's oval, {@code theta} round it, scaled by {@code k}, on side {@code side}, at a radius. */
	private static float[] oval(float z, int side, double theta, float k, float radius) {
		return cyl(z + OVAL_Z * k * (float) Math.cos(theta), side * (WINDOW_ANGLE - OVAL_A * k * Math.sin(theta)), radius);
	}

	/** A filled oval window on the hull's curve: a fan from its middle. */
	private static void ovalFill(ShapeBuilder b, float z, int side, float k, float radius, int color) {
		float[] middle = cyl(z, side * WINDOW_ANGLE, radius);
		int n = 24;
		for (int i = 0; i < n; i++) {
			b.flat(middle, oval(z, side, Math.PI * 2 * i / n, k, radius), oval(z, side, Math.PI * 2 * (i + 1) / n, k, radius), oval(z, side, Math.PI * 2 * (i + 1) / n, k, radius), color);
		}
	}

	/**
	 * A mid-size business jet at full size: a long fuselage with a gold cheat line and big oval
	 * windows, a wraparound windshield framed in black that you can see the cockpit through, low
	 * swept wings with tall winglets, two big engines on the tail, a T-tail, landing gear with twin
	 * wheels, and the door folded down as an airstair while parked. From inside, the cabin part of
	 * the fuselage is left off (the cabin's drawn instead).
	 */
	private static float smoothDoor(float t) {
		return t * t * (3 - 2 * t);
	}

	private void drawJet(ShapeBuilder b, ShapeBuilder glass, boolean gearDown, float door, boolean inside) {
		boolean doorOpen = door > 0;
		int p = look != null ? look[0] : paint.get(), line = look != null ? look[1] : stripe.get(), grey = ShapeBuilder.shade(p, 0.82f), dark = 0xFF1C1D22, metal = 0xFFB9BEC6;
		// The nose plain paint with its black windshield frame; the cheat line starts behind the cockpit
		ShapeBuilder.FaceColor nose = (segment, angle) -> {
			int frame = windshieldFrame(segment, angle);
			if (frame != 0) return frame;
			return segment <= 5 ? p : 0;
		};
		if (inside) {
			b.loft(Arrays.copyOfRange(FUSELAGE, 0, 7), SIDES, p, 1.6f, 1.8f, line, JetModule::windshield, nose);
			b.loft(Arrays.copyOfRange(FUSELAGE, TAIL_STATION, FUSELAGE.length), SIDES, p, 1.6f, 1.8f, line);
		} else {
			b.loft(FUSELAGE, SIDES, p, 1.6f, 1.8f, line, (segment, angle) -> windshield(segment, angle) || (doorOpen && doorway(segment, angle)), nose);
			// Oval cabin windows, dark glass with a glint
			for (float z : WINDOWS) {
				for (int side = -1; side <= 1; side += 2) {
					ovalFill(b, z, side, 1.08f, HULL + 0.012f, 0xFFD9DCE0);
					ovalFill(b, z, side, 1.0f, HULL + 0.016f, 0xFF18202C);
					ovalFill(b, z + 0.05f, side, 0.45f, HULL + 0.02f, 0xFF34465E);
				}
			}
		}
		// The windshield: tinted glass you can see the cockpit through, framed in black
		Station s3 = FUSELAGE[4], s4 = FUSELAGE[5], s5 = FUSELAGE[6];
		double face = Math.PI * 2 / SIDES;
		for (int i = -SIDES / 2; i < SIDES / 2; i++) {
			double from = i * face, to = (i + 1) * face, mid = (from + to) / 2;
			if (windshield(4, mid)) glass.patch(s3, s4, from, to, -0.01f, 0x55223040);
			if (windshield(5, mid)) glass.patch(s4, s5, from, to, -0.01f, 0x55223040);
		}
		// Radome tip and the tail cone's APU exhaust
		b.loft(new Station[]{new Station(15.12f, 0.1f, 2.15f), new Station(15.18f, 0.0f, 2.15f)}, 12, 0xFF9A9EA6);
		b.loft(new Station[]{new Station(-14.62f, 0.1f, 3.5f), new Station(-14.64f, 0.0f, 3.5f)}, 12, dark);
		// Wing-to-body fairing under the cabin
		b.loft(new Station[]{new Station(4.5f, 0.05f, 1.15f), new Station(3.4f, 0.6f, 1.05f), new Station(-3.6f, 0.64f, 1.05f), new Station(-5.4f, 0.05f, 1.15f)}, 20, grey);

		for (int side = -1; side <= 1; side += 2) {
			// Wings: a thick inner panel and a thinner outer one, swept and angled up, then a tall winglet
			b.plate(new float[][]{{side * 1.3f, 1.4f, 3.0f}, {side * 6.0f, 1.76f, -0.06f}, {side * 6.0f, 1.76f, -4.3f}, {side * 1.3f, 1.4f, -2.8f}}, 0, 1, 0, 0.3f, p);
			b.plate(new float[][]{{side * 6.0f, 1.76f, -0.06f}, {side * 14.5f, 2.4f, -5.6f}, {side * 14.5f, 2.4f, -7.0f}, {side * 6.0f, 1.76f, -4.3f}}, 0, 1, 0, 0.18f, p);
			b.plate(new float[][]{{side * 14.5f, 2.4f, -5.6f}, {side * 14.5f, 2.4f, -7.0f}, {side * 15.1f, 4.7f, -8.1f}, {side * 15.1f, 4.7f, -7.4f}}, 1, 0, 0, 0.12f, p);
			// Leading edge in bare metal, flaps and their track fairings
			b.plate(new float[][]{{side * 1.4f, 1.42f, 2.94f}, {side * 14.4f, 2.41f, -5.56f}, {side * 14.4f, 2.41f, -5.75f}, {side * 1.4f, 1.42f, 2.7f}}, 0, 1, 0, 0.32f, metal);
			b.plate(new float[][]{{side * 1.6f, 1.47f, -2.5f}, {side * 9.0f, 2.0f, -5.3f}, {side * 9.0f, 2.0f, -5.8f}, {side * 1.6f, 1.47f, -2.95f}}, 0, 1, 0, 0.2f, grey);
			for (float x : new float[]{3.5f, 6.5f, 9.0f}) {
				float t = (x - 1.3f) / 13.2f, z = -2.8f + t * -4.2f, y = 1.4f + t;
				b.loft(new Station[]{new Station(z + 1.4f, 0.02f, y - 0.1f, side * x), new Station(z + 0.6f, 0.16f, y - 0.12f, side * x), new Station(z - 0.4f, 0.12f, y - 0.1f, side * x), new Station(z - 0.8f, 0.02f, y - 0.05f, side * x)}, 8, grey);
			}

			// The engine on its pylon: silver intake lip, dark fan face with a spinner, exhaust cone
			float ex = side * 2.85f, ey = 3.7f;
			b.box(side > 0 ? 1.25f : -2.15f, 3.45f, -10.0f, side > 0 ? 2.15f : -1.25f, 3.8f, -7.6f, grey);
			b.loft(new Station[]{new Station(-6.6f, 0.78f, ey, ex), new Station(-6.75f, 0.9f, ey, ex), new Station(-9.4f, 0.93f, ey, ex), new Station(-10.8f, 0.76f, ey, ex), new Station(-11.2f, 0.62f, ey, ex)}, 24, p, 1.45f, 1.7f, line);
			b.loft(new Station[]{new Station(-6.52f, 0.72f, ey, ex), new Station(-6.6f, 0.82f, ey, ex), new Station(-6.75f, 0.9f, ey, ex)}, 24, metal);
			b.loft(new Station[]{new Station(-6.9f, 0.74f, ey, ex), new Station(-6.92f, 0.0f, ey, ex)}, 24, 0xFF26282C);
			b.loft(new Station[]{new Station(-6.5f, 0.0f, ey, ex), new Station(-6.75f, 0.18f, ey, ex), new Station(-6.9f, 0.2f, ey, ex)}, 12, metal);
			b.loft(new Station[]{new Station(-11.15f, 0.6f, ey, ex), new Station(-11.17f, 0.0f, ey, ex)}, 24, dark);
			b.loft(new Station[]{new Station(-11.0f, 0.36f, ey, ex), new Station(-11.6f, 0.2f, ey, ex), new Station(-11.9f, 0.02f, ey, ex)}, 12, 0xFF6C6E73);

			// Tailplane on top of the fin
			b.plate(new float[][]{{0, 9.0f, -12.6f}, {side * 5.2f, 9.25f, -14.4f}, {side * 5.2f, 9.25f, -15.5f}, {0, 9.0f, -14.9f}}, 0, 1, 0, 0.22f, p);
		}
		// The fin, swept back up to the tailplane, with the cheat line's colour up its middle
		b.plate(new float[][]{{0, 3.8f, -8.4f}, {0, 3.6f, -13.8f}, {0, 9.0f, -14.95f}, {0, 9.0f, -12.6f}}, 1, 0, 0, 0.32f, p);
		b.plate(new float[][]{{0, 5.2f, -10.6f}, {0, 5.2f, -11.4f}, {0, 8.2f, -13.6f}, {0, 8.2f, -12.9f}}, 1, 0, 0, 0.34f, line);
		b.loft(new Station[]{new Station(-12.3f, 0.02f, 9.05f), new Station(-12.8f, 0.28f, 9.05f), new Station(-15.1f, 0.28f, 9.05f), new Station(-15.6f, 0.02f, 9.05f)}, 12, p);
		// Antennas on top
		b.box(-0.03f, 4.15f, 6.0f, 0.03f, 4.55f, 6.3f, grey);
		b.box(-0.03f, 4.15f, -2.0f, 0.03f, 4.45f, -1.7f, grey);

		if (gearDown) {
			// Nose gear with twin wheels, and the main gear under the wings
			b.box(-0.07f, 0.4f, 12.0f, 0.07f, 1.45f, 12.14f, metal);
			b.plate(new float[][]{{-0.4f, 1.1f, 12.6f}, {-0.4f, 1.1f, 11.2f}, {-0.4f, 0.75f, 11.2f}, {-0.4f, 0.75f, 12.6f}}, 1, 0, 0, 0.03f, grey);
			for (int side = -1; side <= 1; side += 2) wheel(b, side * 0.2f, 0.36f, 12.07f, 0.36f, 0.24f);
			for (int side = -1; side <= 1; side += 2) {
				float gx = side * 2.3f;
				b.box(gx - 0.09f, 0.5f, -1.68f, gx + 0.09f, 1.5f, -1.52f, metal);
				b.box(gx - 0.32f, 0.45f, -1.68f, gx + 0.32f, 0.55f, -1.52f, metal);
				b.plate(new float[][]{{gx + side * 0.45f, 1.5f, -0.9f}, {gx + side * 0.45f, 1.5f, -2.3f}, {gx + side * 0.45f, 0.75f, -2.3f}, {gx + side * 0.45f, 0.75f, -0.9f}}, 1, 0, 0, 0.03f, grey);
				for (int w = -1; w <= 1; w += 2) wheel(b, gx + w * 0.28f, 0.48f, -1.6f, 0.48f, 0.3f);
			}
		}
		// A frame round the doorway, a seam when it's shut
		Station front = new Station(DOOR_FRONT + 0.05f, HULL, CENTER), back = new Station(DOOR_BACK - 0.05f, HULL, CENTER);
		int edge = doorOpen ? grey : ShapeBuilder.shade(p, 0.6f);
		b.patch(front, new Station(DOOR_FRONT, HULL, CENTER), DOOR_SILL, DOOR_TOP, 0.012f, edge);
		b.patch(new Station(DOOR_BACK, HULL, CENTER), back, DOOR_SILL, DOOR_TOP, 0.012f, edge);
		b.patch(front, back, DOOR_TOP - (doorOpen ? 0.06 : 0.02), DOOR_TOP, 0.012f, edge);
		if (!doorOpen) b.patch(front, back, DOOR_SILL, DOOR_SILL + 0.02, 0.012f, edge);
	}

	/**
	 * The airstair door, laid out folded down (its hinge on the sill): the door's outer skin
	 * underneath, steps on top with their side rails, and handrails. Drawn through a builder turned
	 * about the hinge, so it swings up shut and down open.
	 */
	private void drawAirstair(ShapeBuilder b, float fold) {
		int p = look != null ? look[0] : paint.get(), grey = ShapeBuilder.shade(p, 0.82f), metal = 0xFFB9BEC6;
		float z1 = DOOR_BACK + 0.05f, z2 = DOOR_FRONT - 0.05f;
		// The door's outside, which faces out when it's shut
		float[] top = fold(-1.36f, FLOOR - 0.3f, fold), bottom = fold(-3.67f, -0.05f, fold);
		b.plate(new float[][]{{top[0], top[1], z1 - 0.05f}, {bottom[0], bottom[1], z1 - 0.05f}, {bottom[0], bottom[1], z2 + 0.05f}, {top[0], top[1], z2 + 0.05f}}, 0.545f, -0.839f, 0, 0.06f, p);
		for (int i = 0; i < 7; i++) {
			float x = -1.36f - i * 0.33f, y = FLOOR - i * 0.25f;
			foldedBox(b, fold, x - 0.33f, y - 0.07f, z1, x, y, z2, ShapeBuilder.shade(p, 0.92f));
			foldedBox(b, fold, x - 0.33f, y - 0.25f, z1 - 0.05f, x, y, z1, grey);
			foldedBox(b, fold, x - 0.33f, y - 0.25f, z2, x, y, z2 + 0.05f, grey);
			for (float rz : new float[]{z1 - 0.05f, z2}) {
				foldedBox(b, fold, x - 0.2f, y, rz, x - 0.16f, y + 0.9f, rz + 0.05f, metal);
				foldedBox(b, fold, x - 0.33f, y + 0.98f, rz, x, y + 1.04f, rz + 0.05f, metal);
			}
		}
	}

	/** A point of the airstair drawn in toward its hinge by {@code fold}, so it tucks into the doorway as it shuts. */
	private static float[] fold(float x, float y, float fold) {
		return new float[]{-1.36f + (x + 1.36f) * fold, FLOOR + (y - FLOOR) * fold};
	}

	private static void foldedBox(ShapeBuilder b, float fold, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
		float[] a = fold(x1, y1, fold), c = fold(x2, y2, fold);
		b.box(a[0], a[1], z1, c[0], c[1], z2, color);
	}

	/** A wheel turning on an axle along x: tyre round the outside, a silver hub on each face. */
	private static void wheel(ShapeBuilder b, float cx, float cy, float cz, float radius, float width) {
		int n = 14;
		float x1 = cx - width / 2, x2 = cx + width / 2;
		for (int i = 0; i < n; i++) {
			double a1 = Math.PI * 2 * i / n, a2 = Math.PI * 2 * (i + 1) / n;
			float y1 = cy + (float) Math.cos(a1) * radius, z1 = cz + (float) Math.sin(a1) * radius, y2 = cy + (float) Math.cos(a2) * radius, z2 = cz + (float) Math.sin(a2) * radius;
			b.quad(new float[]{x1, y1, z1}, new float[]{x2, y1, z1}, new float[]{x2, y2, z2}, new float[]{x1, y2, z2}, 0xFF1A1A1C);
			for (float x : new float[]{x1, x2}) {
				b.flat(new float[]{x, cy, cz}, new float[]{x, y1, z1}, new float[]{x, y2, z2}, new float[]{x, y2, z2}, 0xFF1A1A1C);
				float hy1 = cy + (float) Math.cos(a1) * radius * 0.55f, hz1 = cz + (float) Math.sin(a1) * radius * 0.55f, hy2 = cy + (float) Math.cos(a2) * radius * 0.55f, hz2 = cz + (float) Math.sin(a2) * radius * 0.55f;
				float hx = x + (x == x1 ? -0.005f : 0.005f);
				b.flat(new float[]{hx, cy, cz}, new float[]{hx, hy1, hz1}, new float[]{hx, hy2, hz2}, new float[]{hx, hy2, hz2}, 0xFFC4C8CE);
			}
		}
	}

	/**
	 * A round wall across the fuselage at {@code z} with a doorway in it: the ring between the
	 * doorway's edge and the wall's round edge, filled in.
	 */
	private static void bulkhead(ShapeBuilder b, float z, float halfWidth, float top, float radius, int color) {
		float bottom = FLOOR - CENTER, up = top - CENTER;
		float[][] corners = {{halfWidth, up}, {-halfWidth, up}, {-halfWidth, bottom}, {halfWidth, bottom}};
		List<float[]> edge = new ArrayList<>();
		for (int c = 0; c < 4; c++) {
			float[] a = corners[c], d = corners[(c + 1) % 4];
			for (int i = 0; i < 8; i++) edge.add(new float[]{a[0] + (d[0] - a[0]) * i / 8, a[1] + (d[1] - a[1]) * i / 8});
		}
		// The floor's doorway edge would go below the floor; the wall carries on down to the hull there
		for (int i = 0; i < edge.size(); i++) {
			float[] p = edge.get(i), q = edge.get((i + 1) % edge.size());
			float lp = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1]), lq = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1]);
			b.quad(new float[]{p[0], CENTER + p[1], z}, new float[]{p[0] / lp * radius, CENTER + p[1] / lp * radius, z},
				new float[]{q[0] / lq * radius, CENTER + q[1] / lq * radius, z}, new float[]{q[0], CENTER + q[1], z}, color);
		}
		// Below the floor, between the doorway's sill and the hull
		b.box(-halfWidth, FLOOR - 0.9f, z - 0.02f, halfWidth, FLOOR, z + 0.02f, color);
	}

	/**
	 * The cockpit, seen from inside and through the windshield from outside: two pilot seats, the
	 * instrument panel with its four big screens under the glareshield, the centre pedestal, the
	 * overhead panel and the bulkhead back to the cabin.
	 */
	private void drawCockpit(ShapeBuilder b) {
		int grey = 0xFF3A3C42, panel = 0xFF25272C;
		b.box(-1.0f, FLOOR - 0.06f, 10.5f, 1.0f, FLOOR, 12.6f, 0xFF2E2F33);
		for (int side = -1; side <= 1; side += 2) seat(b, side * 0.55f, 11.45f, 1, 0xFF8E7D66, 0xFF5E5245);
		b.box(-0.92f, FLOOR + 0.3f, 11.95f, 0.92f, FLOOR + 1.0f, 12.6f, panel);
		b.box(-0.98f, FLOOR + 1.0f, 11.85f, 0.98f, FLOOR + 1.1f, 12.9f, 0xFF16171A);
		for (int i = 0; i < 4; i++) {
			float sx = -0.86f + i * 0.44f;
			b.box(sx, FLOOR + 0.48f, 11.94f, sx + 0.36f, FLOOR + 0.92f, 11.95f, 0xFF0E1622);
			b.box(sx + 0.03f, FLOOR + 0.66f, 11.935f, sx + 0.33f, FLOOR + 0.9f, 11.94f, i % 2 == 0 ? 0xFF2B5E9A : 0xFF6A4A2A);
			b.box(sx + 0.03f, FLOOR + 0.5f, 11.935f, sx + 0.33f, FLOOR + 0.64f, 11.94f, i % 2 == 0 ? 0xFF3D8A4E : 0xFF1F3D66);
		}
		b.box(-0.16f, FLOOR, 11.1f, 0.16f, FLOOR + 0.72f, 11.95f, grey);
		b.box(-0.12f, FLOOR + 0.72f, 11.3f, 0.12f, FLOOR + 0.74f, 11.9f, 0xFF16171A);
		b.box(-0.38f, 3.72f, 11.0f, 0.38f, 3.86f, 12.1f, grey);
		// Sidesticks
		for (int side = -1; side <= 1; side += 2) b.box(side * 0.98f - 0.03f, FLOOR + 0.6f, 11.5f, side * 0.98f + 0.03f, FLOOR + 0.82f, 11.56f, 0xFF16171A);
		bulkhead(b, 10.5f, 0.42f, 3.85f, WALL, 0xFF8A5A30);
	}

	/**
	 * The cabin, modelled on a real one: cream walls curving into the ceiling with a wood trim line
	 * and a warm cove light, oval windows with white bezels and real glass (the sky shows through),
	 * glossy wood ledges along both sides, big cream leather armchairs facing across fold-out
	 * tables, a divan, a galley by the door, a TV, and a wood bulkhead at the back with a door and a
	 * screen above it.
	 */
	private void drawCabin(ShapeBuilder b, ShapeBuilder glass, boolean inside) {
		boolean doorOpen = doorAmount() > 0;
		int cream = 0xFFF2ECE0, wood = 0xFF8A5A30, gloss = 0xFFB27C45, carpet = 0xFFCFC3AC, seat = look != null ? look[2] : leather.get(), seatDark = ShapeBuilder.shade(seat, 0.86f), bezel = 0xFFF7F4EE;

		// Walls and ceiling: a tube with the windows left open and a wood line above them
		// Stations at the door (segment 1) and each window's edges (segments 3, 5, ...)
		List<Station> stations = new ArrayList<>();
		stations.add(new Station(11.0f, WALL, CENTER));
		stations.add(new Station(DOOR_FRONT, WALL, CENTER));
		stations.add(new Station(DOOR_BACK, WALL, CENTER));
		for (float z : WINDOWS) {
			stations.add(new Station(z + HOLE_Z, WALL, CENTER));
			stations.add(new Station(z - HOLE_Z, WALL, CENTER));
		}
		stations.add(new Station(-6.0f, WALL, CENTER));
		Station front = stations.getFirst(), back = stations.getLast();
		b.loft(stations.toArray(new Station[0]), SIDES, cream, 0.79f, 0.91f, wood, (segment, angle) -> {
			if (segment == 1) return doorOpen && angle > DOOR_SILL && angle < DOOR_TOP;
			return segment >= 3 && segment % 2 == 1 && Math.abs(angle) > WINDOW_ANGLE - HOLE_A + 0.01 && Math.abs(angle) < WINDOW_ANGLE + HOLE_A - 0.01;
		});
		// The doorway's lining, from the cabin wall out to the skin
		if (doorOpen) {
			int lining = 0xFFD9D2C4;
			for (int i = 0; i < 8; i++) {
				double a1 = DOOR_SILL + (DOOR_TOP - DOOR_SILL) * i / 8, a2 = DOOR_SILL + (DOOR_TOP - DOOR_SILL) * (i + 1) / 8;
				for (float z : new float[]{DOOR_FRONT, DOOR_BACK}) b.quad(cyl(z, a1, WALL), cyl(z, a1, HULL), cyl(z, a2, HULL), cyl(z, a2, WALL), lining);
			}
			b.quad(cyl(DOOR_FRONT, DOOR_TOP, WALL), cyl(DOOR_FRONT, DOOR_TOP, HULL), cyl(DOOR_BACK, DOOR_TOP, HULL), cyl(DOOR_BACK, DOOR_TOP, WALL), lining);
		}
		// The cove light glowing along each side of the ceiling, and a lighter raised centre panel
		Station ceilingFront = new Station(10.45f, WALL, CENTER), ceilingBack = new Station(-5.45f, WALL, CENTER);
		for (int side = -1; side <= 1; side += 2) b.patch(ceilingFront, ceilingBack, side * 0.46, side * 0.56, -0.02f, 0xFFFFE7BC);
		for (int i = -3; i < 3; i++) b.patch(ceilingFront, ceilingBack, i * 0.13, (i + 1) * 0.13, -0.03f, 0xFFF8F5EF);

		// Each window: a white bezel shaping the hole into an oval, a deep reveal out to the skin, and glass
		for (int w = 0; w < WINDOWS.length; w++) {
			float z = WINDOWS[w];
			for (int side = -1; side <= 1; side += 2) {
				List<float[]> rect = new ArrayList<>();
				float[][] corners = {{HOLE_Z + 0.03f, HOLE_A + 0.03f}, {-HOLE_Z - 0.03f, HOLE_A + 0.03f}, {-HOLE_Z - 0.03f, -HOLE_A - 0.03f}, {HOLE_Z + 0.03f, -HOLE_A - 0.03f}};
				for (int c = 0; c < 4; c++) {
					float[] a = corners[c], d = corners[(c + 1) % 4];
					for (int i = 0; i < 6; i++) rect.add(new float[]{a[0] + (d[0] - a[0]) * i / 6, a[1] + (d[1] - a[1]) * i / 6});
				}
				for (int i = 0; i < rect.size(); i++) {
					float[] p = rect.get(i), q = rect.get((i + 1) % rect.size());
					double tp = Math.atan2(p[1] / OVAL_A, p[0] / OVAL_Z), tq = Math.atan2(q[1] / OVAL_A, q[0] / OVAL_Z);
					float r = WALL - 0.012f;
					b.quad(oval(z, side, tp, 1, r), cyl(z + p[0], side * (WINDOW_ANGLE - p[1]), r), cyl(z + q[0], side * (WINDOW_ANGLE - q[1]), r), oval(z, side, tq, 1, r), bezel);
					// The reveal: the oval tunnelling out through the wall to the glass
					b.quad(oval(z, side, tp, 1, r), oval(z, side, tq, 1, r), oval(z, side, tq, 0.93f, HULL - 0.005f), oval(z, side, tp, 0.93f, HULL - 0.005f), ShapeBuilder.shade(bezel, 0.93f));
				}
				// A few blinds pulled part way down
				if (w % 4 == 2) {
					double start = Math.asin(0.35), end = Math.PI - start;
					float[] mid = oval(z, side, Math.PI / 2, 0.68f, HULL - 0.03f);
					for (int i = 0; i < 10; i++) {
						double t1 = start + (end - start) * i / 10, t2 = start + (end - start) * (i + 1) / 10;
						b.flat(mid, oval(z, side, t1, 0.93f, HULL - 0.03f), oval(z, side, t2, 0.93f, HULL - 0.03f), oval(z, side, t2, 0.93f, HULL - 0.03f), 0xFFE8E0D2);
					}
				}
				if (inside) {
					ovalFill(glass, z, side, 0.94f, HULL - 0.01f, 0x22BFD8F0);
					// A glint across the pane
					glass.flat(oval(z, side, 2.4, 0.8f, HULL - 0.015f), oval(z, side, 2.0, 0.8f, HULL - 0.015f), oval(z, side, 1.75, 0.55f, HULL - 0.015f), oval(z, side, 2.2, 0.45f, HULL - 0.015f), 0x28FFFFFF);
				}
			}
		}

		// Carpet with a lighter runner up the aisle
		b.box(-1.24f, FLOOR - 0.06f, -5.5f, 1.24f, FLOOR, 10.5f, carpet);
		b.box(-0.32f, FLOOR, -5.5f, 0.32f, FLOOR + 0.004f, 10.4f, ShapeBuilder.shade(carpet, 1.06f));

		// Glossy wood ledges along both sides, under the windows
		ledge(b, -1, -1.8f, 8.9f, wood, gloss);
		ledge(b, 1, 1.9f, 8.7f, wood, gloss);
		ledge(b, 1, -5.4f, -1.7f, wood, gloss);

		// Two clubs of four armchairs facing across fold-out tables
		for (int side = -1; side <= 1; side += 2) {
			for (float table : new float[]{7.2f, 3.8f}) {
				seat(b, side * 0.66f, table + 1.0f, -1, seat, seatDark);
				seat(b, side * 0.66f, table - 1.0f, 1, seat, seatDark);
				table(b, side, table, wood, gloss);
			}
		}
		// A dining pair on the left; a long divan opposite
		seat(b, -0.66f, 1.2f, -1, seat, seatDark);
		seat(b, -0.66f, -0.8f, 1, seat, seatDark);
		table(b, -1, 0.2f, wood, gloss);
		divan(b, 1.6f, -1.6f, seat, seatDark);
		// The aft lounge: another divan, across from a ledge with a plant
		divan(b, -1.9f, -5.3f, seat, seatDark);
		b.box(1.18f, 2.56f, -3.6f, 1.3f, 2.66f, -3.48f, 0xFFEFEDE8);
		b.box(1.16f, 2.66f, -3.62f, 1.32f, 2.84f, -3.46f, 0xFF4E8A3A);
		// A small plant and a pair of glasses on the ledges
		b.box(-1.3f, 2.55f, 5.6f, -1.18f, 2.65f, 5.72f, 0xFFEFEDE8);
		b.box(-1.32f, 2.65f, 5.58f, -1.16f, 2.82f, 5.74f, 0xFF4E8A3A);

		// The galley across from the door
		b.box(0.55f, FLOOR, 8.95f, 1.24f, FLOOR + 0.95f, 10.45f, wood);
		b.box(0.52f, FLOOR + 0.95f, 8.92f, 1.4f, FLOOR + 1.0f, 10.48f, 0xFFEDEBE6);
		b.box(0.72f, 3.25f, 8.95f, 1.1f, 3.65f, 10.45f, ShapeBuilder.shade(wood, 1.1f));
		b.box(0.9f, FLOOR + 1.0f, 9.9f, 1.2f, FLOOR + 1.35f, 10.2f, 0xFF2A2B2F);
		// The cabin door on the left, shut once you're flying
		if (!doorOpen) {
			b.patch(new Station(DOOR_FRONT, WALL, CENTER), new Station(DOOR_BACK, WALL, CENTER), DOOR_SILL, DOOR_TOP, -0.02f, 0xFFE2D8C6);
			b.patch(new Station(9.97f, WALL, CENTER), new Station(9.83f, WALL, CENTER), -1.5, -1.38, -0.03f, 0xFFC9A14A);
		}

		// A solid wood wall across the back, with a big TV on it showing the markets
		float[][] wall = new float[32][];
		for (int i = 0; i < 32; i++) {
			double a = Math.PI * 2 * i / 32;
			wall[i] = new float[]{(float) Math.sin(a) * 1.53f, CENTER + (float) Math.cos(a) * 1.53f, -5.5f};
		}
		b.plate(wall, 0, 0, 1, 0.08f, wood);
		for (float x : new float[]{-0.95f, 0.95f}) b.box(x - 0.015f, FLOOR, -5.455f, x + 0.015f, 3.7f, -5.445f, 0xFFC9A14A);
		b.box(-0.82f, 2.3f, -5.46f, 0.82f, 3.32f, -5.42f, 0xFF0C0D10);
		b.box(-0.77f, 2.35f, -5.42f, 0.77f, 3.27f, -5.415f, 0xFF0F1A2C);
		// A green chart climbing across the screen
		float[] chart = {0.1f, 0.18f, 0.12f, 0.3f, 0.26f, 0.42f, 0.38f, 0.55f, 0.5f, 0.7f, 0.66f, 0.82f};
		for (int i = 0; i < chart.length - 1; i++) {
			float x1 = -0.68f + i * 0.125f, y1 = 2.42f + chart[i] * 0.75f, y2 = 2.42f + chart[i + 1] * 0.75f;
			b.box(x1, Math.min(y1, y2) - 0.012f, -5.414f, x1 + 0.125f, Math.max(y1, y2) + 0.012f, -5.412f, 0xFF35D07F);
		}
		b.box(-0.7f, 3.12f, -5.414f, -0.3f, 3.2f, -5.412f, 0xFFE8ECF2);
	}

	/** A wood ledge along one side under the windows, its top glossier, from z1 to z2. */
	private static void ledge(ShapeBuilder b, int side, float z1, float z2, int wood, int gloss) {
		b.box(side * 1.12f, FLOOR, z1, side * 1.24f, 2.5f, z2, wood);
		b.box(side * 1.08f, 2.5f, z1, side * 1.49f, 2.56f, z2, gloss);
	}

	/** A fold-out table between two facing armchairs, from the ledge, with a plate set on it. */
	private static void table(ShapeBuilder b, int side, float z, int wood, int gloss) {
		b.box(side * 0.22f, FLOOR + 0.7f, z - 0.42f, side * 1.12f, FLOOR + 0.76f, z + 0.42f, gloss);
		b.box(side * 0.2f, FLOOR + 0.66f, z - 0.44f, side * 1.12f, FLOOR + 0.7f, z + 0.44f, wood);
		b.box(side * 0.55f, FLOOR + 0.76f, z - 0.12f, side * 0.79f, FLOOR + 0.775f, z + 0.12f, 0xFFF7F6F2);
		b.box(side * 0.62f, FLOOR + 0.775f, z - 0.05f, side * 0.72f, FLOOR + 0.8f, z + 0.05f, 0xFF7FAF5A);
		b.box(side * 0.35f, FLOOR + 0.76f, z + 0.18f, side * 0.39f, FLOOR + 0.95f, z + 0.22f, 0xFFE6EEF2);
	}

	/** A long divan against the right or left wall, from z1 down to z2. */
	private static void divan(ShapeBuilder b, float z1, float z2, int color, int darker) {
		int side = z1 > 0 ? 1 : -1;
		b.box(side * 0.62f, FLOOR, z2, side * 1.32f, FLOOR + 0.42f, z1, darker);
		b.box(side * 0.6f, FLOOR + 0.42f, z2 + 0.04f, side * 1.2f, FLOOR + 0.55f, z1 - 0.04f, color);
		b.box(side * 1.12f, FLOOR + 0.42f, z2, side * 1.36f, 2.95f, z1, color);
		for (float z = z2 + 0.6f; z < z1 - 0.3f; z += 0.65f) b.box(side * 1.1f, FLOOR + 0.6f, z - 0.01f, side * 1.115f, 2.9f, z + 0.01f, darker);
	}

	/**
	 * A big leather armchair at (x, z), facing forward ({@code facing} 1) or back (-1): a deep seat
	 * cushion, wide padded armrests, a tall back with stitched seams and a headrest.
	 */
	private static void seat(ShapeBuilder b, float x, float z, int facing, int color, int darker) {
		float f = facing;
		b.box(x - 0.36f, FLOOR, z - 0.36f, x + 0.36f, FLOOR + 0.4f, z + 0.36f, darker);
		b.box(x - 0.245f, FLOOR + 0.4f, z - 0.3f, x + 0.245f, FLOOR + 0.52f, z + 0.3f, color);
		for (int s = -1; s <= 1; s += 2) {
			float ax = x + s * 0.31f;
			b.box(ax - 0.06f, FLOOR + 0.25f, z - 0.345f, ax + 0.06f, FLOOR + 0.7f, z + 0.345f, color);
			b.box(ax - 0.065f, FLOOR + 0.7f, z - 0.35f, ax + 0.065f, FLOOR + 0.74f, z + 0.35f, ShapeBuilder.shade(color, 1.04f));
		}
		float b1 = z - f * 0.365f, b2 = z - f * 0.16f;
		b.box(x - 0.335f, FLOOR + 0.4f, Math.min(b1, b2), x + 0.34f, FLOOR + 1.3f, Math.max(b1, b2), color);
		float h1 = z - f * 0.34f, h2 = z - f * 0.12f;
		b.box(x - 0.26f, FLOOR + 1.3f, Math.min(h1, h2), x + 0.26f, FLOOR + 1.46f, Math.max(h1, h2), color);
		// Stitched seams across the back
		float face = z - f * 0.159f;
		for (float y : new float[]{FLOOR + 0.8f, FLOOR + 1.05f}) b.box(x - 0.3f, y, face - 0.003f, x + 0.3f, y + 0.012f, face + 0.003f, darker);
	}
}
