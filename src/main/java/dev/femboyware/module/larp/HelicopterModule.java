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
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A private helicopter on its own helipad. With Server Larp on, board it (right-click it, or
 * {@code .heli fly}): the rotors spool up, it lifts straight off the pad, flies a banked lap round
 * at altitude, comes back, settles onto the pad and spools down, and you step off where you got
 * on. The captain flies it from the right seat. The server never sees you move, so it only works
 * while larping.
 */
public class HelicopterModule extends Module {
	private final ColorSetting paint = add(new ColorSetting("Paint", "The helicopter's body colour", 0xFF121317));
	private final ColorSetting stripe = add(new ColorSetting("Stripe", "The line down its side", 0xFFC9A14A));
	private final dev.femboyware.setting.BoolSetting farGround = add(new dev.femboyware.setting.BoolSetting("Far Ground",
		"While flying, draws a stand-in ground where the server hasn't sent any (Server Larp keeps you on the pad)", false));
	private final NumberSetting altitude = add(new NumberSetting("Altitude", "How high it flies, in blocks", 40, 15, 90, 5));
	private final NumberSetting radius = add(new NumberSetting("Lap Size", "How wide a circle it flies, in blocks", 55, 25, 120, 5));

	/** The flight in seconds: spool up, lift off, the lap, coming down onto the pad, spool down. */
	private static final double SPOOL = 4, LIFTED = 9, LAP = 30, LANDED = LIFTED + LAP + 5, DONE = LANDED + 3, HOVER = 8;

	private Vec3 parked;
	private float parkedYaw;
	private boolean boarded;
	private int ticks;
	private long lastTickNanos;
	private Vec3 boardedFrom;
	private float tickYaw;
	private double frameSeconds = -1;
	private int nextSoundTick;
	private Quaternionf cameraRotation;
	private float rotorAngle, tailAngle;
	private long lastFrame;

	private RemotePlayer pilot;
	private static final int PILOT_ID = -31001;
	/** Seats: you in the front left, the captain front right. Feet positions in the helicopter's own space (x is its left). */
	private static final Vector3f SEAT = new Vector3f(0.45f, 0.8f, 0.6f), PILOT_SEAT = new Vector3f(-0.45f, 0.8f, 0.6f);

	public HelicopterModule() {
		super("Helicopter", "A helicopter on a helipad you can board and fly round in while larping. Use " + CommandManager.PREFIX + "heli", Category.LARP);
	}

	@Override
	public String getInfo() {
		return boarded ? "Flying" : parked != null ? "Parked" : null;
	}

	private static boolean larping() {
		return FemboyWare.getModules().get(ServerLarpModule.class).isLarping();
	}

	// ---------------------------------------------------------------- commands

	/** Handles {@code .heli [spawn|fly|land|remove]}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "spawn";
		switch (action) {
			case "spawn", "park" -> {
				if (mc.player == null) return;
				if (boarded) {
					ChatUtil.info("Land first");
					return;
				}
				// On a pad off to your right, facing the way you do
				float yaw = mc.player.getYRot();
				double rad = Math.toRadians(yaw);
				parkAt(mc.player.position().add(Math.cos(rad) * -9, 0, Math.sin(rad) * -9).add(-Math.sin(rad) * 3, 0, Math.cos(rad) * 3), yaw);
				ChatUtil.info("Your helicopter's on the pad. " + (larping() ? "Right-click it to take off" : "Turn on Server Larp to fly it"));
			}
			case "fly", "board" -> board();
			case "land", "stop" -> {
				if (boarded) disembark("Back on the ground");
			}
			case "remove", "clear" -> {
				if (boarded) disembark(null);
				parked = null;
				removePilot();
				ChatUtil.info("Helicopter's gone back to the hangar");
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "heli <spawn|fly|land|remove>");
		}
	}

	/** Takes the helicopter away (and the captain), quietly, for the hangar. */
	public void clear() {
		if (boarded) disembark(null);
		parked = null;
		removePilot();
	}

	/** Parks it on a pad at {@code at} facing {@code yaw}, for the hangar too. */
	public void parkAt(Vec3 at, float yaw) {
		if (boarded) return;
		parked = at;
		// Squared to a compass direction, so its collision boxes fit it exactly
		parkedYaw = Math.round(yaw / 90f) * 90f;
		if (!isEnabled()) setEnabled(true);
	}

	// ---------------------------------------------------------------- flying

