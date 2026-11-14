package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * Flex cars parked next to you: a long luxury SUV and a Jesko-style hypercar, built from shaded
 * boxes with chrome, tinted glass, lights and spinning rims, with optional hydraulics bouncing the
 * body and underglow. Client-side only, and you walk through them.
 */
public class CarsModule extends Module {
	public enum Model { SUV, JESKO }

	/** A parked car: what it is, where, which way its nose points (Minecraft yaw), and when it arrived. */
	private record Car(Model model, Vec3 pos, float yaw, long born, Vec3 from, long arrive) {
		Car(Model model, Vec3 pos, float yaw, long born) {
			this(model, pos, yaw, born, pos, born);
		}

		/** Where it is now: driving in from {@code from} until {@code arrive}, slowing to a stop, then parked. */
		Vec3 at(long now) {
			if (now >= arrive) return pos;
			float progress = 1 - (arrive - now) / (float) DRIVE_IN;
			float eased = 1 - (1 - progress) * (1 - progress) * (1 - progress);
			return from.add(pos.subtract(from).scale(eased));
		}

		boolean parked(long now) {
			return now >= arrive;
		}
	}

	private static final long DRIVE_IN = 5_000_000_000L;

	private final EnumSetting<Model> model = add(new EnumSetting<>("Model", "Which car " + CommandManager.PREFIX + "car spawn parks", Model.SUV));
	private final ColorSetting paint = add(new ColorSetting("Paint", "Body colour", 0xFF0D0E11));
	private final ColorSetting trim = add(new ColorSetting("Trim", "Chrome trim, grille and rims", 0xFFD6DAE0));
	private final ColorSetting jeskoPaint = add(new ColorSetting("Jesko Paint", "The hypercar's body colour", 0xFFE6E8EC));
	private final ColorSetting jeskoAccent = add(new ColorSetting("Jesko Accent", "Its accent colour: splitter, canards, diffuser fins and brake calipers", 0xFF7CF23C));
	private final BoolSetting hydraulics = add(new BoolSetting("Hydraulics", "The Maybach's body bounces and rocks on its suspension", true));
	private final NumberSetting bounce = add(new NumberSetting("Bounce", "How high it bounces, in blocks", 0.35, 0.05, 1.2, 0.05)).visibleWhen(hydraulics::get);
	private final BoolSetting underglow = add(new BoolSetting("Underglow", "Coloured light under the car", true));
	private final ColorSetting glowColor = add(new ColorSetting("Glow Color", "Underglow colour", 0xFFB45CFF)).visibleWhen(underglow::get);
	private final BoolSetting spinningRims = add(new BoolSetting("Spinning Rims", "The spinners keep turning for a few seconds after it pulls up, then wind down", true));
	private final BoolSetting headlights = add(new BoolSetting("Headlights", "Lights on, glowing", true));
	private final BoolSetting valet = add(new BoolSetting("Valet", "A valet drives each car up to you, gets out and hands it over", true));

	/** A valet bringing a car round: walking up to you, waiting, then walking off. */
	private static final class Valet {
		final net.minecraft.client.player.RemotePlayer entity;
		final dev.femboyware.module.client.FakeWanderer walker = new dev.femboyware.module.client.FakeWanderer();
		final Car car;
		int stage, ticks;
		Vec3 leaveTo;

		Valet(net.minecraft.client.player.RemotePlayer entity, Car car) {
			this.entity = entity;
			this.car = car;
		}
	}

	private final List<Car> awaitingValet = new ArrayList<>();
	private final List<Valet> valets = new ArrayList<>();
	private int nextValetId = -32000;

	private final List<Car> cars = new ArrayList<>();
	/** Tinted glass you can see through, from outside and in. */
	/** Semi-tinted: you can make out who's inside, but not much more. */
	private static final int SUV_GLASS = 0xC20C0F13, JESKO_GLASS = 0xAA0C0F13;
	private static final int GLASS = 0xFF13171D, TIRE = 0xFF141416, BLACK = 0xFF0A0A0B, HEADLIGHT = 0xFFF4F6FF, TAILLIGHT = 0xFFC2121F;

	/**
	 * Draws one SUV bouncing on its hydraulics at {@code pos}, for the Maybach Bounce emote; works
	 * whether or not this module is on.
	 */
	public void drawShowCar(Render3DEvent event, Vec3 pos, float yaw, float seconds) {
		Car car = new Car(Model.SUV, pos, yaw, 0);
		Builder b = new Builder(FxRender.solidQuads(event), event.matrices().last(), car, event.camera().pos);
		Builder glass = new Builder(null, event.matrices().last(), car, event.camera().pos);
		b.lift = glass.lift = showLift(seconds);
		b.pitch = glass.pitch = 0.05f * (float) Math.sin(seconds * 1.3f);
		// Parked: the wheels sit still while the body bounces
		suv(b, glass, 0);
		glass.flush(FxRender.quads(event, false, false));
	}

	/** How high the show car's body is bouncing at {@code seconds}. */
	public static float showLift(float seconds) {
		return 0.4f * Math.abs((float) Math.sin(seconds * 2.6f));
	}

	// ---------------------------------------------------------------- collision and finding cars

	/** How tall a car is at {@code z} along its length (its own space, + forward), for collision. */
	private static float heightAt(Model model, float z) {
		if (model == Model.SUV) return z > -2.35f && z < 1.2f ? 1.92f : 1.18f;
		if (z > -1.1f && z < 0.9f) return 1.12f;
		return z >= 0.9f ? 0.72f : 0.95f;
	}

	/**
	 * Called from EntityMixin as you move: the cars as solid boxes, so you can't walk through them and
	 * can climb on top. Only for you, and only while Server Larp is on, since the server can't see the
	 * cars and would otherwise snap you back when it disagrees about where you can stand.
	 */
	public List<net.minecraft.world.phys.shapes.VoxelShape> collisions(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.AABB area) {
		List<net.minecraft.world.phys.shapes.VoxelShape> shapes = new ArrayList<>();
		// Valets find their own way round the cars, and get in and out of them, so cars aren't solid for them
		if (!isEnabled() || cars.isEmpty() || isValet(entity) || !LarpProps.solidFor(entity)) return shapes;
		if (riding == null) return carShapes(cars, area);
		List<Car> others = new ArrayList<>(cars);
		others.remove(riding);
		return carShapes(others, area);
	}

	/** Whether this is one of your valets. */
	public boolean isValet(net.minecraft.world.entity.Entity entity) {
		if (entity == null || entity.getId() >= 0) return false;
		for (Valet v : valets) if (v.entity == entity) return true;
		return false;
	}

	/** Someone else's parked cars as solid boxes, from what {@link #shareState} gave on their end. */
	public List<net.minecraft.world.phys.shapes.VoxelShape> sharedCollisions(net.minecraft.world.phys.AABB area, List<double[]> shared) {
		long now = System.nanoTime();
		List<Car> list = new ArrayList<>();
		for (double[] s : shared) {
			// Still driving in: not solid yet, as with your own
			if (s.length < 7 || s[6] < 0) continue;
			Model which = Model.values()[net.minecraft.util.Mth.clamp((int) s[0], 0, Model.values().length - 1)];
			list.add(new Car(which, new Vec3(s[1], s[2], s[3]), (float) s[4], now - (long) (s[5] * 1e9)));
		}
		return carShapes(list, area);
	}

	/** For Client Sync: your valets as they walk (x, y, z, yaw, head yaw, body yaw, walk speed, walk position). */
	public List<double[]> shareValets() {
		List<double[]> list = new ArrayList<>();
		if (!isEnabled()) return list;
		for (Valet v : valets) {
			var e = v.entity;
			list.add(new double[]{e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getYHeadRot(), e.yBodyRot, e.walkAnimation.speed(), e.walkAnimation.position()});
		}
		return list;
	}

	private List<net.minecraft.world.phys.shapes.VoxelShape> carShapes(List<Car> cars, net.minecraft.world.phys.AABB area) {
		List<net.minecraft.world.phys.shapes.VoxelShape> shapes = new ArrayList<>();
		long nowNanos = System.nanoTime();
		for (Car car : cars) {
			if (!car.parked(nowNanos)) continue;
			float half = car.model == Model.SUV ? 2.75f : 2.45f, width = car.model == Model.SUV ? 1.02f : 1.0f;
			double rad = Math.toRadians(car.yaw);
			double fx = -Math.sin(rad), fz = Math.cos(rad), rx = -Math.cos(rad), rz = -Math.sin(rad);
			// Slices along its length, each boxed as tightly as an upright box can be round a turned slice
			int slices = 8;
			for (int i = 0; i < slices; i++) {
				float z1 = -half + 2 * half * i / slices, z2 = -half + 2 * half * (i + 1) / slices;
				double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
				for (float z : new float[]{z1, z2}) {
					for (float x : new float[]{-width, width}) {
						double wx = car.pos.x + rx * x + fx * z, wz = car.pos.z + rz * x + fz * z;
						minX = Math.min(minX, wx);
						maxX = Math.max(maxX, wx);
						minZ = Math.min(minZ, wz);
						maxZ = Math.max(maxZ, wz);
					}
				}
				net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(minX, car.pos.y, minZ, maxX, car.pos.y + heightAt(car.model, (z1 + z2) / 2), maxZ);
				if (box.intersects(area)) shapes.add(net.minecraft.world.phys.shapes.Shapes.create(box));
			}
		}
		return shapes;
	}

	/** The middle of the parked car nearest {@code near} within {@code range}, a Jesko before anything else, or null. */
	public Vec3 nearestCar(Vec3 near, double range) {
		if (!isEnabled()) return null;
		Car best = null;
		double bestDistance = range * range;
		for (Car car : cars) {
			double d = car.pos.distanceToSqr(near) - (car.model == Model.JESKO ? 400 : 0);
			if (car.pos.distanceToSqr(near) <= range * range && (best == null || d < bestDistance)) {
				best = car;
				bestDistance = d;
			}
		}
		return best == null ? null : best.pos;
	}

	public CarsModule() {
		super("Cars", "Park flex cars next to you: a luxury SUV or a hypercar. Use " + CommandManager.PREFIX + "car", Category.LARP);
	}

	@Override
	public String getInfo() {
		return cars.isEmpty() ? null : String.valueOf(cars.size());
	}

