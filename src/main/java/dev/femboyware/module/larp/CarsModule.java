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
	private static final int GLASS = 0xFF13171D, TIRE = 0xFF141416, BLACK = 0xFF0A0A0B, HEADLIGHT = 0xFFF4F6FF, TAILLIGHT = 0xFFC2121F;

	/**
	 * Draws one SUV bouncing on its hydraulics at {@code pos}, for the Maybach Bounce emote; works
	 * whether or not this module is on.
	 */
	public void drawShowCar(Render3DEvent event, Vec3 pos, float yaw, float seconds) {
		Car car = new Car(Model.SUV, pos, yaw, 0);
		Builder b = new Builder(FxRender.solidQuads(event), event.matrices().last(), car, event.camera().pos);
		b.lift = showLift(seconds);
		b.pitch = 0.05f * (float) Math.sin(seconds * 1.3f);
		// Parked: the wheels sit still while the body bounces
		suv(b, 0);
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
		return carShapes(cars, area);
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
				ChatUtil.info("Parked a " + which.name().toLowerCase(Locale.ROOT) + ". Only you can see it");
			}
			case "remove", "undo" -> {
				if (!cars.isEmpty()) cars.removeLast();
				ChatUtil.info(cars.size() + " cars left");
			}
			case "clear" -> {
				cars.clear();
				removeValets();
				ChatUtil.info("Cleared your cars");
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "car <spawn [suv|jesko]|remove|clear>");
		}
	}

	@Override
	public void onTick() {
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
		removeValets();
		cars.clear();
	}

	// ---------------------------------------------------------------- drawing

	/** Builds a car's parts in its own space: x across (right), y up, z forward. Units are blocks. */
	private final class Builder {
		final VertexConsumer c;
		final PoseStack.Pose pose;
		final float cos, sin, ox, oy, oz;
		/** The body's lift and nose-up pitch from the hydraulics; wheels don't get them. */
		float lift, pitch;
		boolean body = true;
		/** How much taller than built the body is drawn; wheels and their arch trims aren't stretched. */
		float stretch = 1;

		Builder(VertexConsumer c, PoseStack.Pose pose, Car car, Vec3 cam) {
			this.c = c;
			this.pose = pose;
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
			// Facing yaw: forward is (-sin, cos); right is (-cos, -sin)
			float wx = ox - x * cos - z * sin, wz = oz - x * sin + z * cos;
			FxRender.vertex(c, pose, wx, oy + y, wz, color);
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
		if (l.length >= 7) {
			look = new Look(l[0], l[1], l[2], l[3], l[4], (l[6] & 1) != 0, l[5] / 1000f, (l[6] & 2) != 0, (l[6] & 4) != 0, (l[6] & 8) != 0);
		}
		try {
			draw(event, shown, now);
		} finally {
			look = was;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (cars.isEmpty()) return;
		long now = System.nanoTime();
		List<Car> shown = new ArrayList<>();
		for (Car car : cars) shown.add(new Car(car.model, car.at(now), car.yaw, car.born, car.from, car.arrive));
		draw(event, shown, now);
	}

	private void draw(Render3DEvent event, List<Car> shown, long now) {
		Vec3 cam = event.camera().pos;
		Look look = look();
		VertexConsumer solid = FxRender.solidQuads(event);
		for (Car car : shown) {
			float t = (now - car.born) / 1e9f;
			Builder b = new Builder(solid, event.matrices().last(), car, cam);
			// Hydraulics are the Maybach's thing; the Jesko sits low and still
			if (look.hydraulics && car.model == Model.SUV) {
				b.lift = look.bounce * Math.abs((float) Math.sin(t * 2.6f));
				b.pitch = 0.04f * (float) Math.sin(t * 1.3f);
			}
			// Rolling as it drives in and still once it's stopped; spinners coast on a little, then wind down
			float rolled = (float) car.from.distanceTo(car.pos) / 0.38f;
			float stopped = Math.max(0, (now - car.arrive) / 1e9f);
			float coast = look.spinningRims ? 7 / 0.6f * (1 - (float) Math.exp(-0.6f * stopped)) : 0;
			float spin = coast - rolled;
			switch (car.model) {
				case SUV -> suv(b, spin);
				case JESKO -> jesko(b, spin);
			}
		}
		// Glows go after everything solid: underglow on the ground, headlights in front
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (Car car : shown) {
			float t = (now - car.born) / 1e9f;
			float half = car.model == Model.SUV ? 2.65f : 2.38f;
			double rad = Math.toRadians(car.yaw);
			float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), rx = (float) -Math.cos(rad), rz = (float) -Math.sin(rad);
			float cx = (float) (car.pos.x - cam.x), cy = (float) (car.pos.y - cam.y), cz = (float) (car.pos.z - cam.z);
			if (look.underglow) {
				int color = FxRender.fade(look.glowColor, 0.7f + 0.15f * (float) Math.sin(t * 3));
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
					FxRender.billboard(event, glow, lx, lightY, lz, 0.45f, 0, 0x90FFF6E0);
					FxRender.billboard(event, glow, lx, lightY, lz, 0.18f, 0, 0xF0FFFFFF);
				}
			}
		}
	}

	/**
	 * A long luxury SUV in the style of a Maybach: a tall flat-sided body, a sloped windshield and
	 * rear glass, pillars and chrome window frames, door seams and handles, mirrors, roof rails, a
	 * long chrome sill strip, a tall slatted grille, slim lights and many-spoke chrome wheels.
	 */
	private void suv(Builder b, float spin) {
		Look look = look();
		int p = look.paint, chrome = look.trim, seam = shade(p, 0.45f);
		// The body, side on: front bumper, bonnet sloping up to the windshield base, flat to the back
		b.prism(new float[][]{{2.78f, 0.42f}, {2.8f, 0.78f}, {2.7f, 1.12f}, {1.3f, 1.2f}, {-2.58f, 1.2f}, {-2.74f, 1.08f}, {-2.76f, 0.42f}}, -1.0f, 1.0f, p);
		b.box(-0.96f, 0.3f, -2.6f, 0.96f, 0.42f, 2.62f, BLACK);
		// The glasshouse: raked windshield, tall side windows, sloped rear glass
		b.prism(new float[][]{{1.3f, 1.2f}, {0.5f, 1.86f}, {-2.28f, 1.88f}, {-2.58f, 1.2f}}, -0.93f, 0.93f, GLASS);
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
	private void jesko(Builder b, float spin) {
		Look look = look();
		int p = look.jeskoPaint, accent = look.jeskoAccent, carbon = 0xFF141518, seam = shade(p, 0.55f), rims = 0xFF1C1D21;
		int shadow = shade(p, 0.78f);
		float frontWheel = 1.4f, rearWheel = -1.5f;
		// Carbon tub along the bottom
		b.box(-0.98f, 0.15f, -2.3f, 0.98f, 0.3f, 2.3f, carbon);
		// The body side on: nose, the long bonnet line, under the canopy, the deck and the tail
		float[][] body = {{2.34f, 0.18f}, {2.42f, 0.3f}, {2.34f, 0.42f}, {2.05f, 0.54f}, {1.6f, 0.66f}, {1.05f, 0.8f}, {0.5f, 0.84f}, {-0.6f, 0.86f},
			{-1.3f, 0.9f}, {-2.05f, 0.88f}, {-2.3f, 0.78f}, {-2.36f, 0.5f}, {-2.3f, 0.18f}};
		b.prism(body, -0.92f, 0.92f, p);
		// The full-width lower body, and a chamfer stepping in to the shoulders
		b.prism(new float[][]{{2.38f, 0.2f}, {2.44f, 0.32f}, {2.3f, 0.45f}, {2.05f, 0.5f}, {-2.3f, 0.5f}, {-2.38f, 0.4f}, {-2.32f, 0.2f}}, -1.0f, 1.0f, p);
		float[][] chamfer = new float[body.length][];
		for (int i = 0; i < body.length; i++) chamfer[i] = new float[]{body[i][0], Math.max(0.5f, Math.min(body[i][1], 0.5f + (body[i][1] - 0.5f) * 0.7f))};
		b.prism(chamfer, -0.975f, 0.975f, p);
		for (int side = -1; side <= 1; side += 2) {
			// Front fender cresting over the wheel, with a softer crown
			b.prism(new float[][]{{2.2f, 0.48f}, {2.0f, 0.62f}, {1.7f, 0.72f}, {1.35f, 0.78f}, {1.05f, 0.79f}, {0.9f, 0.74f}, {0.9f, 0.48f}}, side > 0 ? 0.6f : -1.0f, side > 0 ? 1.0f : -0.6f, p);
			b.prism(new float[][]{{1.95f, 0.66f}, {1.7f, 0.745f}, {1.35f, 0.8f}, {1.08f, 0.81f}, {1.08f, 0.72f}, {1.95f, 0.6f}}, side > 0 ? 0.66f : -0.93f, side > 0 ? 0.93f : -0.66f, p);
			// Rear haunch swelling over the back wheel and running out to the tail
			b.prism(new float[][]{{-0.55f, 0.82f}, {-0.95f, 0.92f}, {-1.45f, 0.97f}, {-1.95f, 0.95f}, {-2.25f, 0.84f}, {-2.34f, 0.62f}, {-0.55f, 0.55f}}, side > 0 ? 0.55f : -1.05f, side > 0 ? 1.05f : -0.55f, p);
			b.prism(new float[][]{{-0.95f, 0.95f}, {-1.45f, 0.995f}, {-1.95f, 0.975f}, {-1.95f, 0.9f}, {-0.95f, 0.88f}}, side > 0 ? 0.6f : -0.99f, side > 0 ? 0.99f : -0.6f, p);
			// Dark trim round each arch
			b.arch(side * 1.003f, 0.36f, frontWheel, 0.4f, 0.45f, carbon);
			b.arch(side * 1.053f, 0.38f, rearWheel, 0.42f, 0.47f, carbon);
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
		b.prism(new float[][]{{1.05f, 0.8f}, {0.75f, 0.95f}, {0.45f, 1.06f}, {0.0f, 1.12f}, {-0.5f, 1.11f}, {-0.95f, 1.03f}, {-1.3f, 0.92f}, {-1.3f, 0.86f}, {0.5f, 0.84f}}, -0.72f, 0.72f, GLASS);
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
			b.strip(side * 1.006f, 0.85f, 0.34f, -0.5f, 0.56f, 0.1f, shadow);
			b.strip(side * 1.007f, 0.85f, 0.29f, -0.5f, 0.51f, 0.025f, seam);
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
