package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.ServerLarpModule;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Your own private hangar: a huge arched building with its doors slid open, a polished floor with
 * taxi lines, and lights all along the roof. Spawning it parks the private jet inside facing out
 * of the doors, the cars along the back, and the helicopter on a pad out front. Its walls are
 * solid while Server Larp is on.
 */
public class HangarModule extends Module {
	private final ColorSetting walls = add(new ColorSetting("Walls", "The hangar's cladding", 0xFFE4E6EA));
	private final ColorSetting trim = add(new ColorSetting("Trim", "The band round its walls", 0xFFC9A14A));

	/** Half its width and depth, how high the walls go and how much higher the middle of the roof. Its own space: x across, z out the doors. */
	private static final float HALF_WIDTH = 22, HALF_DEPTH = 21, WALL = 10, ARCH = 6, DOOR = 12.5f;

	private Vec3 at;
	private float yaw;
	/** The cars it parked, to take away with it. */
	private final List<Object> parkedCars = new ArrayList<>();

	public HangarModule() {
		super("Hangar", "A private hangar with your jet and cars parked inside. Use " + CommandManager.PREFIX + "hangar", Category.LARP);
	}

	@Override
	public String getInfo() {
		return at != null ? "Built" : null;
	}

	/** Handles {@code .hangar [spawn|remove]}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "spawn";
		switch (action) {
			case "spawn", "build", "park" -> {
				if (mc.player == null) return;
				// Ahead of you with its doors toward you, squared to a compass direction like the jet
				float facing = Math.round(mc.player.getYRot() / 90f) * 90f;
				double rad = Math.toRadians(facing);
				at = mc.player.position().add(-Math.sin(rad) * (HALF_DEPTH + 9), 0, Math.cos(rad) * (HALF_DEPTH + 9));
				yaw = facing + 180;
				if (!isEnabled()) setEnabled(true);
				fill();
			}
			case "remove", "clear" -> {
				clearAll();
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "hangar <spawn|remove>");
		}
	}

	/** For LarpSave: where it stands (x, y, z, yaw), or null. */
	public double[] savePlace() {
		return at == null ? null : new double[]{at.x, at.y, at.z, yaw};
	}

	/** For LarpSave: puts it back where it was saved (or takes it away), quietly. */
	public void restorePlace(double[] p) {
		if (p == null || p.length < 4) {
			at = null;
			parkedCars.clear();
			return;
		}
		at = new Vec3(p[0], p[1], p[2]);
		yaw = (float) p[3];
		parkedCars.clear();
		if (!isEnabled()) setEnabled(true);
	}

	/** For LarpSave: whether the hangar parked this car (so it goes when the hangar does). */
	public boolean parked(Object car) {
		return parkedCars.contains(car);
	}

	/** For LarpSave: a car put back that the hangar had parked. */
	public void adopt(Object car) {
		parkedCars.add(car);
	}

	/** Takes the hangar away with everything it parked: the jet, the helicopter and its two cars. */
	private void clearAll() {
		at = null;
		FemboyWare.getModules().get(JetModule.class).clear();
		FemboyWare.getModules().get(HelicopterModule.class).clear();
		CarsModule cars = FemboyWare.getModules().get(CarsModule.class);
		for (Object car : parkedCars) cars.remove(car);
		parkedCars.clear();
	}

	@Override
	protected void onDisable() {
		if (at != null) clearAll();
	}

	/** A point in the hangar's own space in the world. */
	private Vec3 world(float x, float y, float z) {
		Vector3f v = new Vector3f(x, y, z).rotate(turn());
		return at.add(v.x, v.y, v.z);
	}

	private Quaternionf turn() {
		return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
	}

	/** Parks everything: the jet in the middle facing out, a car either side at the back, the helicopter on a pad outside. */
	private void fill() {
		FemboyWare.getModules().get(JetModule.class).parkAt(world(0, 0, -1), yaw);
		CarsModule cars = FemboyWare.getModules().get(CarsModule.class);
		for (Object car : parkedCars) cars.remove(car);
		parkedCars.clear();
		parkedCars.add(cars.parkAt(CarsModule.Model.SUV, world(16.5f, 0, -14), yaw));
		parkedCars.add(cars.parkAt(CarsModule.Model.JESKO, world(-16.5f, 0, -14), yaw));
		FemboyWare.getModules().get(HelicopterModule.class).parkAt(world(-34, 0, HALF_DEPTH + 10), yaw);
	}

	// ---------------------------------------------------------------- collision

	/** Called from EntityMixin: the floor, walls and doors are solid for you while Server Larp is on (the server can't see them). */
	public List<VoxelShape> collisions(Entity entity, AABB area) {
		if (!isEnabled() || at == null || !LarpProps.solidFor(entity)) return new ArrayList<>();
		return shapes(entity, area);
	}