	/** Handles {@code .car [spawn [suv|jesko]|remove|clear]}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "spawn";
		switch (action) {
			case "spawn", "park", "add" -> {
				if (mc.player == null) return;
				Model which = model.get();
				if (args.length > 2) {
					String name = args[2].toLowerCase(Locale.ROOT);
					if (name.equals("supercar") || name.equals("hypercar")) name = "jesko";
					if (name.equals("maybach")) name = "suv";
					try {
						which = Model.valueOf(name.toUpperCase(Locale.ROOT));
					} catch (IllegalArgumentException e) {
						ChatUtil.info("Models: suv, jesko");
						return;
					}
				}
				// In front of you, side on, so you see the whole thing
				float yaw = mc.player.getYRot();
				double rad = Math.toRadians(yaw);
				Vec3 at = mc.player.position().add(-Math.sin(rad) * 4.5, 0, Math.cos(rad) * 4.5);
				long now = System.nanoTime();
				Car car;
				if (valet.get()) {
					// It comes from behind its spot, along the way it'll face, and pulls up
					double carRad = Math.toRadians(yaw + 90);
					Vec3 from = at.add(Math.sin(carRad) * 28, 0, -Math.cos(carRad) * 28);
					car = new Car(which, at, yaw + 90, now, from, now + DRIVE_IN);
					awaitingValet.add(car);
				} else car = new Car(which, at, yaw + 90, now);
				cars.add(car);
				if (!isEnabled()) setEnabled(true);
			}
			case "remove", "undo" -> {
				if (!cars.isEmpty()) cars.removeLast();
			}
			case "clear" -> {
				cars.clear();
				removeValets();
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "car <spawn [suv|jesko]|remove|clear>");
		}
	}

	@Override
	public void onTick() {
		tickRide();
		tickGettingIn();
		long now = System.nanoTime();
		// A car's parked: its valet gets out of the driver's door
		for (java.util.Iterator<Car> it = awaitingValet.iterator(); it.hasNext(); ) {
			Car car = it.next();
			if (!car.parked(now)) continue;
			it.remove();
			double rad = Math.toRadians(car.yaw);
			Vec3 door = car.pos.add(Math.cos(rad) * 1.4, 0, Math.sin(rad) * 1.4);
			var entity = new net.minecraft.client.player.RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Valet"));
			entity.setId(nextValetId--);
			entity.snapTo(door.x, door.y, door.z, car.yaw, 0);
			mc.level.addEntity(entity);
			valets.add(new Valet(entity, car));
		}
		for (java.util.Iterator<Valet> it = valets.iterator(); it.hasNext(); ) {
			Valet v = it.next();
			v.ticks++;
			Vec3 you = mc.player.position();
			if (v.stage == 0) {
				// Up to you, stopping a step short and facing you
				Vec3 toYou = you.subtract(v.entity.position());
				Vec3 spot = outsideCar(v.car, toYou.lengthSqr() > 4 ? you.subtract(toYou.normalize().scale(1.4)) : v.entity.position());
				walkAround(v, spot, (float) Math.toDegrees(Math.atan2(-toYou.x, toYou.z)));
				if (v.entity.position().distanceTo(spot) < 0.5 || v.ticks > 200) {
					v.stage = 1;
					v.ticks = 0;
					dev.femboyware.util.Mc.addChat(net.minecraft.network.chat.Component.literal("<Valet> Your car is ready."));
				}
			} else if (v.stage == 1) {
				Vec3 toYou = you.subtract(v.entity.position());
				v.walker.tickFormation(v.entity, mc.level, v.entity.position(), (float) Math.toDegrees(Math.atan2(-toYou.x, toYou.z)));
				if (v.ticks > 50) {
					v.stage = 2;
					v.ticks = 0;
					Vec3 away = v.entity.position().subtract(you);
					away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
					v.leaveTo = v.entity.position().add(away.scale(22));
				}
			} else {
				walkAround(v, v.leaveTo, 0);
				if (v.entity.position().distanceTo(v.leaveTo) < 1 || v.ticks > 300) {
					mc.level.removeEntity(v.entity.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
					it.remove();
				}
			}
		}
	}

	/** Walks a valet toward {@code target}, round the corners of his car rather than through it. */
	private void walkAround(Valet v, Vec3 target, float facing) {
		Vec3 step = nextStep(v.car, v.entity.position(), target);
		float yaw = step == target ? facing : (float) Math.toDegrees(Math.atan2(-(step.x - v.entity.getX()), step.z - v.entity.getZ()));
		v.walker.tickFormation(v.entity, mc.level, step, yaw);
	}

	/** Half the car's length and width, plus room to walk past it. */
	private static float[] clearance(Car car) {
		float half = car.model == Model.SUV ? 2.75f : 2.45f, width = car.model == Model.SUV ? 1.02f : 1.0f;
		return new float[]{width + 0.65f, half + 0.65f};
	}

	/** A world point in the car's own space: x to its right, z forward. */
	private static double[] toCar(Car car, Vec3 p) {
		double rad = Math.toRadians(car.yaw), dx = p.x - car.pos.x, dz = p.z - car.pos.z;
		return new double[]{dx * -Math.cos(rad) + dz * -Math.sin(rad), dx * -Math.sin(rad) + dz * Math.cos(rad)};
	}

	private static Vec3 fromCar(Car car, double x, double z, double y) {
		double rad = Math.toRadians(car.yaw);
		return new Vec3(car.pos.x - Math.cos(rad) * x - Math.sin(rad) * z, y, car.pos.z - Math.sin(rad) * x + Math.cos(rad) * z);
	}

	/** {@code p}, or the nearest spot just outside the car if it's inside it. */
	private static Vec3 outsideCar(Car car, Vec3 p) {
		float[] room = clearance(car);
		double[] l = toCar(car, p);
		if (Math.abs(l[0]) >= room[0] || Math.abs(l[1]) >= room[1]) return p;
		// Out the nearer side
		if (room[0] - Math.abs(l[0]) < room[1] - Math.abs(l[1])) l[0] = Math.copySign(room[0], l[0]);
		else l[1] = Math.copySign(room[1], l[1]);
		return fromCar(car, l[0], l[1], p.y);
	}

	/** Whether the straight line from a to b (car space) crosses the box |x| < rx, |z| < rz. */
	private static boolean crosses(double[] a, double[] b, double rx, double rz) {
		double t0 = 0, t1 = 1, dx = b[0] - a[0], dz = b[1] - a[1];
		double[] p = {-dx, dx, -dz, dz}, q = {a[0] + rx, rx - a[0], a[1] + rz, rz - a[1]};
		for (int i = 0; i < 4; i++) {
			if (p[i] == 0) {
				if (q[i] < 0) return false;
				continue;
			}
			double r = q[i] / p[i];
			if (p[i] < 0) t0 = Math.max(t0, r);
			else t1 = Math.min(t1, r);
			if (t0 > t1) return false;
		}
		return true;
	}