	private void board() {
		if (parked == null) {
			ChatUtil.info("Park a helicopter first: " + CommandManager.PREFIX + "heli spawn");
			return;
		}
		if (!larping()) {
			ChatUtil.info("The helicopter only flies while Server Larp is on, so the server never sees you leave");
			return;
		}
		if (boarded) return;
		boarded = true;
		ticks = 0;
		lastTickNanos = System.nanoTime();
		boardedFrom = mc.player.position();
		tickYaw = parkedYaw;
		mc.player.setYRot(parkedYaw);
		mc.player.setXRot(5);
		mc.player.setNoGravity(true);
		nextSoundTick = 0;
		dev.femboyware.util.Mc.addChat(Component.literal("<Captain> Strap in, we're spooling up."));
	}

	private void disembark(String message) {
		boarded = false;
		frameSeconds = -1;
		if (mc.player == null) return;
		mc.player.setNoGravity(false);
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.fallDistance = 0;
		if (boardedFrom != null) mc.player.snapTo(boardedFrom.x, boardedFrom.y, boardedFrom.z, mc.player.getYRot(), mc.player.getXRot());
		if (message != null) ChatUtil.info(message);
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	/** Where it is and how it's turned, and how fast the rotors turn (0 to 1). */
	private record Pose(Vec3 pos, float yaw, float pitch, float roll, float rotor) {}

	private Pose parkedPose() {
		return new Pose(parked, parkedYaw, 0, 0, 0);
	}

	/**
	 * The flight at {@code s} seconds. The lap is a circle off to the right that starts and ends
	 * over the pad heading the way it was parked; it speeds up and slows down along it, nose down
	 * and banked into the turn while it's moving.
	 */
	private Pose poseAt(double s) {
		double rad = Math.toRadians(parkedYaw);
		double fx = -Math.sin(rad), fz = Math.cos(rad), rx = -Math.cos(rad), rz = -Math.sin(rad), r = radius.get();
		float rotor = (float) Math.min(smooth((s - 1) / (SPOOL - 1)), 1 - smooth((s - LANDED) / (DONE - LANDED)));
		double height, x = parked.x, z = parked.z;
		float yaw = parkedYaw, pitch = 0, roll = 0;
		if (s < LIFTED) {
			height = HOVER * smooth((s - SPOOL) / (LIFTED - SPOOL));
		} else if (s < LIFTED + LAP) {
			double u = (s - LIFTED) / LAP, eased = smooth(u), phi = Math.PI * 2 * eased;
			double cx = parked.x + rx * r, cz = parked.z + rz * r;
			x = cx - r * (rx * Math.cos(phi) - fx * Math.sin(phi));
			z = cz - r * (rz * Math.cos(phi) - fz * Math.sin(phi));
			double hx = rx * Math.sin(phi) + fx * Math.cos(phi), hz = rz * Math.sin(phi) + fz * Math.cos(phi);
			yaw = (float) Math.toDegrees(Math.atan2(-hx, hz));
			height = HOVER + (altitude.get() - HOVER) * (1 - Math.cos(Math.PI * 2 * u)) / 2;
			// How fast it's going, 0 to 1: nose down and banked into the turn with it
			float speed = (float) (6 * u * (1 - u) / 1.5);
			pitch = -10 * speed;
			roll = 20 * speed;
		} else {
			height = HOVER * (1 - smooth((s - LIFTED - LAP) / (LANDED - LIFTED - LAP)));
		}
		// A gentle bob while it hovers
		if (height > 0.5) height += 0.15 * Math.sin(s * 1.7);
		return new Pose(new Vec3(x, parked.y + height, z), yaw, pitch, roll, rotor);
	}

	private double flightSeconds(double tickTime) {
		return Math.max(0, tickTime) / 20;
	}

	private double smoothSeconds() {
		double fraction = Mth.clamp((System.nanoTime() - lastTickNanos) / 50_000_000.0, 0, 1);
		return flightSeconds(ticks + fraction);
	}

	private Pose renderPose() {
		if (!boarded) return parkedPose();
		return poseAt(Math.min(frameSeconds >= 0 ? frameSeconds : smoothSeconds(), DONE));
	}

	private static Quaternionf rotation(Pose pose) {
		return new Quaternionf().rotateY((float) Math.toRadians(-pose.yaw)).rotateX((float) Math.toRadians(-pose.pitch)).rotateZ((float) Math.toRadians(pose.roll));
	}

	@Override
	public void onTick() {
		if (parked == null || mc.player == null) return;
		if (!boarded) {
			if (larping() && dev.femboyware.util.Mc.screen() == null && aimingAt()) {
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
		if (seconds >= DONE + 0.5) {
			disembark(null);
			dev.femboyware.util.Mc.addChat(Component.literal("<Captain> Back on the pad. Enjoy your evening."));
			return;
		}
		Pose pose = poseAt(seconds);
		mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(pose.yaw - tickYaw));
		tickYaw = pose.yaw;
		Vector3f at = new Vector3f(SEAT).rotate(rotation(pose));
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.setPos(pose.pos.x + at.x, pose.pos.y + at.y, pose.pos.z + at.z);
		mc.player.setOnGround(true);
		mc.player.fallDistance = 0;
		if (--nextSoundTick <= 0 && pose.rotor > 0.05f) {
			LarpSounds.play(LarpSounds.HELI_ROTOR, 0.75f + 0.25f * pose.rotor, 0.55f * pose.rotor);
			nextSoundTick = 36;
		}
	}

	/**
	 * Called from EntityMixin: the helipad underfoot, and the parked helicopter's cabin, tail boom and
	 * skids, solid for you while Server Larp is on (the server can't see them).
	 */
	public java.util.List<net.minecraft.world.phys.shapes.VoxelShape> collisions(Entity entity, AABB area) {
		if (!isEnabled() || parked == null || !LarpProps.solidFor(entity)) return new java.util.ArrayList<>();
		return padShapes(entity, area);
	}

	private java.util.List<net.minecraft.world.phys.shapes.VoxelShape> padShapes(Entity entity, AABB area) {
		java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes = new java.util.ArrayList<>();
		if (parked.distanceToSqr(entity.position()) > 30 * 30) return shapes;
		java.util.List<float[]> boxes = new java.util.ArrayList<>();
		// The pad: a round disc, as a stack of strips
		for (float x = -6.5f; x < 6.5f; x += 1) {
			float mid = x + 0.5f, half = (float) Math.sqrt(Math.max(0, 6.5f * 6.5f - mid * mid));
			boxes.add(new float[]{x, 0, -half, x + 1, 0.04f, half});
		}
		if (!boarded) {
			boxes.add(new float[]{-1.15f, 0.25f, -2.4f, 1.15f, 3.3f, 2.5f});   // cabin
			boxes.add(new float[]{-0.35f, 2.3f, -7.3f, 0.35f, 3.0f, -2.4f});  // tail boom
			boxes.add(new float[]{-0.2f, 2.6f, -7.7f, 0.2f, 4.0f, -6.3f});    // fin
			for (int side = -1; side <= 1; side += 2) boxes.add(new float[]{side * 0.95f - 0.1f, 0.04f, -1.6f, side * 0.95f + 0.1f, 0.25f, 2.05f}); // skids
		}
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-parkedYaw));
		for (float[] b : boxes) {
			Vector3f p = new Vector3f(b[0], b[1], b[2]).rotate(turn), q = new Vector3f(b[3], b[4], b[5]).rotate(turn);
			AABB box = new AABB(parked.x + p.x, parked.y + p.y, parked.z + p.z, parked.x + q.x, parked.y + q.y, parked.z + q.z);
			if (box.intersects(area)) shapes.add(net.minecraft.world.phys.shapes.Shapes.create(box));
		}
		return shapes;
	}