	/** Someone else's hangar as solid walls and floor, from what {@link #shareState} gave on their end. */
	public List<VoxelShape> sharedCollisions(Entity entity, AABB area, double[] s) {
		Vec3 wasAt = at;
		float wasYaw = yaw;
		at = new Vec3(s[0], s[1], s[2]);
		yaw = (float) s[3];
		try {
			return shapes(entity, area);
		} finally {
			at = wasAt;
			yaw = wasYaw;
		}
	}

	private List<VoxelShape> shapes(Entity entity, AABB area) {
		List<VoxelShape> shapes = new ArrayList<>();
		if (at.distanceToSqr(entity.position()) > 60 * 60) return shapes;
		float w = HALF_WIDTH, d = HALF_DEPTH;
		float[][] boxes = {
			// The floor and the apron out the front, a step up from the ground
			{-w, 0, -d, w, 0.05f, d + 6},
			// Side and back walls, and the parts of the walls by the doorway up to the gable
			{-w - 0.3f, 0, -d, -w + 0.2f, DOOR, d},
			{w - 0.2f, 0, -d, w + 0.3f, DOOR, d},
			{-w, 0, -d - 0.3f, w, WALL + ARCH, -d + 0.2f},
			// The gable over the doorway
			{-w, DOOR - 0.6f, d - 0.15f, w, WALL + ARCH, d + 0.15f},
			// The doors, slid open past each side
			{-w - 11, 0, d + 0.3f, -w, DOOR - 0.3f, d + 0.8f},
			{w, 0, d + 0.3f, w + 11, DOOR - 0.3f, d + 0.8f}};
		Quaternionf turn = turn();
		for (float[] b : boxes) {
			Vector3f p = new Vector3f(b[0], b[1], b[2]).rotate(turn), q = new Vector3f(b[3], b[4], b[5]).rotate(turn);
			AABB box = new AABB(at.x + p.x, at.y + p.y, at.z + p.z, at.x + q.x, at.y + q.y, at.z + q.z);
			if (box.intersects(area)) shapes.add(Shapes.create(box));
		}
		return shapes;
	}

	// ---------------------------------------------------------------- drawing

	/** The roof's height across the hangar: walls up to {@link #WALL}, arching up to the middle. */
	private static float roof(float x) {
		float u = x / HALF_WIDTH;
		return WALL + ARCH * (1 - u * u);
	}

	// ---------------------------------------------------------------- Client Sync

	/** Someone else's colours (walls, trim) while their hangar's drawn, or null for yours. */
	private int[] look;

	/** For Client Sync: where your hangar is (x, y, z, yaw), or null. */
	public double[] shareState() {
		return isEnabled() && at != null ? new double[]{at.x, at.y, at.z, yaw} : null;
	}

	public int[] shareLook() {
		return new int[]{walls.get(), trim.get()};
	}

	/** Someone else's hangar, from what {@link #shareState} and {@link #shareLook} gave on their end. */
	public void drawShared(Render3DEvent event, double[] s, int[] colors) {
		Vec3 wasAt = at;
		float wasYaw = yaw;
		int[] wasLook = look;
		at = new Vec3(s[0], s[1], s[2]);
		yaw = (float) s[3];
		look = colors.length >= 2 ? colors : null;
		try {
			onRender3D(event);
		} finally {
			at = wasAt;
			yaw = wasYaw;
			look = wasLook;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (at == null) return;
		Vec3 cam = event.camera().pos;
		if (cam.distanceToSqr(at) > 220 * 220) return;
		float ox = (float) (at.x - cam.x), oy = (float) (at.y - cam.y), oz = (float) (at.z - cam.z);
		Quaternionf turn = turn();
		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn, ox, oy, oz);
		drawHangar(b);
		// Lights glowing along the roof inside
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (float x : new float[]{-12, 0, 12}) {
			for (float z = -16; z <= 16; z += 8) {
				Vector3f p = new Vector3f(x, roof(x) - 0.6f, z).rotate(turn);
				FxRender.billboard(event, glow, ox + p.x, oy + p.y, oz + p.z, 1.6f, 0, LarpLight.glow(0x90FFF4DC));
			}
		}
	}