	/**
	 * Where to head next on the way to {@code to}: straight there if the car isn't in the way, else
	 * to whichever corner just outside it he can walk to directly that makes the shortest way round.
	 */
	private static Vec3 nextStep(Car car, Vec3 from, Vec3 to) {
		float[] room = clearance(car);
		double[] a = toCar(car, from), b = toCar(car, to);
		double rx = room[0] - 0.15, rz = room[1] - 0.15;
		// Just out of the door: step clear of the car first
		if (Math.abs(a[0]) < rx && Math.abs(a[1]) < rz) return outsideCar(car, from);
		if (!crosses(a, b, rx, rz)) return to;
		double best = Double.MAX_VALUE;
		double[] pick = null;
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				double[] c = {sx * room[0], sz * room[1]};
				double near = Math.hypot(c[0] - a[0], c[1] - a[1]);
				if (near < 0.35 || crosses(a, c, rx, rz)) continue;
				double length = near + Math.hypot(b[0] - c[0], b[1] - c[1]);
				if (length < best) {
					best = length;
					pick = c;
				}
			}
		}
		return pick == null ? to : fromCar(car, pick[0], pick[1], from.y);
	}

	/** Parks a car at {@code at} facing {@code yaw}, already there, for the hangar. Returns it, to take away later. */
	public Object parkAt(Model which, Vec3 at, float yaw) {
		Car car = new Car(which, at, yaw, System.nanoTime());
		cars.add(car);
		if (!isEnabled()) setEnabled(true);
		return car;
	}

	/** For LarpSave: each car as model, x, y, z, yaw, and 1 if the hangar parked it. */
	public List<double[]> saveCars(HangarModule hangar) {
		List<double[]> out = new ArrayList<>();
		for (Car car : cars) out.add(new double[]{car.model.ordinal(), car.pos.x, car.pos.y, car.pos.z, car.yaw, hangar.parked(car) ? 1 : 0});
		return out;
	}

	/** For LarpSave: puts your cars back where they were saved, already parked. Not while you're driving one. */
	public void restoreCars(List<double[]> saved, HangarModule hangar) {
		if (riding != null) return;
		cars.clear();
		long parkedLongAgo = System.nanoTime() - 60_000_000_000L;
		for (double[] c : saved) {
			if (c == null || c.length < 5) continue;
			Model[] models = Model.values();
			Car car = new Car(models[Math.floorMod((int) c[0], models.length)], new Vec3(c[1], c[2], c[3]), (float) c[4], parkedLongAgo);
			cars.add(car);
			if (c.length > 5 && c[5] == 1) hangar.adopt(car);
		}
		if (!cars.isEmpty() && !isEnabled()) setEnabled(true);
	}

	/** Takes away a car {@link #parkAt} parked. */
	public void remove(Object car) {
		cars.remove(car);
	}

	/** The valet's black suit. */
	public static final net.minecraft.world.entity.player.PlayerSkin SUIT = new net.minecraft.world.entity.player.PlayerSkin(
		new net.minecraft.core.ClientAsset.ResourceTexture(net.minecraft.resources.Identifier.fromNamespaceAndPath("femboyware", "valet_skin"),
			net.minecraft.resources.Identifier.fromNamespaceAndPath("femboyware", "textures/larp/valet_skin.png")),
		null, null, net.minecraft.world.entity.player.PlayerModelType.WIDE, true);

	/** For the skin mixin: valets wear the suit. */
	public net.minecraft.world.entity.player.PlayerSkin skinFor(net.minecraft.client.player.AbstractClientPlayer player, net.minecraft.world.entity.player.PlayerSkin textures) {
		for (Valet v : valets) if (v.entity == player) return SUIT;
		if (chauffeur != null && player == chauffeur) return SUIT;
		return textures;
	}

	private void removeValets() {
		for (Valet v : valets) {
			if (mc.level != null) mc.level.removeEntity(v.entity.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		}
		valets.clear();
		awaitingValet.clear();
	}

	@Override
	protected void onDisable() {
		if (riding != null) getOut(null);
		removeValets();
		cars.clear();
	}

	// ---------------------------------------------------------------- driving and being driven

	/** The car you're in, and whether a chauffeur's driving it (the Maybach) or you are (the Jesko). */
	private Car riding;
	private boolean chauffeured;
	/** Where it is this tick and last, which way it faces, how fast it's going (blocks a tick), and how far it's gone (for the wheels). */
	private Vec3 rideAt, rideAtOld;
	private float rideYaw, rideYawOld;
	private double rideSpeed, rideDistance;
	/** The chauffeur's loop: its middle and how far round he's got, and how long he's been stuck. */
	private Vec3 loopCenter;
	private double loopAngle, loopRadius;
	private int stuckTicks;
	private net.minecraft.client.player.RemotePlayer chauffeur;
	private static final int CHAUFFEUR_ID = -33000;
	/** The wheel: eases towards where A and D point it instead of snapping, so turns sweep in and out. */
	private float steer;
	/** How fast the car's turning, degrees a tick, eased the same way for the chauffeur. */
	private float yawRate;
	/** How hard you're on the throttle, eased, for the engine note. */
	private float throttle;
	private EngineSound engineSound;

	/** Seats in each car's own space (x right, y up, z forward): where your feet go, so you sit on the cushion. */
	private static final float[] JESKO_SEAT = {-0.36f, -0.62f, 0.05f}, MAYBACH_REAR = {0.45f, 0.0f, -1.35f}, MAYBACH_DRIVER = {-0.45f, 0.0f, 0.3f};
	private static final double JESKO_TOP = 1.6, CHAUFFEUR_CRUISE = 0.42, SEATED_EYE = 1.62;

	private static boolean larping() {
		return dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.utility.ServerLarpModule.class).isLarping();
	}

	public boolean riding() {
		return riding != null;
	}

	/** The car nearest you of a model, parked and within reach, or null. */
	private Car nearestParked(Model which, double range) {
		if (mc.player == null) return null;
		long now = System.nanoTime();
		Car best = null;
		double bestDistance = range * range;
		for (Car car : cars) {
			if (car.model != which || !car.parked(now)) continue;
			double d = car.pos.distanceToSqr(mc.player.position());
			if (d < bestDistance) {
				best = car;
				bestDistance = d;
			}
		}
		return best;
	}

	/** Whether you're looking at this car from close by. */
	private boolean aimingAt(Car car) {
		Vec3 eye = mc.player.getEyePosition(), reach = eye.add(mc.player.getViewVector(1).scale(5));
		net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(eye, reach).inflate(1);
		for (var shape : carShapes(List.of(car), area)) {
			if (shape.bounds().contains(eye) || shape.bounds().clip(eye, reach).isPresent()) return true;
		}
		return false;
	}

	/** Gets in: the driver's seat of the Jesko, or the back of the Maybach with the chauffeur up front. */
	private void getIn(Car car) {
		if (!larping()) {
			ChatUtil.info("Turn on Server Larp to get in, so the server never sees you drive off");
			return;
		}
		riding = car;
		chauffeured = car.model == Model.SUV;
		rideAt = rideAtOld = car.pos;
		rideYaw = rideYawOld = car.yaw;
		rideSpeed = 0;
		rideDistance = 0;
		stuckTicks = 0;
		steer = yawRate = throttle = 0;
		mc.player.setNoGravity(true);
		// The engine catches, then keeps running under you
		boolean jesko = car.model == Model.JESKO;
		LarpSounds.play(LarpSounds.CAR_START, jesko ? 1.0f : 0.8f, jesko ? 0.8f : 0.4f);
		if (engineSound != null) engineSound.end();
		engineSound = new EngineSound();
		mc.getSoundManager().play(engineSound);
		if (chauffeured) {
			// A big loop round from here, turning right, back to where it started
			loopRadius = 26;
			double rad = Math.toRadians(car.yaw);
			Vec3 right = new Vec3(-Math.cos(rad), 0, -Math.sin(rad));
			loopCenter = car.pos.add(right.scale(loopRadius));
			Vec3 from = car.pos.subtract(loopCenter);
			loopAngle = Math.atan2(from.z, from.x);
			dev.femboyware.util.Mc.addChat(net.minecraft.network.chat.Component.literal("<Chauffeur> Good evening. Sit back and enjoy the ride. Sneak whenever you'd like to step out."));
		} else {
			dev.femboyware.util.Mc.actionBar("W/S drive and brake, A/D steer, sneak to get out");
		}
	}

	/** Gets out beside the car, which stays where it stopped. */
	private void getOut(String message) {
		Car car = riding;
		riding = null;
		chauffeured = false;
		removeChauffeur();
		if (engineSound != null) {
			engineSound.end();
			engineSound = null;
		}
		if (mc.player == null || car == null) return;
		mc.player.setNoGravity(false);
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.resetFallDistance();
		// Out of the door on the side you sat
		float side = car.model == Model.SUV ? 1.6f : -1.5f;
		double rad = Math.toRadians(car.yaw);
		Vec3 right = new Vec3(-Math.cos(rad), 0, -Math.sin(rad));
		Vec3 out = car.pos.add(right.scale(side));
		mc.player.snapTo(out.x, out.y, out.z, mc.player.getYRot(), mc.player.getXRot());
		mc.player.setOldPosAndRot();
		if (message != null) dev.femboyware.util.Mc.actionBar(message);
	}

	/** The top of the ground under a point, looking a little up and down from {@code fromY}; NaN if there's none in reach. */
	private double groundAt(double x, double fromY, double z) {
		net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		for (int dy = 2; dy >= -4; dy--) {
			pos.set(net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(fromY) + dy, net.minecraft.util.Mth.floor(z));
			var state = mc.level.getBlockState(pos);
			var shape = state.getCollisionShape(mc.level, pos);
			if (shape.isEmpty()) continue;
			double top = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
			if (top <= fromY + 1.05) return top;
		}
		return Double.NaN;
	}

	/** One tick of the car moving: on along its heading at its speed, following the ground, stopped by walls. */
	private void moveCar() {
		double rad = Math.toRadians(rideYaw);
		Vec3 next = rideAt.add(-Math.sin(rad) * rideSpeed, 0, Math.cos(rad) * rideSpeed);
		// The ground at the front of the car decides whether it can go there
		double ground = groundAt(next.x, rideAt.y, next.z);
		if (Double.isNaN(ground)) {
			// Nothing within a few blocks down: let it roll off, falling
			next = new Vec3(next.x, rideAt.y - 0.4, next.z);
		} else if (ground > rideAt.y + 1.05) {
			// A wall: it stops dead
			rideSpeed = 0;
			stuckTicks++;
			return;
		} else {
			// Eased onto the ground's height rather than snapped to it, so steps and slopes ride like suspension
			next = new Vec3(next.x, ground < rideAt.y - 0.5 ? Math.max(ground, rideAt.y - 0.5) : rideAt.y + (ground - rideAt.y) * 0.35, next.z);
		}
		rideDistance += Math.abs(rideSpeed);
		if (Math.abs(rideSpeed) > 0.02) stuckTicks = 0;
		rideAt = next;
	}

	/** The Jesko, driven by you: throttle, brake and reverse on W and S, steering on A and D. */
	private void driveJesko() {
		var input = mc.player.input.getMoveVector();
		if (input.y > 0) rideSpeed = Math.min(JESKO_TOP, rideSpeed + 0.03 * (1 - Math.max(0, rideSpeed) / JESKO_TOP * 0.6));
		else if (input.y < 0) rideSpeed = rideSpeed > 0.02 ? Math.max(0, rideSpeed - 0.07) : Math.max(-0.3, rideSpeed - 0.02);
		else rideSpeed *= 0.985;
		if (Math.abs(rideSpeed) < 0.003) rideSpeed = 0;
		throttle += ((input.y != 0 ? 1 : 0) - throttle) * 0.25f;
		// The wheel turns in and back out over a few ticks instead of snapping
		steer += (input.x - steer) * 0.22f;
		if (Math.abs(steer) < 0.01f && input.x == 0) steer = 0;
		// Steering bites harder at low speed, gentler flat out; no turning on the spot
		float grip = (float) Math.min(1, Math.abs(rideSpeed) * 4);
		float rate = (float) (3.6 - 1.6 * Math.min(1, Math.abs(rideSpeed) / JESKO_TOP));
		rideYaw -= steer * rate * grip * Math.signum(rideSpeed);
		moveCar();
	}

	/** The Maybach, driven by the chauffeur: a slow, smooth loop round, steering for the next point on it. */
	private void chauffeurDrive() {
		double ahead = loopAngle + 0.35;
		Vec3 target = loopCenter.add(Math.cos(ahead) * loopRadius, 0, Math.sin(ahead) * loopRadius);
		Vec3 to = target.subtract(rideAt);
		float want = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		float turn = net.minecraft.util.Mth.wrapDegrees(want - rideYaw);
		// Turning in and out gradually, like a careful driver, rather than at a fixed rate the moment he decides to
		yawRate += net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.clamp(turn * 0.5f, -3f, 3f) - yawRate, -0.3f, 0.3f);
		rideYaw += yawRate;
		throttle += (0.4f - throttle) * 0.1f;
		// Easing off for tight turns, then up to cruising
		double speedFor = CHAUFFEUR_CRUISE * (1 - Math.min(0.6, Math.abs(turn) / 90));
		rideSpeed += net.minecraft.util.Mth.clamp(speedFor - rideSpeed, -0.03, 0.015);
		moveCar();
		// How far round the loop the car is now
		Vec3 from = rideAt.subtract(loopCenter);
		double angle = Math.atan2(from.z, from.x);
		if (net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(angle - loopAngle)) > 0) loopAngle = angle;
		// Stuck against something: he tries further round
		if (stuckTicks > 20) {
			loopAngle += 0.6;
			stuckTicks = 0;
		}
	}

	/** Runs the ride each tick: the car moves, you're carried along in your seat. */
	private void tickRide() {
		if (riding == null || mc.player == null) return;
		// Where it was before this tick's steering and moving, so both are smoothed between frames
		// (and you turn with it); taken after the steering, the turn would jump once a tick
		rideAtOld = rideAt;
		rideYawOld = rideYaw;
		if (!larping() || !cars.contains(riding)) {
			getOut("Server Larp went off, so you're out of the car");
			return;
		}
		if (mc.options.keyShift.isDown()) {
			// Stopping first, then out
			rideSpeed = Math.abs(rideSpeed) < 0.06 ? 0 : rideSpeed * 0.8;
			rideAtOld = rideAt;
			rideYawOld = rideYaw;
			if (rideSpeed == 0) {
				boolean wasChauffeured = chauffeured;
				getOut(wasChauffeured ? null : "You're out of the Jesko");
				if (wasChauffeured) dev.femboyware.util.Mc.addChat(net.minecraft.network.chat.Component.literal("<Chauffeur> We've arrived. Have a wonderful evening."));
				return;
			}
		} else if (chauffeured) chauffeurDrive();
		else driveJesko();
		// The car in the list moves with it; it's put back where it stops when you get out
		int index = cars.indexOf(riding);
		Car moved = new Car(riding.model, rideAt, rideYaw, riding.born, rideAt.add(rideDistance, 0, 0), riding.born);
		cars.set(index, moved);
		riding = moved;
		// You turn with the car
		mc.player.setYRot(mc.player.getYRot() + (rideYaw - rideYawOld));
		seat(mc.player, chauffeured ? MAYBACH_REAR : JESKO_SEAT, rideAt, rideYaw);
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.setOnGround(true);
		mc.player.resetFallDistance();
		placeChauffeur(rideAt, rideYaw);
	}

	/**
	 * The engine while you're in a car: one seamless loop, its pitch following the revs. The Jesko
	 * pulls up through its gears (the note climbs, drops back a little at each shift and climbs
	 * again) and is louder on the throttle; the Maybach is a low, quiet hum. It fades out once
	 * you're out.
	 */
	private final class EngineSound extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
		private boolean ending;

		EngineSound() {
			super(LarpSounds.CAR_ENGINE, net.minecraft.sounds.SoundSource.PLAYERS, net.minecraft.util.RandomSource.create());
			this.looping = true;
			this.delay = 0;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
			this.volume = 0.01f;
			this.pitch = 0.5f;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			if (ending || riding == null) {
				volume -= 0.06f;
				if (volume <= 0.01f) stop();
				return;
			}
			float speed = (float) Math.min(1, Math.abs(rideSpeed) / (chauffeured ? CHAUFFEUR_CRUISE * 1.6 : JESKO_TOP));
			float wantPitch, wantVolume;
			if (chauffeured) {
				wantPitch = 0.5f + 0.35f * speed;
				wantVolume = 0.16f + 0.1f * speed;
			} else {
				// Seven gears: each one revs up from where the last shift dropped it to
				float gears = speed * 7, inGear = gears - (float) Math.floor(Math.min(6.999f, gears));
				wantPitch = speed < 0.02f ? 0.55f : 0.62f + 0.12f * Math.min(6, (float) Math.floor(gears)) / 6 + 0.75f * inGear;
				wantVolume = 0.3f + 0.25f * speed + 0.3f * throttle;
			}
			pitch += (Math.min(2, wantPitch) - pitch) * 0.35f;
			volume += (wantVolume - volume) * 0.25f;
		}

		void end() {
			ending = true;
		}
	}

	/** Puts someone in a seat of a car at {@code at} facing {@code yaw}. */
	private static void seat(net.minecraft.world.entity.Entity who, float[] seat, Vec3 at, float yaw) {
		double rad = Math.toRadians(yaw);
		double rx = -Math.cos(rad), rz = -Math.sin(rad), fx = -Math.sin(rad), fz = Math.cos(rad);
		who.setPos(at.x + rx * seat[0] + fx * seat[2], at.y + seat[1], at.z + rz * seat[0] + fz * seat[2]);
	}

	/** Called from CameraMixin each frame: you, and the car, exactly where they are this frame, so nothing judders. */
	public void placeFrame() {
		if (riding == null || mc.player == null || rideAtOld == null) return;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 at = rideAtOld.lerp(rideAt, partial);
		float yaw = net.minecraft.util.Mth.rotLerp(partial, rideYawOld, rideYaw);
		seat(mc.player, chauffeured ? MAYBACH_REAR : JESKO_SEAT, at, yaw);
		mc.player.xo = mc.player.xOld = mc.player.getX();
		mc.player.yo = mc.player.yOld = mc.player.getY();
		mc.player.zo = mc.player.zOld = mc.player.getZ();
		mc.player.yBodyRot = mc.player.yBodyRotO = yaw;
		placeChauffeur(at, yaw);
	}

	/**
	 * For CameraMixin: where the camera is this frame while you're in a car. In first person, your
	 * eyes in your seat exactly where the car is drawn; in third person, orbiting the car with the
	 * mouse. Handed straight to the camera, so it never trails the car by a frame. Null when you
	 * aren't in one.
	 */
	public CinematicCameraModule.View view(float tickProgress) {
		if (riding == null || mc.player == null || rideAtOld == null) return null;
		Vec3 at = rideAtOld.lerp(rideAt, tickProgress);
		float carYaw = net.minecraft.util.Mth.rotLerp(tickProgress, rideYawOld, rideYaw);
		// Where you look relative to the car, on top of the car's own turn this frame: the car's yaw is
		// smooth between ticks, so turning with it is too (your own yaw only steps once a tick)
		float yaw = carYaw + net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - rideYaw), pitch = mc.player.getViewXRot(tickProgress);
		float[] seatPos = chauffeured ? MAYBACH_REAR : JESKO_SEAT;
		double rad = Math.toRadians(carYaw);
		double rx = -Math.cos(rad), rz = -Math.sin(rad), fx = -Math.sin(rad), fz = Math.cos(rad);
		Vec3 seatAt = new Vec3(at.x + rx * seatPos[0] + fx * seatPos[2], at.y + seatPos[1], at.z + rz * seatPos[0] + fz * seatPos[2]);
		// Your standing eye height, always: holding sneak to get out would otherwise drop your eyes into the floor
		if (mc.options.getCameraType().isFirstPerson()) return new CinematicCameraModule.View(seatAt.add(0, SEATED_EYE, 0), yaw, pitch);
		boolean front = mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
		float lookYaw = front ? yaw + 180 : yaw, lookPitch = front ? -pitch : pitch;
		double yr = Math.toRadians(lookYaw), pr = Math.toRadians(lookPitch);
		Vec3 dir = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
		Vec3 target = at.add(0, 1.2, 0);
		Vec3 cam = target.subtract(dir.scale(chauffeured ? 7 : 5.5));
		if (cam.y < at.y + 0.4) cam = new Vec3(cam.x, at.y + 0.4, cam.z);
		Vec3 look = target.subtract(cam);
		return new CinematicCameraModule.View(cam, (float) Math.toDegrees(Math.atan2(-look.x, look.z)),
			(float) Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z))));
	}

	/** The car you're in, where it's drawn this frame. */
	private Car frameCar(Car car, long now) {
		if (car != riding || rideAtOld == null) return new Car(car.model, car.at(now), car.yaw, car.born, car.from, car.arrive);
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		return new Car(car.model, rideAtOld.lerp(rideAt, partial), net.minecraft.util.Mth.rotLerp(partial, rideYawOld, rideYaw), car.born, car.from, car.arrive);
	}

	private void placeChauffeur(Vec3 at, float yaw) {
		if (!chauffeured || mc.level == null) return;
		if (chauffeur == null || chauffeur.isRemoved() || chauffeur.level() != mc.level) {
			chauffeur = new net.minecraft.client.player.RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Chauffeur"));
			chauffeur.setId(CHAUFFEUR_ID);
			mc.level.addEntity(chauffeur);
		}
		seat(chauffeur, MAYBACH_DRIVER, at, yaw);
		chauffeur.setOldPosAndRot();
		chauffeur.setYRot(yaw);
		chauffeur.yRotO = yaw;
		chauffeur.yBodyRot = chauffeur.yBodyRotO = yaw;
		chauffeur.yHeadRot = chauffeur.yHeadRotO = yaw;
	}

	private void removeChauffeur() {
		if (chauffeur != null && mc.level != null && chauffeur.level() == mc.level) mc.level.removeEntity(chauffeur.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		chauffeur = null;
	}

	/** Called from PlayerModelMixin: you and the chauffeur sit, his hands on the wheel. */
	public void poseRiders(net.minecraft.client.model.player.PlayerModel model, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
		if (riding == null || mc.player == null) return;
		boolean you = state.id == mc.player.getId(), driver = chauffeur != null && state.id == CHAUFFEUR_ID;
		if (!you && !driver) return;
		model.rightLeg.xRot = -1.45f;
		model.leftLeg.xRot = -1.45f;
		model.rightLeg.yRot = 0.1f;
		model.leftLeg.yRot = -0.1f;
		if (driver || !chauffeured) {
			model.rightArm.xRot = -1.0f;
			model.leftArm.xRot = -1.0f;
			model.rightArm.zRot = 0;
			model.leftArm.zRot = 0;
		}
	}

	/** Handles getting in by right-clicking a car while larping. */
	private void tickGettingIn() {
		if (riding != null || mc.player == null || !larping() || dev.femboyware.util.Mc.screen() != null) return;
		long now = System.nanoTime();
		Car target = null;
		for (Car car : cars) {
			if (car.parked(now) && car.pos.distanceToSqr(mc.player.position()) < 8 * 8 && aimingAt(car)) target = car;
		}
		if (target == null) return;
		boolean clicked = false;
		while (mc.options.keyUse.consumeClick()) clicked = true;
		if (clicked) getIn(target);
	}

	// ---------------------------------------------------------------- the valet app

	/** For the phone's valet app: has a valet bring a car round to you. */
	public void summon(Model which) {
		Model was = model.get();
		model.set(which);
		command(new String[]{"car", "spawn"});
		model.set(was);
	}

	/** For the valet app: your cars, by model, and how far away. */
	public List<String> parkedSummary() {
		List<String> out = new ArrayList<>();
		if (mc.player == null) return out;
		for (Car car : cars) {
			int distance = (int) Math.round(car.pos.distanceTo(mc.player.position()));
			out.add((car.model == Model.SUV ? "Maybach" : "Jesko") + (car == riding ? "  ·  you're in it" : "  ·  " + distance + " blocks away"));
		}
		return out;
	}

	/** For the valet app: takes your most recent car away. */
	public void sendAway() {
		for (int i = cars.size() - 1; i >= 0; i--) {
			if (cars.get(i) == riding) continue;
			cars.remove(i);
			return;
		}
	}

	/** For the valet app: gets you into the nearest car of a model, if it's near enough. */
	public String getInNearest(Model which) {
		if (riding != null) return "You're already in a car";
		Car car = nearestParked(which, 12);
		if (car == null) return "Have the valet bring it round first";
		getIn(car);
		return riding != null ? null : "Turn on Server Larp first";
	}

	// ---------------------------------------------------------------- drawing

	/** Builds a car's parts in its own space: x across (right), y up, z forward. Units are blocks. */
	private final class Builder {
		final VertexConsumer c;
		final PoseStack.Pose pose;
		final float cos, sin, ox, oy, oz;
		/** The body's lift and nose-up pitch from the hydraulics; wheels don't get them. */
		float lift, pitch;
		/** The whole car tipped nose up (radians), wheels and all: driving up a slope. */
		float tilt;
		boolean body = true;
		/** How much taller than built the body is drawn; wheels and their arch trims aren't stretched. */
		float stretch = 1;

		/** How lit the car is where it's parked (see LarpLight); interiors keep a softer floor. */
		float light;
		/** Vertices held back to draw later, when built without a consumer (for see-through glass). */
		final List<float[]> held = new ArrayList<>();

		Builder(VertexConsumer c, PoseStack.Pose pose, Car car, Vec3 cam) {
			this.c = c;
			this.pose = pose;
			this.light = LarpLight.at(car.pos.x, car.pos.y + 1.2, car.pos.z);
			double rad = Math.toRadians(car.yaw);
			// Car +z points where the yaw faces; +x to its right
			cos = (float) Math.cos(rad);
			sin = (float) Math.sin(rad);
			ox = (float) (car.pos.x - cam.x);
			oy = (float) (car.pos.y - cam.y);
			oz = (float) (car.pos.z - cam.z);
		}

		void vertex(float x, float y, float z, int color) {
			if (body) {
				y = y * stretch + lift + z * pitch;
			}
			if (tilt != 0) {
				float c0 = (float) Math.cos(tilt), s0 = (float) Math.sin(tilt), ty = y * c0 + z * s0;
				z = z * c0 - y * s0;
				y = ty;
			}
			// Facing yaw: forward is (-sin, cos); right is (-cos, -sin)
			float wx = ox - x * cos - z * sin, wz = oz - x * sin + z * cos;
			color = LarpLight.shade(color, light);
			if (c == null) held.add(new float[]{wx, oy + y, wz, Float.intBitsToFloat(color)});
			else FxRender.vertex(c, pose, wx, oy + y, wz, color);
		}

		/** Draws what was held back into {@code into}. */
		void flush(VertexConsumer into) {
			for (float[] v : held) FxRender.vertex(into, pose, v[0], v[1], v[2], Float.floatToRawIntBits(v[3]));
			held.clear();
		}

		void quad(float[] a, float[] b, float[] cc, float[] d, int color) {
			vertex(a[0], a[1], a[2], color);
			vertex(b[0], b[1], b[2], color);
			vertex(cc[0], cc[1], cc[2], color);
			vertex(d[0], d[1], d[2], color);
		}

		/** A shaded box: lit from above, sides a bit darker, ends darker still. */
		void box(float x1, float y1, float z1, float x2, float y2, float z2, int color) {
			int top = color, side = shade(color, 0.8f), end = shade(color, 0.68f), bottom = shade(color, 0.45f);
			quad(new float[]{x1, y2, z1}, new float[]{x1, y2, z2}, new float[]{x2, y2, z2}, new float[]{x2, y2, z1}, top);
			quad(new float[]{x1, y1, z1}, new float[]{x2, y1, z1}, new float[]{x2, y1, z2}, new float[]{x1, y1, z2}, bottom);
			quad(new float[]{x1, y1, z1}, new float[]{x1, y1, z2}, new float[]{x1, y2, z2}, new float[]{x1, y2, z1}, side);
			quad(new float[]{x2, y1, z2}, new float[]{x2, y1, z1}, new float[]{x2, y2, z1}, new float[]{x2, y2, z2}, side);
			quad(new float[]{x1, y1, z2}, new float[]{x2, y1, z2}, new float[]{x2, y2, z2}, new float[]{x1, y2, z2}, end);
			quad(new float[]{x2, y1, z1}, new float[]{x1, y1, z1}, new float[]{x1, y2, z1}, new float[]{x2, y2, z1}, end);
		}

		/**
		 * A shape drawn side on as a convex outline of (z, y) points going round, made solid across
		 * {@code x1..x2}: for sloped parts like the windshield.
		 */
		void prism(float[][] outline, float x1, float x2, int color) {
			int n = outline.length;
			for (int i = 1; i < n - 1; i++) {
				float[] a = outline[0], p = outline[i], q = outline[i + 1];
				int side = shade(color, 0.8f);
				quad(new float[]{x1, a[1], a[0]}, new float[]{x1, p[1], p[0]}, new float[]{x1, q[1], q[0]}, new float[]{x1, q[1], q[0]}, side);
				quad(new float[]{x2, a[1], a[0]}, new float[]{x2, p[1], p[0]}, new float[]{x2, q[1], q[0]}, new float[]{x2, q[1], q[0]}, side);
			}
			for (int i = 0; i < n; i++) {
				float[] p = outline[i], q = outline[(i + 1) % n];
				float dz = q[0] - p[0], dy = q[1] - p[1], length = (float) Math.sqrt(dz * dz + dy * dy);
				// Facing up gets the light; facing down is dark
				float up = length == 0 ? 0 : -dz / length;
				float light = up > 0 ? 0.68f + 0.32f * up : 0.68f + 0.23f * up;
				quad(new float[]{x1, p[1], p[0]}, new float[]{x2, p[1], p[0]}, new float[]{x2, q[1], q[0]}, new float[]{x1, q[1], q[0]}, shade(color, light));
			}
		}

		/** The top half of a ring on the side at {@code x} round (z, y): trim round a wheel arch. */
		void arch(float x, float y, float z, float inner, float outer, int color) {
			float was = stretch;
			stretch = 1;
			int segments = 14;
			for (int i = 0; i < segments; i++) {
				double a1 = Math.PI * i / segments, a2 = Math.PI * (i + 1) / segments;
				quad(new float[]{x, y + (float) Math.sin(a1) * inner, z + (float) Math.cos(a1) * inner}, new float[]{x, y + (float) Math.sin(a1) * outer, z + (float) Math.cos(a1) * outer},
					new float[]{x, y + (float) Math.sin(a2) * outer, z + (float) Math.cos(a2) * outer}, new float[]{x, y + (float) Math.sin(a2) * inner, z + (float) Math.cos(a2) * inner}, color);
			}
			stretch = was;
		}

		/** A flat band {@code width} wide on the side at {@code x}, from (z1, y1) to (z2, y2): trims and slanted pillars. */
		void strip(float x, float z1, float y1, float z2, float y2, float width, int color) {
			float dz = z2 - z1, dy = y2 - y1, length = (float) Math.sqrt(dz * dz + dy * dy);
			float nz = -dy / length * width / 2, ny = dz / length * width / 2;
			int c = shade(color, 0.85f);
			quad(new float[]{x, y1 - ny, z1 - nz}, new float[]{x, y2 - ny, z2 - nz}, new float[]{x, y2 + ny, z2 + nz}, new float[]{x, y1 + ny, z1 + nz}, c);
		}

		/** A wheel on its side at {@code x}: tyre, a chrome rim ring, and many fine spokes turned by {@code spin}. */
		void wheel(float x, float y, float z, float radius, float width, float spin, int rim) {
			wheel(x, y, z, radius, width, spin, rim, 18, 0.03f, 0);
		}

		/** A wheel with {@code spokes} spokes {@code spokeWidth} of the radius wide, and a brake caliper in {@code caliper} (0 for none). */
		void wheel(float x, float y, float z, float radius, float width, float spin, int rim, int spokes, float spokeWidth, int caliper) {
			boolean wasBody = body;
			body = false;
			int segments = 16;
			float outer = x + Math.signum(x) * width / 2, inner = x - Math.signum(x) * width / 2;
			for (int i = 0; i < segments; i++) {
				double a1 = Math.PI * 2 * i / segments, a2 = Math.PI * 2 * (i + 1) / segments;
				float y1 = y + (float) Math.sin(a1) * radius, z1 = z + (float) Math.cos(a1) * radius;
				float y2 = y + (float) Math.sin(a2) * radius, z2 = z + (float) Math.cos(a2) * radius;
				quad(new float[]{inner, y1, z1}, new float[]{outer, y1, z1}, new float[]{outer, y2, z2}, new float[]{inner, y2, z2}, shade(TIRE, 0.7f + 0.3f * (float) Math.sin((a1 + a2) / 2)));
				// The outside: tyre wall round a rim
				float r1 = radius * 0.7f;
				float iy1 = y + (float) Math.sin(a1) * r1, iz1 = z + (float) Math.cos(a1) * r1, iy2 = y + (float) Math.sin(a2) * r1, iz2 = z + (float) Math.cos(a2) * r1;
				float face = outer + Math.signum(x) * 0.002f;
				quad(new float[]{face, iy1, iz1}, new float[]{face, y1, z1}, new float[]{face, y2, z2}, new float[]{face, iy2, iz2}, TIRE);
				quad(new float[]{face, y, z}, new float[]{face, iy1, iz1}, new float[]{face, iy2, iz2}, new float[]{face, y, z}, shade(rim, 0.55f));
			}
			// The brake caliper, gripping the disc up at the front, behind the spokes
			if (caliper != 0) {
				float cf = outer + Math.signum(x) * 0.004f;
				for (int i = 0; i < 6; i++) {
					double a1 = 0.35 + i * 0.13, a2 = a1 + 0.13;
					float in = radius * 0.34f, out = radius * 0.6f;
					quad(new float[]{cf, y + (float) Math.sin(a1) * in, z + (float) Math.cos(a1) * in}, new float[]{cf, y + (float) Math.sin(a1) * out, z + (float) Math.cos(a1) * out},
						new float[]{cf, y + (float) Math.sin(a2) * out, z + (float) Math.cos(a2) * out}, new float[]{cf, y + (float) Math.sin(a2) * in, z + (float) Math.cos(a2) * in}, caliper);
				}
			}
			// A lip, then the spokes and the hub
			float face = outer + Math.signum(x) * 0.006f, r = radius * 0.68f, half = radius * spokeWidth;
			for (int i = 0; i < segments; i++) {
				double a1 = Math.PI * 2 * i / segments, a2 = Math.PI * 2 * (i + 1) / segments;
				float lip = radius * 0.72f, in = radius * 0.64f, lf = face + Math.signum(x) * 0.002f;
				quad(new float[]{lf, y + (float) Math.sin(a1) * in, z + (float) Math.cos(a1) * in}, new float[]{lf, y + (float) Math.sin(a1) * lip, z + (float) Math.cos(a1) * lip},
					new float[]{lf, y + (float) Math.sin(a2) * lip, z + (float) Math.cos(a2) * lip}, new float[]{lf, y + (float) Math.sin(a2) * in, z + (float) Math.cos(a2) * in}, rim);
			}
			for (int k = 0; k < spokes; k++) {
				double a = spin + Math.PI * 2 * k / spokes;
				float dy = (float) Math.sin(a), dz = (float) Math.cos(a), py = -dz * half, pz = dy * half;
				quad(new float[]{face, y + py, z + pz}, new float[]{face, y + dy * r + py, z + dz * r + pz}, new float[]{face, y + dy * r - py, z + dz * r - pz}, new float[]{face, y - py, z - pz}, rim);
			}
			box(face - 0.01f, y - radius * 0.12f, z - radius * 0.12f, face + 0.01f, y + radius * 0.12f, z + radius * 0.12f, shade(rim, 0.9f));
			body = wasBody;
		}
	}

	// ---------------------------------------------------------------- interiors

	private static final int CREAM = 0xFFEDE3CE, CREAM_DARK = 0xFFD9CCB0, WOOD = 0xFF5A3A22, PIANO = 0xFF141416, CHROME = 0xFFD6DAE0, CARPET = 0xFF2B2420;

	/**
	 * The Maybach's cabin: cream leather executive seats in the back that recline with leg rests and
	 * pillows, a console between them with a champagne cooler, a bottle and two flutes, fold-out wood
	 * tables and screens on the front seat backs, front seats, a wood and leather dashboard with a
	 * widescreen display and the wheel, wood-trimmed doors with armrests, deep carpet, and a dark
	 * headliner for the starlight (drawn with the glows).
	 */
	private void maybachInterior(Builder b) {
		// Floor
		b.box(-0.9f, 0.43f, -2.3f, 0.9f, 0.47f, 1.2f, CARPET);
		for (int side = -1; side <= 1; side += 2) {
			float x = side * 0.45f;
			// The executive seat: quilted cushion, reclined back, headrest with a pillow, a leg rest out front
			b.box(x - 0.31f, 0.47f, -1.8f, x + 0.31f, 0.72f, -0.95f, CREAM);
			for (int q = 0; q < 4; q++) b.box(x - 0.3f, 0.721f, -1.72f + q * 0.2f, x + 0.3f, 0.726f, -1.7f + q * 0.2f, CREAM_DARK);
			b.box(x - 0.31f, 0.72f, -1.97f, x + 0.31f, 1.45f, -1.78f, CREAM);
			for (int q = 0; q < 3; q++) b.box(x - 0.3f, 0.82f + q * 0.2f, -1.781f, x + 0.3f, 0.84f + q * 0.2f, -1.775f, CREAM_DARK);
			b.box(x - 0.2f, 1.45f, -1.95f, x + 0.2f, 1.66f, -1.8f, CREAM);
			b.box(x - 0.16f, 1.46f, -1.8f, x + 0.16f, 1.6f, -1.74f, 0xFFF7F3EA);
			b.box(x - 0.26f, 0.5f, -0.95f, x + 0.26f, 0.64f, -0.5f, CREAM);
			// Outer armrest
			b.box(side * 0.86f - 0.05f, 0.72f, -1.85f, side * 0.86f + 0.05f, 0.86f, -0.95f, CREAM);
			// A fold-out wood table on the back of the seat in front, and its screen
			b.box(x - 0.22f, 0.98f, -0.52f, x + 0.22f, 1.0f, -0.2f, WOOD);
			b.box(x - 0.21f, 1.0f, -0.51f, x + 0.21f, 1.003f, -0.21f, 0xFF7A5232);
			// The front seat: cushion, back with the screen facing you, headrest
			b.box(x - 0.3f, 0.47f, 0.1f, x + 0.3f, 0.72f, 0.75f, CREAM);
			b.box(x - 0.3f, 0.72f, -0.12f, x + 0.3f, 1.5f, 0.1f, CREAM);
			b.box(x - 0.18f, 1.5f, -0.1f, x + 0.18f, 1.68f, 0.06f, CREAM);
			b.box(x - 0.21f, 1.07f, -0.135f, x + 0.21f, 1.37f, -0.121f, PIANO);
			b.box(x - 0.19f, 1.09f, -0.14f, x + 0.19f, 1.35f, -0.136f, 0xFF2D5FB8);
			// The door: wood trim, an armrest, and leather above and below
			float dx = side * 0.9f;
			b.box(dx - 0.02f, 1.0f, -2.2f, dx + 0.02f, 1.06f, 1.15f, WOOD);
			b.box(side * 0.86f - 0.05f, 0.9f, -1.9f, side * 0.86f + 0.05f, 0.96f, -0.6f, CREAM_DARK);
			b.box(dx - 0.015f, 0.47f, -2.2f, dx + 0.015f, 1.0f, 1.15f, CREAM_DARK);
		}
		// The console between the back seats: piano black, a wood top, the champagne cooler, bottle and flutes
		b.box(-0.13f, 0.47f, -1.95f, 0.13f, 0.86f, -0.9f, PIANO);
		b.box(-0.13f, 0.86f, -1.95f, 0.13f, 0.875f, -0.9f, WOOD);
		b.box(-0.1f, 0.875f, -1.28f, 0.1f, 0.89f, -1.02f, CHROME);
		b.box(-0.035f, 0.89f, -1.19f, 0.035f, 1.18f, -1.11f, 0xFF123A22);
		b.box(-0.025f, 1.18f, -1.18f, 0.025f, 1.25f, -1.12f, 0xFFC9A14A);
		for (int side = -1; side <= 1; side += 2) {
			float fx = side * 0.07f, fz = -1.5f;
			b.box(fx - 0.012f, 0.875f, fz - 0.012f, fx + 0.012f, 0.88f, fz + 0.012f, CHROME);
			b.box(fx - 0.004f, 0.88f, fz - 0.004f, fx + 0.004f, 0.96f, fz + 0.004f, 0xFFE8F0F5);
			b.box(fx - 0.018f, 0.96f, fz - 0.018f, fx + 0.018f, 1.06f, fz + 0.018f, 0xFFE8D79A);
		}
		// The front: centre console with a crystal gear selector, the dashboard with a widescreen display, and the wheel
		b.box(-0.18f, 0.47f, 0.0f, 0.18f, 0.8f, 1.0f, PIANO);
		b.box(-0.17f, 0.8f, 0.0f, 0.17f, 0.815f, 1.0f, WOOD);
		b.box(-0.03f, 0.815f, 0.55f, 0.03f, 0.9f, 0.61f, 0xFFE8F0F5);
		b.box(-0.93f, 0.95f, 1.0f, 0.93f, 1.2f, 1.28f, 0xFF1A1714);
		b.box(-0.92f, 1.0f, 0.985f, 0.92f, 1.06f, 1.0f, WOOD);
		b.box(-0.85f, 1.075f, 0.975f, 0.25f, 1.2f, 0.985f, PIANO);
		b.box(-0.83f, 1.085f, 0.968f, 0.23f, 1.19f, 0.975f, 0xFF1F3A5A);
		float wx = -0.45f, wy = 1.06f, wz = 0.82f, r = 0.17f;
		for (int k = 0; k < 12; k++) {
			double a = Math.PI * 2 * k / 12;
			float px = wx + (float) Math.cos(a) * r, py = wy + (float) Math.sin(a) * r;
			b.box(px - 0.025f, py - 0.025f, wz - 0.02f, px + 0.025f, py + 0.025f, wz + 0.02f, 0xFF1A1714);
		}
		b.box(wx - 0.06f, wy - 0.06f, wz - 0.01f, wx + 0.06f, wy + 0.06f, wz + 0.03f, CHROME);
		b.box(wx - 0.03f, wy - 0.03f, wz + 0.03f, wx + 0.03f, wy + 0.03f, 1.0f, PIANO);
		// The rear shelf, and the headliner the stars shine from
		b.box(-0.9f, 1.8f, -2.2f, 0.9f, 1.84f, 0.6f, 0xFF0B0C14);
	}

	/** The Maybach's lights inside: a starlight roof that twinkles, ambient strips along the doors, and the glowing screens. */
	private void maybachGlows(Render3DEvent event, VertexConsumer glow, Builder b) {
		long ms = System.currentTimeMillis();
		java.util.Random stars = new java.util.Random(7);
		for (int i = 0; i < 70; i++) {
			float x = -0.85f + stars.nextFloat() * 1.7f, z = -2.1f + stars.nextFloat() * 2.6f;
			float twinkle = 0.55f + 0.45f * (float) Math.sin(ms / 400.0 + i * 1.7);
			int color = FxRender.fade(stars.nextInt(5) == 0 ? 0xFFBFD8FF : 0xFFFFFFFF, twinkle);
			glowAt(event, glow, b, x, 1.795f, z, 0.022f, LarpLight.glow(color));
		}
		for (int side = -1; side <= 1; side += 2) {
			for (float z = -2.1f; z < 1.1f; z += 0.22f) glowAt(event, glow, b, side * 0.87f, 0.99f, z, 0.07f, LarpLight.glow(0x70B45CFF));
			glowAt(event, glow, b, side * 0.45f, 1.22f, -0.15f, 0.16f, LarpLight.glow(0x603D7BE0));
		}
		glowAt(event, glow, b, -0.3f, 1.14f, 0.96f, 0.3f, LarpLight.glow(0x403D7BE0));
	}

	/** A glow at a point in a car's own space, riding the body's lift and pitch like the rest of it. */
	private static void glowAt(Render3DEvent event, VertexConsumer glow, Builder b, float x, float y, float z, float size, int color) {
		float yy = y + b.lift + z * b.pitch;
		float wx = b.ox - x * b.cos - z * b.sin, wz = b.oz - x * b.sin + z * b.cos;
		FxRender.billboard(event, glow, wx, b.oy + yy, wz, size, 0, color);
	}

	/**
	 * A glass canopy from a side outline: both sides, and the strips over the top for the first
	 * {@code over} edges of the outline, leaving the bottom open so you can see in and out.
	 */
	/** The Jesko's cockpit opening: from the windshield's foot back to behind the seats, inside the canopy's glass. */
	private static final float COCKPIT_FRONT = 1.05f, COCKPIT_BACK = -0.95f, COCKPIT_SIDE = 0.72f;

	/**
	 * A body piece drawn round the Jesko's cockpit instead of over it: the part ahead of it, the part
	 * behind, and a sill down each side, so no lid covers the seats and you can see out from inside.
	 */
	private static void aroundCockpit(Builder b, float[][] outline, float x1, float x2, int color) {
		float[][] front = clipZ(outline, COCKPIT_FRONT, Float.MAX_VALUE), back = clipZ(outline, -Float.MAX_VALUE, COCKPIT_BACK), middle = clipZ(outline, COCKPIT_BACK, COCKPIT_FRONT);
		if (front.length >= 3) b.prism(front, x1, x2, color);
		if (back.length >= 3) b.prism(back, x1, x2, color);
		if (middle.length >= 3) {
			b.prism(middle, x1, -COCKPIT_SIDE, color);
			b.prism(middle, COCKPIT_SIDE, x2, color);
		}
	}

	/** A side profile ({z, y} points) cut down to the part between {@code from} and {@code to} along the car. */
	private static float[][] clipZ(float[][] outline, float from, float to) {
		List<float[]> points = new ArrayList<>(List.of(outline));
		for (int pass = 0; pass < 2; pass++) {
			List<float[]> kept = new ArrayList<>();
			for (int i = 0; i < points.size(); i++) {
				float[] p = points.get(i), q = points.get((i + 1) % points.size());
				boolean pIn = pass == 0 ? p[0] >= from : p[0] <= to, qIn = pass == 0 ? q[0] >= from : q[0] <= to;
				float edge = pass == 0 ? from : to;
				if (pIn) kept.add(p);
				if (pIn != qIn) {
					float t = (edge - p[0]) / (q[0] - p[0]);
					kept.add(new float[]{edge, p[1] + (q[1] - p[1]) * t});
				}
			}
			points = kept;
			if (points.isEmpty()) break;
		}
		return points.toArray(new float[0][]);
	}

	private static void glassShell(Builder glass, float[][] outline, int over, float x1, float x2, int color) {
		for (float x : new float[]{x1, x2}) {
			for (int i = 1; i < outline.length - 1; i++) {
				float[] a = outline[0], p = outline[i], q = outline[i + 1];
				glass.quad(new float[]{x, a[1], a[0]}, new float[]{x, p[1], p[0]}, new float[]{x, q[1], q[0]}, new float[]{x, q[1], q[0]}, color);
			}
		}
		for (int i = 0; i < over && i + 1 < outline.length; i++) {
			float[] p = outline[i], q = outline[i + 1];
			glass.quad(new float[]{x1, p[1], p[0]}, new float[]{x2, p[1], p[0]}, new float[]{x2, q[1], q[0]}, new float[]{x1, q[1], q[0]}, color);
		}
	}

	/** The Jesko's cockpit, under its glass canopy: carbon bucket seats with accent stripes, the dash and its screen, and the wheel. */
	private void jeskoInterior(Builder b, int accent) {
		int alcantara = 0xFF1C1D21, carbon = 0xFF141518;
		for (int side = -1; side <= 1; side += 2) {
			float x = side * 0.36f;
			b.box(x - 0.24f, 0.36f, -0.5f, x + 0.24f, 1.02f, -0.34f, alcantara);
			b.box(x - 0.03f, 0.5f, -0.341f, x + 0.03f, 1.0f, -0.335f, accent);
			b.box(x - 0.24f, 0.3f, -0.34f, x + 0.24f, 0.42f, 0.25f, alcantara);
		}
		b.box(-0.06f, 0.3f, -0.4f, 0.06f, 0.62f, 0.5f, carbon);
		b.box(-0.7f, 0.83f, 0.45f, 0.7f, 0.9f, 0.78f, carbon);
		b.box(-0.55f, 0.88f, 0.44f, -0.15f, 0.96f, 0.455f, PIANO);
		b.box(-0.53f, 0.89f, 0.435f, -0.17f, 0.95f, 0.44f, 0xFF2D5FB8);
		float wx = -0.36f, wy = 0.86f, wz = 0.33f, r = 0.13f;
		for (int k = 0; k < 10; k++) {
			double a = Math.PI * 2 * k / 10;
			float px = wx + (float) Math.cos(a) * r, py = wy + (float) Math.sin(a) * r;
			b.box(px - 0.02f, py - 0.02f, wz - 0.015f, px + 0.02f, py + 0.02f, wz + 0.015f, alcantara);
		}
		b.box(wx - 0.015f, wy + r - 0.02f, wz - 0.02f, wx + 0.015f, wy + r + 0.025f, wz + 0.02f, accent);
		b.box(wx - 0.04f, wy - 0.04f, wz, wx + 0.04f, wy + 0.04f, 0.45f, carbon);
	}

	private static int shade(int color, float by) {
		int r = (int) (((color >> 16) & 0xFF) * by), g = (int) (((color >> 8) & 0xFF) * by), b = (int) ((color & 0xFF) * by);
		return (color & 0xFF000000) | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
	}

	// ---------------------------------------------------------------- Client Sync

	/** How cars look: colours and which extras are on. Yours come from the settings; Client Sync brings other people's. */
	private record Look(int paint, int trim, int jeskoPaint, int jeskoAccent, int glowColor, boolean hydraulics, float bounce,
						boolean underglow, boolean spinningRims, boolean headlights) {
	}

	/** Someone else's look while their cars are drawn, or null for yours. */
	private Look look;

	private Look look() {
		if (look != null) return look;
		return new Look(paint.get(), trim.get(), jeskoPaint.get(), jeskoAccent.get(), glowColor.get(), hydraulics.get(), bounce.getFloat(),
			underglow.get(), spinningRims.get(), headlights.get());
	}

	/** For Client Sync: each car as it is now (model, x, y, z, yaw, seconds since it came, seconds since it stopped), or null. */
	public List<double[]> shareState() {
		if (!isEnabled() || cars.isEmpty()) return null;
		long now = System.nanoTime();
		List<double[]> list = new ArrayList<>();
		for (Car car : cars) {
			Vec3 at = car.at(now);
			list.add(new double[]{car.model.ordinal(), at.x, at.y, at.z, car.yaw, (now - car.born) / 1e9, (now - car.arrive) / 1e9});
		}
		return list;
	}

	/** For Client Sync: your look as numbers (paint, trim, Jesko paint, Jesko accent, glow colour, bounce in thousandths, flags). */
	public int[] shareLook() {
		Look l = look();
		int flags = (l.hydraulics ? 1 : 0) | (l.underglow ? 2 : 0) | (l.spinningRims ? 4 : 0) | (l.headlights ? 8 : 0);
		return new int[]{l.paint, l.trim, l.jeskoPaint, l.jeskoAccent, l.glowColor, Math.round(l.bounce * 1000), flags};
	}

	/** Someone else's cars, from what {@link #shareState} and {@link #shareLook} gave on their end. */
	public void drawShared(Render3DEvent event, List<double[]> shared, int[] l) {
		if (shared.isEmpty()) return;
		long now = System.nanoTime();
		List<Car> shown = new ArrayList<>();
		for (double[] s : shared) {
			if (s.length < 7) continue;
			Model which = Model.values()[net.minecraft.util.Mth.clamp((int) s[0], 0, Model.values().length - 1)];
			long born = now - (long) (s[5] * 1e9), arrive = now - (long) (s[6] * 1e9);
			Vec3 at = new Vec3(s[1], s[2], s[3]);
			shown.add(new Car(which, at, (float) s[4], born, at, arrive));
		}
		Look was = look;
		float[] tiltsWere = tilts;
		tilts = new float[shown.size()];
		for (int i = 0, k = 0; i < shared.size(); i++) {
			if (shared.get(i).length < 7) continue;
			tilts[k++] = shared.get(i).length > 7 ? (float) shared.get(i)[7] : 0;
		}
		if (l.length >= 7) {
			look = new Look(l[0], l[1], l[2], l[3], l[4], (l[6] & 1) != 0, l[5] / 1000f, (l[6] & 2) != 0, (l[6] & 4) != 0, (l[6] & 8) != 0);
		}
		try {
			draw(event, shown, now);
		} finally {
			look = was;
			tilts = tiltsWere;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (cars.isEmpty()) return;
		long now = System.nanoTime();
		List<Car> shown = new ArrayList<>();
		for (Car car : cars) shown.add(frameCar(car, now));
		draw(event, shown, now);
	}

	/** Whether a car drawn this frame is the one you're riding in (drawn copies are new records). */
	private static boolean sameSpot(Car drawn, Car riding) {
		return riding != null && drawn.model == riding.model && drawn.born == riding.born;
	}

	/** For shared cars being drawn: each one's nose-up tilt, by its place in the list; null otherwise. */
	private float[] tilts;

	private void draw(Render3DEvent event, List<Car> shown, long now) {
		Vec3 cam = event.camera().pos;
		Look look = look();
		VertexConsumer solid = FxRender.solidQuads(event);
		List<Builder> glasses = new ArrayList<>();
		List<Builder> builders = new ArrayList<>();
		for (int ci = 0; ci < shown.size(); ci++) {
			Car car = shown.get(ci);
			float t = (now - car.born) / 1e9f;
			Builder b = new Builder(solid, event.matrices().last(), car, cam);
			Builder glass = new Builder(null, event.matrices().last(), car, cam);
			if (tilts != null && ci < tilts.length) b.tilt = glass.tilt = tilts[ci];
			// Hydraulics are the Maybach's thing; the Jesko sits low and still. Not while someone's riding in it
			if (look.hydraulics && car.model == Model.SUV && !sameSpot(car, riding)) {
				b.lift = glass.lift = look.bounce * Math.abs((float) Math.sin(t * 2.6f));
				b.pitch = glass.pitch = 0.04f * (float) Math.sin(t * 1.3f);
			}
			glasses.add(glass);
			builders.add(b);
			// Rolling as it drives in and still once it's stopped; spinners coast on a little, then wind down
			float rolled = (float) car.from.distanceTo(car.pos) / 0.38f;
			float stopped = Math.max(0, (now - car.arrive) / 1e9f);
			float coast = look.spinningRims ? 7 / 0.6f * (1 - (float) Math.exp(-0.6f * stopped)) : 0;
			float spin = coast - rolled;
			switch (car.model) {
				case SUV -> suv(b, glass, spin);
				case JESKO -> jesko(b, glass, spin);
			}
		}
		// The see-through glass after everything solid
		if (!glasses.isEmpty()) {
			VertexConsumer translucent = FxRender.quads(event, false, false);
			for (Builder glass : glasses) glass.flush(translucent);
		}
		// Glows go after everything solid: underglow on the ground, headlights in front, and the Maybach's starlight roof
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (int i = 0; i < shown.size(); i++) {
			if (shown.get(i).model == Model.SUV) maybachGlows(event, glow, builders.get(i));
		}
		for (Car car : shown) {
			float t = (now - car.born) / 1e9f;
			float half = car.model == Model.SUV ? 2.65f : 2.38f;
			double rad = Math.toRadians(car.yaw);
			float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), rx = (float) -Math.cos(rad), rz = (float) -Math.sin(rad);
			float cx = (float) (car.pos.x - cam.x), cy = (float) (car.pos.y - cam.y), cz = (float) (car.pos.z - cam.z);
			if (look.underglow) {
				int color = LarpLight.glow(FxRender.fade(look.glowColor, 0.7f + 0.15f * (float) Math.sin(t * 3)));
				float w = 2.2f, l = half + 1.2f, y = cy + 0.03f;
				FxRender.textured(glow, event.matrices().last(), cx - rx * w - fx * l, y, cz - rz * w - fz * l, 0, 0, color);
				FxRender.textured(glow, event.matrices().last(), cx + rx * w - fx * l, y, cz + rz * w - fz * l, 1, 0, color);
				FxRender.textured(glow, event.matrices().last(), cx + rx * w + fx * l, y, cz + rz * w + fz * l, 1, 1, color);
				FxRender.textured(glow, event.matrices().last(), cx - rx * w + fx * l, y, cz - rz * w + fz * l, 0, 1, color);
			}
			if (look.headlights) {
				float lift = look.hydraulics && car.model == Model.SUV ? look.bounce * Math.abs((float) Math.sin(t * 2.6f)) : 0;
				float lightY = cy + (car.model == Model.JESKO ? 0.46f : 0.92f) + lift;
				for (int side = -1; side <= 1; side += 2) {
					float spread = car.model == Model.JESKO ? 0.78f : 0.72f, lx = cx + fx * (half + 0.05f) + rx * side * spread, lz = cz + fz * (half + 0.05f) + rz * side * spread;
					FxRender.billboard(event, glow, lx, lightY, lz, 0.45f, 0, LarpLight.glow(0x90FFF6E0));
					FxRender.billboard(event, glow, lx, lightY, lz, 0.18f, 0, LarpLight.glow(0xF0FFFFFF));
				}
			}
		}
	}

	/**
	 * A long luxury SUV in the style of a Maybach: a tall flat-sided body, a sloped windshield and
	 * rear glass, pillars and chrome window frames, door seams and handles, mirrors, roof rails, a
	 * long chrome sill strip, a tall slatted grille, slim lights and many-spoke chrome wheels.
	 */
	private void suv(Builder b, Builder glass, float spin) {
		Look look = look();
		int p = look.paint, chrome = look.trim, seam = shade(p, 0.45f);
		// The body in three parts, so the cabin is open inside: the bonnet sloping up to the windshield base,
		// the boot behind the back seats, and the doors along the sides between them
		b.prism(new float[][]{{2.78f, 0.42f}, {2.8f, 0.78f}, {2.7f, 1.12f}, {1.3f, 1.2f}, {1.3f, 0.42f}}, -1.0f, 1.0f, p);
		b.prism(new float[][]{{-2.0f, 1.2f}, {-2.58f, 1.2f}, {-2.74f, 1.08f}, {-2.76f, 0.42f}, {-2.0f, 0.42f}}, -1.0f, 1.0f, p);
		for (int side = -1; side <= 1; side += 2) b.box(side > 0 ? 0.93f : -1.0f, 0.42f, -1.99f, side > 0 ? 1.0f : -0.93f, 1.2f, 1.29f, p);
		b.box(-0.96f, 0.3f, -2.6f, 0.96f, 0.42f, 2.62f, BLACK);
		// The glass: raked windshield, tall side windows, sloped rear glass. Just the panes, no floor to them
		float gx = 0.93f;
		for (int side = -1; side <= 1; side += 2) {
			float x = side * gx;
			glass.quad(new float[]{x, 1.2f, 1.3f}, new float[]{x, 1.86f, 0.5f}, new float[]{x, 1.88f, -2.28f}, new float[]{x, 1.2f, -2.58f}, SUV_GLASS);
		}
		glass.quad(new float[]{-gx, 1.2f, 1.3f}, new float[]{gx, 1.2f, 1.3f}, new float[]{gx, 1.86f, 0.5f}, new float[]{-gx, 1.86f, 0.5f}, SUV_GLASS);
		glass.quad(new float[]{-gx, 1.88f, -2.28f}, new float[]{gx, 1.88f, -2.28f}, new float[]{gx, 1.2f, -2.58f}, new float[]{-gx, 1.2f, -2.58f}, SUV_GLASS);
		maybachInterior(b);
		b.box(-0.96f, 1.86f, -2.32f, 0.96f, 1.94f, 0.55f, p);
		for (int side = -1; side <= 1; side += 2) {
			float sx = side * 0.938f, cx = side * 0.945f;
			// Pillars: A along the windshield, B and C upright, D along the rear glass
			b.strip(sx, 1.3f, 1.2f, 0.5f, 1.86f, 0.09f, p);
			b.box(sx - 0.012f, 1.2f, -0.62f, sx + 0.012f, 1.87f, -0.5f, p);
			b.box(sx - 0.012f, 1.2f, -1.8f, sx + 0.012f, 1.87f, -1.66f, p);
			b.strip(sx, -2.28f, 1.88f, -2.58f, 1.2f, 0.12f, p);
			// Chrome round the windows
			b.strip(cx, 1.28f, 1.21f, -2.55f, 1.21f, 0.025f, chrome);
			b.strip(cx, 0.52f, 1.84f, -2.26f, 1.86f, 0.025f, chrome);
			b.strip(cx, 1.28f, 1.21f, 0.52f, 1.84f, 0.025f, chrome);
			// Door seams, handles, the long chrome sill and a lower chrome line
			float ox = side * 1.004f;
			for (float z : new float[]{1.18f, -0.56f, -1.73f}) b.box(ox - 0.004f, 0.5f, z - 0.012f, ox + 0.004f, 1.19f, z + 0.012f, seam);
			b.box(ox - 0.01f, 1.0f, 0.12f, ox + 0.01f, 1.04f, 0.36f, chrome);
			b.box(ox - 0.01f, 1.0f, -1.12f, ox + 0.01f, 1.04f, -0.88f, chrome);
			b.box(side * 1.0f - 0.03f, 0.33f, -1.5f, side * 1.0f + 0.03f, 0.39f, 1.42f, chrome);
			b.box(ox - 0.006f, 0.6f, -2.45f, ox + 0.006f, 0.63f, 2.5f, chrome);
			// Mirror on a stalk
			b.box(side * 1.0f, 1.24f, 1.08f, side * 1.2f, 1.36f, 1.2f, p);
			// Roof rails
			b.box(side * 0.86f - 0.03f, 1.94f, -2.15f, side * 0.86f + 0.03f, 1.97f, 0.4f, chrome);
		}
		// Grille: a chrome surround with upright slats, and slim lights each side
		b.box(-0.4f, 0.52f, 2.72f, 0.4f, 1.12f, 2.77f, chrome);
		b.box(-0.34f, 0.57f, 2.76f, 0.34f, 1.07f, 2.775f, shade(chrome, 0.35f));
		for (int i = -6; i <= 6; i++) b.box(i * 0.052f - 0.008f, 0.57f, 2.77f, i * 0.052f + 0.008f, 1.07f, 2.785f, chrome);
		for (int side = -1; side <= 1; side += 2) {
			b.box(side * 0.68f - 0.24f, 0.93f, 2.69f, side * 0.68f + 0.24f, 1.02f, 2.75f, HEADLIGHT);
			b.box(side * 0.68f - 0.22f, 0.9f, 2.72f, side * 0.68f + 0.22f, 0.915f, 2.76f, chrome);
			b.box(side * 0.82f - 0.13f, 0.78f, -2.79f, side * 0.82f + 0.13f, 1.1f, -2.73f, TAILLIGHT);
		}
		// Bumpers with a chrome line
		b.box(-0.98f, 0.42f, 2.76f, 0.98f, 0.52f, 2.84f, p);
		b.box(-0.9f, 0.48f, 2.83f, 0.9f, 0.5f, 2.85f, chrome);
		b.box(-0.98f, 0.42f, -2.82f, 0.98f, 0.56f, -2.74f, p);
		for (int side = -1; side <= 1; side += 2) {
			b.wheel(side * 0.9f, 0.45f, 1.78f, 0.45f, 0.32f, spin, chrome);
			b.wheel(side * 0.9f, 0.45f, -1.78f, 0.45f, 0.32f, spin, chrome);
		}
	}

	/**
	 * A Jesko-style hypercar, its side profile measured off a photo of the real car (4.6 blocks long):
	 * a low nose rising in one line to the windshield, the roof cresting over the doors, a long rear
	 * deck to a high tail, fender crests over the front wheels and haunches over the back, a big side
	 * intake, a sculpted door, a low swan-neck wing, a mesh tail with an oval exhaust and a finned
	 * diffuser, lime accents and Y-spoke wheels with lime calipers.
	 */
	private void jesko(Builder b, Builder glass, float spin) {
		Look look = look();
		int p = look.jeskoPaint, accent = look.jeskoAccent, carbon = 0xFF141518, seam = shade(p, 0.55f), rims = 0xFF1C1D21;
		int shadow = shade(p, 0.78f);
		float frontWheel = 1.4f, rearWheel = -1.5f;
		// Carbon tub along the bottom
		b.box(-0.98f, 0.15f, -2.3f, 0.98f, 0.3f, 2.3f, carbon);
		// The body side on: nose, the long bonnet line, under the canopy, the deck and the tail
		float[][] body = {{2.34f, 0.18f}, {2.42f, 0.3f}, {2.34f, 0.42f}, {2.05f, 0.54f}, {1.6f, 0.66f}, {1.05f, 0.8f}, {0.5f, 0.84f}, {-0.6f, 0.86f},
			{-1.3f, 0.9f}, {-2.05f, 0.88f}, {-2.3f, 0.78f}, {-2.36f, 0.5f}, {-2.3f, 0.18f}};
		// Each is left open over the cockpit (between the canopy's ends, inside its glass) so you can sit in it and see out
		aroundCockpit(b, body, -0.92f, 0.92f, p);
		// The full-width lower body, and a chamfer stepping in to the shoulders
		aroundCockpit(b, new float[][]{{2.38f, 0.2f}, {2.44f, 0.32f}, {2.3f, 0.45f}, {2.05f, 0.5f}, {-2.3f, 0.5f}, {-2.38f, 0.4f}, {-2.32f, 0.2f}}, -1.0f, 1.0f, p);
		float[][] chamfer = new float[body.length][];
		for (int i = 0; i < body.length; i++) chamfer[i] = new float[]{body[i][0], Math.max(0.5f, Math.min(body[i][1], 0.5f + (body[i][1] - 0.5f) * 0.7f))};
		aroundCockpit(b, chamfer, -0.975f, 0.975f, p);
		for (int side = -1; side <= 1; side += 2) {
			// Front fender cresting over the wheel, with a softer crown. Its back sweeps down into the
			// door rather than ending in a wall, and it sits just proud of the body instead of level
			// with it (two faces in the same place flicker)
			b.prism(new float[][]{{2.2f, 0.48f}, {2.0f, 0.62f}, {1.7f, 0.72f}, {1.35f, 0.78f}, {1.05f, 0.79f}, {0.8f, 0.7f}, {0.62f, 0.52f}, {0.62f, 0.48f}}, side > 0 ? 0.6f : -1.012f, side > 0 ? 1.012f : -0.6f, p);
			b.prism(new float[][]{{1.95f, 0.66f}, {1.7f, 0.745f}, {1.35f, 0.8f}, {1.08f, 0.81f}, {0.9f, 0.76f}, {0.9f, 0.72f}, {1.95f, 0.6f}}, side > 0 ? 0.66f : -0.93f, side > 0 ? 0.93f : -0.66f, p);
			// Rear haunch swelling over the back wheel and running out to the tail; its front rakes back
			// from the door to the intake instead of standing up as a wall
			b.prism(new float[][]{{-0.5f, 0.8f}, {-0.95f, 0.92f}, {-1.45f, 0.97f}, {-1.95f, 0.95f}, {-2.25f, 0.84f}, {-2.34f, 0.62f}, {-2.3f, 0.5f}, {-1.3f, 0.5f}}, side > 0 ? 0.55f : -1.04f, side > 0 ? 1.04f : -0.55f, p);
			b.prism(new float[][]{{-0.95f, 0.95f}, {-1.45f, 0.995f}, {-1.95f, 0.975f}, {-1.95f, 0.9f}, {-0.95f, 0.88f}}, side > 0 ? 0.6f : -0.99f, side > 0 ? 0.99f : -0.6f, p);
			// Dark trim round each arch, clear of the panels under it
			b.arch(side * 1.022f, 0.36f, frontWheel, 0.4f, 0.45f, carbon);
			b.arch(side * 1.052f, 0.38f, rearWheel, 0.42f, 0.47f, carbon);
		}
		// Bonnet: ridges either side of a slatted vent, following its slope
		for (int side = -1; side <= 1; side += 2) b.prism(new float[][]{{2.0f, 0.56f}, {1.1f, 0.785f}, {1.1f, 0.805f}, {2.0f, 0.58f}}, side * 0.33f - 0.035f, side * 0.33f + 0.035f, shade(p, 1.05f));
		b.prism(new float[][]{{1.85f, 0.6f}, {1.35f, 0.725f}, {1.35f, 0.745f}, {1.85f, 0.62f}}, -0.25f, 0.25f, carbon);
		for (int i = 0; i < 4; i++) {
			float z = 1.42f + i * 0.11f, y = 0.725f - (z - 1.35f) * 0.25f;
			b.box(-0.23f, y + 0.02f, z, 0.23f, y + 0.03f, z + 0.03f, shade(p, 0.9f));
		}
		// Nose: slatted centre intake, side intakes, two-part headlights, canards and the splitter
		b.box(-0.42f, 0.2f, 2.38f, 0.42f, 0.36f, 2.43f, BLACK);
		for (int i = 0; i < 3; i++) b.box(-0.4f, 0.22f + i * 0.045f, 2.43f, 0.4f, 0.235f + i * 0.045f, 2.44f, carbon);
		for (int side = -1; side <= 1; side += 2) {
			b.box(side * 0.73f - 0.22f, 0.21f, 2.32f, side * 0.73f + 0.22f, 0.37f, 2.4f, BLACK);
			b.box(side * 0.73f - 0.2f, 0.21f, 2.4f, side * 0.73f + 0.2f, 0.22f, 2.41f, accent);
			b.box(side * 0.72f - 0.21f, 0.445f, 2.2f, side * 0.72f + 0.21f, 0.475f, 2.31f, HEADLIGHT);
			b.box(side * 0.72f - 0.17f, 0.395f, 2.25f, side * 0.72f + 0.17f, 0.44f, 2.33f, 0xFF3A3D44);
			for (int k = 0; k < 3; k++) b.box(side * 0.72f - 0.13f + k * 0.11f - 0.025f, 0.405f, 2.32f, side * 0.72f - 0.13f + k * 0.11f + 0.025f, 0.43f, 2.335f, HEADLIGHT);
			b.box(side * 0.95f - 0.09f, 0.26f, 2.16f, side * 0.95f + 0.09f, 0.28f, 2.4f, carbon);
			b.box(side * 0.95f - 0.09f, 0.28f, 2.36f, side * 0.95f + 0.09f, 0.29f, 2.4f, accent);
		}
		b.box(-1.02f, 0.13f, 2.12f, 1.02f, 0.17f, 2.54f, carbon);
		b.box(-1.0f, 0.17f, 2.51f, 1.0f, 0.18f, 2.54f, accent);
		// The canopy: windshield rising from the bonnet line, cresting over the doors, sloping to the deck
		glassShell(glass, new float[][]{{1.05f, 0.8f}, {0.75f, 0.95f}, {0.45f, 1.06f}, {0.0f, 1.12f}, {-0.5f, 1.11f}, {-0.95f, 1.03f}, {-1.3f, 0.92f}, {-1.3f, 0.86f}, {0.5f, 0.84f}}, 7, -0.72f, 0.72f, JESKO_GLASS);
		jeskoInterior(b, accent);
		b.prism(new float[][]{{0.45f, 1.06f}, {0.0f, 1.125f}, {-0.5f, 1.115f}, {-0.8f, 1.07f}, {-0.8f, 1.04f}, {-0.5f, 1.085f}, {0.0f, 1.095f}, {0.45f, 1.035f}}, -0.56f, 0.56f, p);
		// Engine cover behind the cabin: louvres and a spine
		b.prism(new float[][]{{-1.0f, 1.0f}, {-1.6f, 0.95f}, {-2.1f, 0.9f}, {-2.1f, 0.84f}, {-1.0f, 0.86f}}, -0.45f, 0.45f, p);
		for (int i = 0; i < 4; i++) b.box(-0.32f, 0.955f - i * 0.012f, -1.25f - i * 0.18f, 0.32f, 0.965f - i * 0.012f, -1.17f - i * 0.18f, carbon);
		b.box(-0.02f, 0.9f, -2.05f, 0.02f, 1.0f, -1.2f, carbon);
		for (int side = -1; side <= 1; side += 2) {
			float ox = side * 1.004f;
			// Door: front and back seams, and the scallop sweeping from the front wheel up into the intake
			b.box(ox - 0.004f, 0.3f, 0.88f, ox + 0.004f, 0.62f, 0.92f, seam);
			b.box(ox - 0.004f, 0.32f, -0.5f, ox + 0.004f, 0.72f, -0.46f, seam);
			b.strip(side * 1.008f, 0.6f, 0.3f, -0.5f, 0.44f, 0.1f, shadow);
			b.strip(side * 1.015f, 0.6f, 0.25f, -0.5f, 0.39f, 0.025f, seam);
			// The big side intake in front of the rear wheel, with a mesh and slats
			b.box(side * 1.02f - 0.03f, 0.24f, -1.25f, side * 1.02f + 0.03f, 0.72f, -0.55f, carbon);
			b.box(side * 1.04f - 0.02f, 0.28f, -1.2f, side * 1.04f + 0.02f, 0.68f, -0.6f, BLACK);
			for (int k = 0; k < 4; k++) b.box(side * 1.05f - 0.012f, 0.33f + k * 0.09f, -1.18f, side * 1.05f + 0.012f, 0.345f + k * 0.09f, -0.62f, 0xFF26272B);
			b.box(side * 1.0f - 0.02f, 0.13f, -1.15f, side * 1.0f + 0.02f, 0.16f, 1.0f, accent);
			// Mirror on a thin arm off the door top
			b.box(side * 0.72f, 0.82f, 0.62f, side * 0.92f, 0.84f, 0.65f, carbon);
			b.box(side * 0.92f - 0.04f, 0.8f, 0.54f, side * 0.92f + 0.06f, 0.9f, 0.68f, carbon);
			// Swan-neck wing mount: up off the deck and hooking back over the wing
			float mx = side * 0.38f;
			b.prism(new float[][]{{-1.68f, 0.92f}, {-1.76f, 0.92f}, {-2.05f, 1.2f}, {-1.97f, 1.2f}}, mx - 0.025f, mx + 0.025f, carbon);
			// End plates
			b.prism(new float[][]{{-1.9f, 1.05f}, {-2.4f, 1.05f}, {-2.46f, 1.23f}, {-2.02f, 1.2f}}, side * 0.93f - 0.015f, side * 0.93f + 0.015f, carbon);
			// Vents on the haunch tops
			for (int k = 0; k < 3; k++) b.box(side * 0.78f - 0.14f, 0.935f, -2.02f + k * 0.07f, side * 0.78f + 0.14f, 0.945f, -1.99f + k * 0.07f, carbon);
		}
		// The wing, low over the tail, a little nose-up
		b.prism(new float[][]{{-1.88f, 1.15f}, {-2.1f, 1.2f}, {-2.42f, 1.2f}, {-2.41f, 1.16f}, {-2.08f, 1.13f}}, -0.93f, 0.93f, carbon);
		// The back: black fascia with a mesh, oval exhaust, plate, and C-shaped tail lights
		b.box(-0.88f, 0.26f, -2.38f, 0.88f, 0.66f, -2.35f, BLACK);
		for (int row = 0; row < 4; row++) {
			for (int col = -6; col <= 6; col++) {
				if (Math.abs(col) <= 2 && row >= 2) continue;
				float mx = col * 0.12f + (row % 2) * 0.06f, my = 0.3f + row * 0.08f;
				b.box(mx - 0.03f, my - 0.025f, -2.385f, mx + 0.03f, my + 0.025f, -2.38f, 0xFF26272B);
			}
		}
		b.box(-0.2f, 0.52f, -2.4f, 0.2f, 0.64f, -2.36f, 0xFFC8CCD2);
		b.box(-0.24f, 0.54f, -2.4f, 0.24f, 0.62f, -2.36f, 0xFFC8CCD2);
		b.box(-0.17f, 0.545f, -2.41f, 0.17f, 0.615f, -2.4f, BLACK);
		b.box(-0.22f, 0.37f, -2.395f, 0.22f, 0.46f, -2.385f, 0xFFE8E8E8);
		b.box(-0.2f, 0.385f, -2.4f, 0.2f, 0.445f, -2.395f, 0xFF30323A);
		for (int side = -1; side <= 1; side += 2) {
			b.box(side * 0.7f - 0.28f, 0.7f, -2.37f, side * 0.7f + 0.28f, 0.73f, -2.33f, TAILLIGHT);
			b.box(side * 0.96f - 0.025f, 0.56f, -2.36f, side * 0.96f + 0.025f, 0.73f, -2.32f, TAILLIGHT);
			b.box(side * 0.82f - 0.14f, 0.56f, -2.36f, side * 0.82f + 0.14f, 0.585f, -2.33f, TAILLIGHT);
		}
		// Diffuser of tall fins, each tipped lime
		b.box(-0.95f, 0.14f, -2.55f, 0.95f, 0.18f, -2.2f, carbon);
		for (int i = -4; i <= 4; i++) {
			float fx = i * 0.2f;
			b.prism(new float[][]{{-2.2f, 0.18f}, {-2.55f, 0.18f}, {-2.55f, 0.23f}, {-2.2f, 0.4f}}, fx - 0.014f, fx + 0.014f, carbon);
			b.box(fx - 0.015f, 0.18f, -2.56f, fx + 0.015f, 0.23f, -2.54f, accent);
		}
		for (int side = -1; side <= 1; side += 2) {
			b.wheel(side * 0.93f, 0.36f, frontWheel, 0.36f, 0.3f, spin, rims, 10, 0.045f, accent);
			b.wheel(side * 0.96f, 0.38f, rearWheel, 0.38f, 0.38f, spin, rims, 10, 0.045f, accent);
		}
	}
}