	/** Whether you're aiming at the parked helicopter from within reach. */
	private boolean aimingAt() {
		if (parked.distanceToSqr(mc.player.position()) > 20 * 20) return false;
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-parkedYaw));
		Vector3f a = new Vector3f(-1.3f, 0.2f, -2.8f).rotate(turn), b = new Vector3f(1.3f, 3.3f, 2.6f).rotate(turn);
		AABB box = new AABB(parked.x + a.x, parked.y + a.y, parked.z + a.z, parked.x + b.x, parked.y + b.y, parked.z + b.z);
		Vec3 eye = mc.player.getEyePosition();
		return box.contains(eye) || box.clip(eye, eye.add(mc.player.getViewVector(1).scale(6))).isPresent();
	}

	@Override
	protected void onDisable() {
		if (boarded) disembark(null);
		removePilot();
	}

	public Quaternionf cameraRotation() {
		return cameraRotation;
	}

	/** For CameraMixin: your seat, turned with the helicopter; in third person, a camera orbiting it. */
	public CinematicCameraModule.View view(float tickProgress) {
		cameraRotation = null;
		placePilot();
		if (!boarded || !isEnabled() || mc.player == null) {
			frameSeconds = -1;
			return null;
		}
		frameSeconds = smoothSeconds();
		Pose pose = poseAt(Math.min(frameSeconds, DONE));
		// You, sat in your seat exactly where the helicopter is this frame, so you don't judder in it
		Vector3f sat = new Vector3f(SEAT).rotate(rotation(pose));
		mc.player.setPos(pose.pos.x + sat.x, pose.pos.y + sat.y, pose.pos.z + sat.z);
		mc.player.xo = mc.player.getX();
		mc.player.yo = mc.player.getY();
		mc.player.zo = mc.player.getZ();
		mc.player.xOld = mc.player.getX();
		mc.player.yOld = mc.player.getY();
		mc.player.zOld = mc.player.getZ();
		mc.player.yBodyRot = pose.yaw;
		mc.player.yBodyRotO = pose.yaw;
		float lookYaw = mc.player.getViewYRot(tickProgress) + Mth.wrapDegrees(pose.yaw - tickYaw), lookPitch = mc.player.getViewXRot(tickProgress);
		if (!mc.options.getCameraType().isFirstPerson()) {
			boolean front = mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT;
			float yaw = front ? lookYaw + 180 : lookYaw, pitch = front ? -lookPitch : lookPitch;
			double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
			Vec3 lookDir = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
			Vec3 target = pose.pos.add(0, 2, 0);
			Vec3 cam = target.subtract(lookDir.scale(16));
			if (cam.y < parked.y + 0.6) cam = new Vec3(cam.x, parked.y + 0.6, cam.z);
			Vec3 look = target.subtract(cam);
			return new CinematicCameraModule.View(cam, (float) Math.toDegrees(Math.atan2(-look.x, look.z)), (float) Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z))));
		}
		Quaternionf turn = rotation(pose);
		Vector3f eye = new Vector3f(SEAT.x, SEAT.y + mc.player.getEyeHeight(), SEAT.z).rotate(turn);
		cameraRotation = new Quaternionf(turn).rotateY((float) Math.toRadians(-(lookYaw - pose.yaw))).rotateX((float) Math.toRadians(lookPitch)).rotateY((float) Math.PI);
		Vector3f forward = new Vector3f(0, 0, -1).rotate(cameraRotation);
		return new CinematicCameraModule.View(pose.pos.add(eye.x, eye.y, eye.z), (float) Math.toDegrees(Math.atan2(-forward.x, forward.z)),
			(float) Math.toDegrees(-Math.asin(Mth.clamp(forward.y, -1, 1))));
	}

	// ---------------------------------------------------------------- the captain

	public net.minecraft.world.entity.player.PlayerSkin skinFor(net.minecraft.client.player.AbstractClientPlayer player, net.minecraft.world.entity.player.PlayerSkin textures) {
		return pilot != null && player == pilot ? JetModule.CAPTAIN : textures;
	}

	/** The captain in the right seat, moved with the helicopter every frame. */
	private void placePilot() {
		if (!isEnabled() || parked == null || mc.level == null) {
			removePilot();
			return;
		}
		if (pilot == null || pilot.level() != mc.level || pilot.isRemoved()) {
			pilot = new RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Captain"));
			pilot.setId(PILOT_ID);
			mc.level.addEntity(pilot);
		}
		Pose pose = renderPose();
		Vector3f seat = new Vector3f(PILOT_SEAT).rotate(rotation(pose));
		pilot.setPos(pose.pos.x + seat.x, pose.pos.y + seat.y, pose.pos.z + seat.z);
		pilot.setOldPosAndRot();
		pilot.setYRot(pose.yaw);
		pilot.yRotO = pose.yaw;
		pilot.yBodyRot = pose.yaw;
		pilot.yBodyRotO = pose.yaw;
		pilot.yHeadRot = pose.yaw;
		pilot.yHeadRotO = pose.yaw;
	}

	private void removePilot() {
		if (pilot != null && mc.level != null && pilot.level() == mc.level) mc.level.removeEntity(pilot.getId(), Entity.RemovalReason.DISCARDED);
		pilot = null;
	}

	/** Called from PlayerModelMixin: the captain, and you while you fly, sit in the seats. */
	public void posePilot(net.minecraft.client.model.player.PlayerModel model, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
		boolean captain = pilot != null && state.id == PILOT_ID;
		boolean you = boarded && mc.player != null && state.id == mc.player.getId();
		// Other people's passengers, from Client Sync, sit too
		boolean passenger = FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).seatedInHelicopter(state.id);
		if (!captain && !you && !passenger) return;
		poseSeatedPilot(model, captain);
	}

	// ---------------------------------------------------------------- the helicopter

	/** The body, nose to tail: a glass bubble nose, the cabin, and the long thin tail boom. */
	private static final Station[] BODY = {
		new Station(2.6f, 0.05f, 1.75f), new Station(2.45f, 0.55f, 1.8f), new Station(2.15f, 0.9f, 1.9f), new Station(1.6f, 1.12f, 2.0f),
		new Station(0.8f, 1.2f, 2.05f), new Station(-0.4f, 1.2f, 2.1f), new Station(-1.3f, 1.05f, 2.2f), new Station(-2.0f, 0.7f, 2.35f),
		new Station(-2.6f, 0.36f, 2.5f), new Station(-4.6f, 0.25f, 2.62f), new Station(-6.8f, 0.17f, 2.72f), new Station(-7.2f, 0.04f, 2.75f)};

	/** The windows: the whole nose bubble, the cockpit's sides, and the cabin doors. */
	private static boolean window(int segment, double angle) {
		double a = Math.abs(angle);
		return switch (segment) {
			case 0, 1, 2 -> a < 1.75;
			case 3 -> a > 0.3 && a < 1.75;
			case 4 -> a > 0.65 && a < 1.5;
			default -> false;
		};
	}

	/** Who's flying in the helicopter right now (you and the captain), for FarGround to keep drawn. */
	public java.util.List<Entity> flyers() {
		if (!boarded || mc.player == null) return java.util.List.of();
		return pilot != null ? java.util.List.of(mc.player, pilot) : java.util.List.of(mc.player);
	}

	// ---------------------------------------------------------------- Client Sync

	/** Someone else's colours (paint, stripe) while their helicopter's drawn, or null for yours. */
	private int[] look;

	/** For Client Sync: your helicopter as drawn now (x, y, z, yaw, pitch, roll, rotor, pad x, y, z, pad yaw, landing light, boarded), or null. */
	public double[] shareState() {
		if (!isEnabled() || parked == null) return null;
		Pose pose = renderPose();
		boolean landing = boarded && pose.pos.y - parked.y < 15 && pose.rotor > 0.5f;
		return new double[]{pose.pos.x, pose.pos.y, pose.pos.z, pose.yaw, pose.pitch, pose.roll, pose.rotor, parked.x, parked.y, parked.z, parkedYaw,
			landing ? 1 : 0, boarded ? 1 : 0};
	}

	/** Someone else's helipad, and their helicopter while it's sat on it, as solid boxes. */
	public java.util.List<net.minecraft.world.phys.shapes.VoxelShape> sharedCollisions(Entity entity, AABB area, double[] s) {
		Vec3 wasParked = parked;
		float wasYaw = parkedYaw;
		boolean wasBoarded = boarded;
		parked = new Vec3(s[7], s[8], s[9]);
		parkedYaw = (float) s[10];
		boarded = s[12] > 0.5;
		try {
			return padShapes(entity, area);
		} finally {
			parked = wasParked;
			parkedYaw = wasYaw;
			boarded = wasBoarded;
		}
	}

	/** Where the captain of someone else's helicopter sits (x, y, z, yaw). */
	public static double[] sharedPilot(double[] s) {
		Pose pose = sharedPose(s);
		Vector3f seat = new Vector3f(PILOT_SEAT).rotate(rotation(pose));
		return new double[]{pose.pos.x + seat.x, pose.pos.y + seat.y, pose.pos.z + seat.z, pose.yaw};
	}

	/** The captain's hands on the controls, legs bent in the seat: for other people's captains too, from Client Sync. */
	public static void poseSeatedPilot(net.minecraft.client.model.player.PlayerModel model, boolean captain) {
		model.rightLeg.xRot = -1.45f;
		model.leftLeg.xRot = -1.45f;
		model.rightLeg.yRot = 0.1f;
		model.leftLeg.yRot = -0.1f;
		if (captain) {
			model.rightArm.xRot = -0.8f;
			model.leftArm.xRot = -0.6f;
			model.rightArm.zRot = 0;
			model.leftArm.zRot = 0;
		}
	}

	public int[] shareLook() {
		return new int[]{paint.get(), stripe.get()};
	}

	/** For Client Sync: your seat in its own space, or null if you're not aboard. */
	public float[] shareSeat() {
		return boarded ? new float[]{SEAT.x, SEAT.y, SEAT.z} : null;
	}

	/** Someone else's helicopter; {@code spin} is its rotor and tail rotor angles, turned on here as it's drawn. */
	public void drawShared(Render3DEvent event, double[] s, int[] colors, float[] spin, float dt) {
		Pose pose = sharedPose(s);
		spin[0] += pose.rotor * 30 * dt;
		spin[1] += pose.rotor * 70 * dt;
		int[] wasLook = look;
		float wasRotor = rotorAngle, wasTail = tailAngle;
		look = colors.length >= 2 ? colors : null;
		rotorAngle = spin[0];
		tailAngle = spin[1];
		try {
			drawPose(event, pose, new Vec3(s[7], s[8], s[9]), (float) s[10], s[11] > 0.5);
		} finally {
			look = wasLook;
			rotorAngle = wasRotor;
			tailAngle = wasTail;
		}
	}

	private static Pose sharedPose(double[] s) {
		return new Pose(new Vec3(s[0], s[1], s[2]), (float) s[3], (float) s[4], (float) s[5], (float) s[6]);
	}

	/** Where someone sat at {@code seat} (its own space) in a shared helicopter is in the world. */
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
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		rotorAngle += pose.rotor * 30 * dt;
		tailAngle += pose.rotor * 70 * dt;
		drawPose(event, pose, parked, parkedYaw, boarded && pose.pos.y - parked.y < 15 && pose.rotor > 0.5f);
	}

	/** Draws the helicopter at {@code pose} and its pad at {@code pad}, with its landing light on if {@code landing}. */
	private void drawPose(Render3DEvent event, Pose pose, Vec3 pad, float padYaw, boolean landing) {
		Vec3 cam = event.camera().pos;
		drawPad(event, cam, pad, padYaw);
		Quaternionf rot = rotation(pose);
		float ox = (float) (pose.pos.x - cam.x), oy = (float) (pose.pos.y - cam.y), oz = (float) (pose.pos.z - cam.z);
		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), rot, ox, oy, oz);
		ShapeBuilder in = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), rot, ox, oy, oz).soft();
		ShapeBuilder glass = new ShapeBuilder(null, event.matrices().last(), rot, ox, oy, oz);
		drawBody(b, in, glass, pose.rotor);
		glass.flush(FxRender.quads(event, false, false));

		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		long ms = System.currentTimeMillis();
		if (ms % 1000 < 400) light(event, glow, rot, ox, oy, oz, 0, 3.95f, -7.45f, 0.35f, 0xE0FF3030);
		light(event, glow, rot, ox, oy, oz, 1.0f, 0.6f, -0.5f, 0.25f, 0xE0FF2A2A);
		light(event, glow, rot, ox, oy, oz, -1.0f, 0.6f, -0.5f, 0.25f, 0xE02AFF4A);
		if (ms % 1300 < 90) light(event, glow, rot, ox, oy, oz, 0, 2.9f, -7.3f, 0.6f, 0xF0FFFFFF);
		if (landing) light(event, glow, rot, ox, oy, oz, 0, 0.75f, 2.1f, 0.9f, 0xB0FFF6E0);
	}

	private static void light(Render3DEvent event, VertexConsumer glow, Quaternionf rot, float ox, float oy, float oz, float x, float y, float z, float size, int color) {
		Vector3f at = new Vector3f(x, y, z).rotate(rot);
		FxRender.billboard(event, glow, ox + at.x, oy + at.y, oz + at.z, size, 0, LarpLight.glow(color));
	}

	/** The helipad: a dark disc, a yellow ring round it and a big white H, flat on the ground under where it parks. */
	private void drawPad(Render3DEvent event, Vec3 cam, Vec3 at, float yaw) {
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
		ShapeBuilder pad = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn,
			(float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
		int sides = 40;
		for (int i = 0; i < sides; i++) {
			double a1 = Math.PI * 2 * i / sides, a2 = Math.PI * 2 * (i + 1) / sides;
			float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1), c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);
			pad.flat(new float[]{0, 0.02f, 0}, new float[]{c2 * 6.5f, 0.02f, s2 * 6.5f}, new float[]{c1 * 6.5f, 0.02f, s1 * 6.5f}, new float[]{c1 * 6.5f, 0.02f, s1 * 6.5f}, 0xFF2A2C30);
			pad.flat(new float[]{c1 * 5.6f, 0.03f, s1 * 5.6f}, new float[]{c2 * 5.6f, 0.03f, s2 * 5.6f}, new float[]{c2 * 6.0f, 0.03f, s2 * 6.0f}, new float[]{c1 * 6.0f, 0.03f, s1 * 6.0f}, 0xFFE8C21C);
		}
		int white = 0xFFF2F2F2;
		pad.box(-1.9f, 0.02f, -2.4f, -1.2f, 0.04f, 2.4f, white);
		pad.box(1.2f, 0.02f, -2.4f, 1.9f, 0.04f, 2.4f, white);
		pad.box(-1.2f, 0.02f, -0.35f, 1.2f, 0.04f, 0.35f, white);
	}

	/**
	 * A sleek private helicopter: a glossy body with a stripe, a glass bubble nose and cabin windows
	 * you can see through, a dark cabin inside with leather seats and an instrument panel, the engine
	 * cowling on top, skids, a long tail boom with a fin and tail rotor, and four main rotor blades
	 * blurring into a disc as they spin up.
	 */
	private void drawBody(ShapeBuilder b, ShapeBuilder in, ShapeBuilder glass, float rotor) {
		int p = look != null ? look[0] : paint.get(), line = look != null ? look[1] : stripe.get(), dark = 0xFF1A1B1F, metal = 0xFF8C9098, cabin = 0xFF2C2D32, leather = 0xFFB89B72;
		b.loft(BODY, 40, p, 1.75f, 1.9f, line, HelicopterModule::window);
		// Glass over every window
		double face = Math.PI * 2 / 40;
		for (int s = 0; s < 5; s++) {
			for (int i = -20; i < 20; i++) {
				double from = i * face, to = (i + 1) * face;
				if (window(s, (from + to) / 2)) glass.patch(BODY[s], BODY[s + 1], from, to, -0.01f, 0x50202A38);
			}
		}
		// The cabin inside: a dark lining with the same windows, a floor, seats and the panel
		Station[] lining = new Station[8];
		for (int k = 0; k < 8; k++) lining[k] = new Station(BODY[k].z(), Math.max(0.02f, BODY[k].radius() - 0.05f), BODY[k].y());
		in.loft(lining, 40, cabin, -1, 0, 0, HelicopterModule::window);
		// All of it sized to sit inside the body, which narrows toward its belly and nose
		in.box(-0.7f, 1.05f, -1.0f, 0.7f, 1.15f, 1.5f, 0xFF3A3B40);
		for (int side = -1; side <= 1; side += 2) seat(in, side * 0.42f, 0.6f, leather);
		in.box(-0.6f, 1.3f, -1.25f, 0.6f, 1.62f, -0.75f, ShapeBuilder.shade(leather, 0.85f));
		in.box(-0.6f, 1.62f, -1.42f, 0.6f, 2.3f, -1.25f, leather);
		in.box(-0.7f, 1.45f, 1.5f, 0.7f, 1.82f, 1.82f, dark);
		in.box(-0.62f, 1.53f, 1.495f, -0.06f, 1.77f, 1.5f, 0xFF1F4B7A);
		in.box(0.06f, 1.53f, 1.495f, 0.62f, 1.77f, 1.5f, 0xFF2E6A3E);
		in.box(-0.03f, 1.15f, 1.0f, 0.03f, 1.6f, 1.06f, dark);

		// Engine cowling and exhausts on top, the mast and the hub
		b.loft(new Station[]{new Station(0.9f, 0.05f, 3.0f), new Station(0.6f, 0.45f, 3.05f), new Station(-1.8f, 0.5f, 3.12f), new Station(-2.5f, 0.15f, 3.05f)}, 20, p);
		for (int side = -1; side <= 1; side += 2) b.loft(new Station[]{new Station(-1.6f, 0.13f, 3.2f, side * 0.4f), new Station(-2.1f, 0.13f, 3.2f, side * 0.45f), new Station(-2.12f, 0.0f, 3.2f, side * 0.45f)}, 10, dark);
		b.box(-0.08f, 3.5f, -0.38f, 0.08f, 3.98f, -0.22f, metal);
		b.loft(new Station[]{new Station(-0.12f, 0.02f, 4.0f), new Station(-0.18f, 0.22f, 4.0f), new Station(-0.42f, 0.22f, 4.0f), new Station(-0.48f, 0.02f, 4.0f)}, 12, dark);

		// Four main blades
		for (int k = 0; k < 4; k++) {
			double a = rotorAngle + k * Math.PI / 2;
			float dx = (float) Math.sin(a), dz = (float) Math.cos(a), px = dz * 0.15f, pz = -dx * 0.15f, hz = -0.3f;
			b.plate(new float[][]{{dx * 0.3f + px, 4.02f, hz + dz * 0.3f + pz}, {dx * 5.6f + px, 4.0f, hz + dz * 5.6f + pz},
				{dx * 5.6f - px, 4.0f, hz + dz * 5.6f - pz}, {dx * 0.3f - px, 4.02f, hz + dz * 0.3f - pz}}, 0, 1, 0, 0.04f, dark);
		}
		if (rotor > 0.4f) {
			// Spinning fast, the blades blur into a faint disc
			int alpha = (int) (0x30 * Math.min(1, (rotor - 0.4f) / 0.4f));
			for (int i = 0; i < 32; i++) {
				double a1 = Math.PI * 2 * i / 32, a2 = Math.PI * 2 * (i + 1) / 32;
				glass.flat(new float[]{0, 4.0f, -0.3f}, new float[]{(float) Math.sin(a1) * 5.6f, 4.0f, -0.3f + (float) Math.cos(a1) * 5.6f},
					new float[]{(float) Math.sin(a2) * 5.6f, 4.0f, -0.3f + (float) Math.cos(a2) * 5.6f}, new float[]{(float) Math.sin(a2) * 5.6f, 4.0f, -0.3f + (float) Math.cos(a2) * 5.6f}, alpha << 24 | 0x202226);
			}
		}

		// Tail fin, the little stabiliser, and the tail rotor on the right
		b.plate(new float[][]{{0, 2.7f, -6.3f}, {0, 2.7f, -7.3f}, {0, 4.0f, -7.65f}, {0, 3.75f, -6.95f}}, 1, 0, 0, 0.1f, p);
		b.plate(new float[][]{{0.9f, 2.62f, -5.55f}, {0.9f, 2.62f, -6.0f}, {-0.9f, 2.62f, -6.0f}, {-0.9f, 2.62f, -5.55f}}, 0, 1, 0, 0.06f, p);
		for (int k = 0; k < 2; k++) {
			double a = tailAngle + k * Math.PI;
			float dy = (float) Math.cos(a), dz = (float) Math.sin(a), py = -dz * 0.07f, pz = dy * 0.07f;
			b.plate(new float[][]{{-0.22f, 3.0f + py, -7.0f + pz}, {-0.22f, 3.0f + dy * 0.8f + py, -7.0f + dz * 0.8f + pz},
				{-0.22f, 3.0f + dy * 0.8f - py, -7.0f + dz * 0.8f - pz}, {-0.22f, 3.0f - py, -7.0f - pz}}, 1, 0, 0, 0.03f, dark);
		}

		// Skids on struts
		for (int side = -1; side <= 1; side += 2) {
			float x = side * 0.95f;
			b.box(x - 0.06f, 0.08f, -1.6f, x + 0.06f, 0.2f, 1.8f, metal);
			b.box(x - 0.06f, 0.14f, 1.8f, x + 0.06f, 0.28f, 2.05f, metal);
			for (float z : new float[]{1.0f, -0.9f}) b.box(x - 0.05f + (side > 0 ? -0.2f : 0.1f), 0.2f, z - 0.05f, x + 0.05f + (side > 0 ? -0.1f : 0.2f), 0.95f, z + 0.05f, metal);
		}
	}

	private static void seat(ShapeBuilder b, float x, float z, int color) {
		b.box(x - 0.24f, 1.15f, z - 0.28f, x + 0.24f, 1.55f, z + 0.28f, ShapeBuilder.shade(color, 0.85f));
		b.box(x - 0.24f, 1.55f, z - 0.42f, x + 0.24f, 2.35f, z - 0.26f, color);
	}

	/** For the hangar: whether a helicopter's parked. */
	public boolean parked() {
		return parked != null;
	}
}