	/**
	 * The hangar: polished concrete floor with a yellow centre line and parking boxes, ribbed side
	 * and back walls with a band of trim, the arched roof (light inside, the cladding colour out),
	 * the gable over the doors, the two big doors slid open to either side, and light panels down
	 * the inside of the roof.
	 */
	private void drawHangar(ShapeBuilder b) {
		int wall = look != null ? look[0] : walls.get(), rib = ShapeBuilder.shade(wall, 0.88f), band = look != null ? look[1] : trim.get(), inside = 0xFFF1F2F4, floor = 0xFFB9BCC2;
		float w = HALF_WIDTH, d = HALF_DEPTH;
		// Floor, a little proud of the ground, and its markings
		b.box(-w, 0, -d, w, 0.05f, d + 6, floor);
		b.box(-0.2f, 0.05f, -d + 2, 0.2f, 0.06f, d + 6, 0xFFE8C21C);
		for (int side = -1; side <= 1; side += 2) {
			float cx = side * 16.5f;
			b.box(cx - 2.2f, 0.05f, -18, cx + 2.2f, 0.06f, -17.8f, 0xFFF2F2F2);
			b.box(cx - 2.2f, 0.05f, -10.2f, cx + 2.2f, 0.06f, -10, 0xFFF2F2F2);
			b.box(cx - 2.2f, 0.05f, -18, cx - 2, 0.06f, -10, 0xFFF2F2F2);
			b.box(cx + 2, 0.05f, -18, cx + 2.2f, 0.06f, -10, 0xFFF2F2F2);
		}
		// Side and back walls, ribbed, with the trim band
		for (int side = -1; side <= 1; side += 2) {
			float x1 = side * w - 0.15f, x2 = side * w + 0.15f;
			b.box(Math.min(x1, x2), 0, -d, Math.max(x1, x2), WALL, d, wall);
			for (float z = -d + 1; z < d; z += 2) b.box(side * w - side * 0.2f - 0.05f, 0, z - 0.12f, side * w - side * 0.2f + 0.05f, WALL, z + 0.12f, rib);
			b.box(side * w - side * 0.22f - 0.03f, 3.0f, -d, side * w - side * 0.22f + 0.03f, 3.6f, d, band);
		}
		b.box(-w, 0, -d - 0.15f, w, WALL, -d + 0.15f, wall);
		for (float x = -w + 1; x < w; x += 2) b.box(x - 0.12f, 0, -d + 0.15f, x + 0.12f, WALL, -d + 0.25f, rib);
		b.box(-w, 3.0f, -d + 0.2f, w, 3.6f, -d + 0.28f, band);
		// The back wall's arched top
		int steps = 16;
		for (int i = 0; i < steps; i++) {
			float x1 = -w + 2 * w * i / steps, x2 = -w + 2 * w * (i + 1) / steps;
			b.quad(new float[]{x1, WALL, -d}, new float[]{x2, WALL, -d}, new float[]{x2, roof(x2), -d}, new float[]{x1, roof(x1), -d}, wall);
		}
		// The roof: a strip per step across, the cladding outside and lit panels inside
		for (int i = 0; i < steps; i++) {
			float x1 = -w + 2 * w * i / steps, x2 = -w + 2 * w * (i + 1) / steps;
			b.quad(new float[]{x1, roof(x1) + 0.2f, -d}, new float[]{x1, roof(x1) + 0.2f, d}, new float[]{x2, roof(x2) + 0.2f, d}, new float[]{x2, roof(x2) + 0.2f, -d}, wall);
			b.quad(new float[]{x1, roof(x1), -d}, new float[]{x2, roof(x2), -d}, new float[]{x2, roof(x2), d}, new float[]{x1, roof(x1), d}, inside);
		}
		// Light panels down the inside of the roof
		for (float x : new float[]{-12, 0, 12}) {
			for (float z = -16; z <= 16; z += 8) {
				float y = roof(x) - 0.05f;
				b.flat(new float[]{x - 1.2f, y, z - 0.6f}, new float[]{x + 1.2f, y, z - 0.6f}, new float[]{x + 1.2f, y, z + 0.6f}, new float[]{x - 1.2f, y, z + 0.6f}, 0xFFFFF8E6);
			}
		}
		// The gable above the doors, and a trim band across it
		for (int i = 0; i < steps; i++) {
			float x1 = -w + 2 * w * i / steps, x2 = -w + 2 * w * (i + 1) / steps;
			b.quad(new float[]{x1, DOOR, d}, new float[]{x2, DOOR, d}, new float[]{x2, Math.max(DOOR, roof(x2)), d}, new float[]{x1, Math.max(DOOR, roof(x1)), d}, wall);
		}
		b.box(-w, WALL, d - 0.1f, -w + 0.3f, DOOR, d + 0.1f, wall);
		b.box(w - 0.3f, WALL, d - 0.1f, w, DOOR, d + 0.1f, wall);
		b.box(-w, DOOR - 0.6f, d - 0.15f, w, DOOR, d + 0.15f, band);
		// The doors, slid open past each side
		for (int side = -1; side <= 1; side += 2) {
			float x1 = side * w, x2 = side * (w + 11);
			b.box(Math.min(x1, x2), 0, d + 0.3f, Math.max(x1, x2), DOOR - 0.6f, d + 0.7f, ShapeBuilder.shade(wall, 0.94f));
			for (float x = Math.min(x1, x2) + 1; x < Math.max(x1, x2); x += 2.2f) b.box(x - 0.1f, 0, d + 0.7f, x + 0.1f, DOOR - 0.6f, d + 0.78f, rib);
			b.box(Math.min(x1, x2), DOOR - 0.6f, d + 0.25f, Math.max(x1, x2), DOOR - 0.3f, d + 0.8f, 0xFF4A4C52);
		}
	}
}
