package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FxRender;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A modern mansion to walk round: a stone plinth with courses, hedges and palms, steps up the
 * front and a driveway into the garage, a tiled deck with an infinity pool (steps down into it, a
 * ladder, lights under the water), loungers with parasols, a double-height glass front, a living
 * room with a fireplace, a kitchen and a dining room, floating stairs up through the floor to the
 * master bedroom and a balcony, and a roof terrace over the garage. Floors, walls, glass, stairs,
 * railings, the pool and hedges are solid for you while larping, and for fake players, valets and
 * dropped items always.
 * <p>
 * Nothing shares a face with anything else: walls stop under the floor slabs, end walls sit
 * between side walls, and anything lying on a surface starts a hair above it, so nothing flickers.
 */
public class MansionModule extends Module {
	private final ColorSetting wallColor = add(new ColorSetting("Walls", "The rendered walls", 0xFFF2F0EC));
	private final ColorSetting stoneColor = add(new ColorSetting("Stone", "The plinth, the paving and the floor slabs", 0xFFC9C2B6));
	private final ColorSetting woodColor = add(new ColorSetting("Wood", "The wood cladding and floors inside", 0xFF8A5A36));

	/** Its own space: x across (+x to the right, looking at it from the front), y up from the ground, z out of the front toward the pool. */
	private static final float PLINTH = 1.2f, F2 = 5.7f, ROOF = 9.4f, SLAB = 0.3f;
	private static final float HX = 14, BACK = -13, FRONT = 4, GARAGE_X = 6, WALL = 0.25f, GLASS_DEPTH = 0.15f;
	private static final float PX1 = -18, PX2 = 18, PZ1 = -16, PZ2 = 16;
	/** The pool, left of the path from the front steps to the door. */
	private static final float POOL_X1 = -13, POOL_X2 = -4, POOL_Z1 = 6.5f, POOL_Z2 = 14, POOL_FLOOR = 0.25f, COPING = 0.5f;
	/** The driveway down from the garage. */
	private static final float DRIVE_X1 = 7, DRIVE_X2 = 13;
	/** The inside stairs: floating treads climbing toward the front along the left wall, through a hole in the floor. */
	private static final int STEPS = 11;
	private static final float STAIR_X1 = -13.6f, STAIR_X2 = -11.6f, STAIR_Z = -11, STAIR_RUN = 0.6f;
	private static final float HOLE_END = STAIR_Z + STAIR_RUN * STEPS;
	/** The cash room upstairs, between the bedroom and the terrace door: its left wall (with the vault door) and back and front walls. */
	private static final float CASH_X1 = 1.0f, CASH_Z1 = -7.1f, CASH_Z2 = -2.6f, VAULT_WALL = 0.15f, VAULT_DOOR_Z1 = -4.6f, VAULT_DOOR_Z2 = -3.4f;
	/** Where the palms stand, and which way each leans (out, away from the house). */
	private static final float[][] PALMS = {{-16.4f, 14.8f, -1, 1}, {16.4f, 14.8f, 1, 1}, {-16.2f, -14.4f, -1, -1}, {16.2f, -14.4f, 1, -1}};

	private Vec3 at;
	private float yaw;
	private int[] look;

	public MansionModule() {
		super("Mansion", "A modern mansion with a pool, garage, balcony and roof terrace you can walk round. Use " + CommandManager.PREFIX + "mansion", Category.LARP);
	}

	@Override
	public String getInfo() {
		return at != null ? "Home" : null;
	}

	private Quaternionf turn() {
		return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
	}

	// ---------------------------------------------------------------- commands

	/** Handles {@code .mansion [spawn|remove]}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "spawn";
		switch (action) {
			case "spawn", "build", "home" -> {
				if (mc.player == null) return;
				// In front of you, facing you, squared to a compass direction so its boxes fit it
				yaw = Math.round(mc.player.getYRot() / 90f) * 90f + 180;
				double rad = Math.toRadians(yaw + 180);
				at = mc.player.position().add(-Math.sin(rad) * 21, 0, Math.cos(rad) * 21);
				at = new Vec3(at.x, ground(), at.z);
				if (!isEnabled()) setEnabled(true);
				dev.femboyware.util.Mc.addChat(net.minecraft.network.chat.Component.literal("<Butler> Welcome home. The house is ready for you."));
			}
			case "remove", "clear" -> {
				at = null;
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "mansion <spawn|remove>");
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
			return;
		}
		at = new Vec3(p[0], p[1], p[2]);
		yaw = (float) p[3];
		if (!isEnabled()) setEnabled(true);
	}

	/** For tests and the like: builds it at {@code where} facing {@code facing}. */
	public void buildAt(Vec3 where, float facing) {
		at = where;
		yaw = Math.round(facing / 90f) * 90f;
		if (!isEnabled()) setEnabled(true);
	}

	public Vec3 origin() {
		return at;
	}

	public float facing() {
		return yaw;
	}

	/** A point in its own space in the world. */
	public Vec3 world(float x, float y, float z) {
		Vector3f v = new Vector3f(x, y, z).rotate(turn());
		return at.add(v.x, v.y, v.z);
	}

	/** Where to build it: where you're standing, or up on the surface if that's in water. */
	private double ground() {
		double best = mc.player.getY();
		net.minecraft.core.BlockPos.MutableBlockPos pos = net.minecraft.core.BlockPos.containing(at.x, best - 1, at.z).mutable();
		if (mc.level.getFluidState(pos).isEmpty()) pos.move(0, 1, 0);
		if (mc.level.getFluidState(pos).isEmpty()) return best;
		int climbed = 0;
		while (climbed++ < 256 && !mc.level.getFluidState(pos.above()).isEmpty()) pos.move(0, 1, 0);
		return Math.max(best, pos.getY() + 1);
	}

	// ---------------------------------------------------------------- collision

	/** The floor slab at {@code y1..y2} over the house, with the hole the stairs come up through. */
	private static void slab(List<float[]> out, float y1, float y2, float x1, float z1, float x2, float z2) {
		out.add(new float[]{x1, y1, z1, STAIR_X1, y2, z2});
		out.add(new float[]{STAIR_X2, y1, z1, x2, y2, z2});
		out.add(new float[]{STAIR_X1, y1, z1, STAIR_X2, y2, STAIR_Z});
		out.add(new float[]{STAIR_X1, y1, HOLE_END, STAIR_X2, y2, z2});
	}

	private static List<float[]> boxes() {
		List<float[]> boxes = new ArrayList<>();
		// The plinth round the pool, the pool's floor, its steps and the coping round it
		boxes.add(new float[]{PX1, 0, PZ1, PX2, PLINTH, POOL_Z1});
		boxes.add(new float[]{PX1, 0, POOL_Z1, POOL_X1, PLINTH, POOL_Z2});
		boxes.add(new float[]{POOL_X2, 0, POOL_Z1, PX2, PLINTH, POOL_Z2});
		boxes.add(new float[]{PX1, 0, POOL_Z2, PX2, PLINTH, PZ2});
		boxes.add(new float[]{POOL_X1, 0, POOL_Z1, POOL_X2, POOL_FLOOR, POOL_Z2});
		for (int k = 0; k < 3; k++) boxes.add(new float[]{POOL_X2 - 0.45f * (3 - k), POOL_FLOOR, POOL_Z1 + 1.5f, POOL_X2, POOL_FLOOR + 0.3f * (k + 1), POOL_Z2 - 1.5f});
		boxes.add(new float[]{POOL_X1 - COPING, PLINTH, POOL_Z1 - COPING, POOL_X2 + COPING, PLINTH + 0.05f, POOL_Z1});
		boxes.add(new float[]{POOL_X1 - COPING, PLINTH, POOL_Z2, POOL_X2 + COPING, PLINTH + 0.05f, POOL_Z2 + COPING});
		boxes.add(new float[]{POOL_X1 - COPING, PLINTH, POOL_Z1, POOL_X1, PLINTH + 0.05f, POOL_Z2});
		boxes.add(new float[]{POOL_X2, PLINTH, POOL_Z1, POOL_X2 + COPING, PLINTH + 0.05f, POOL_Z2});
		// Front steps down to the ground, and the driveway down from the garage
		// Each runs out from the plinth; the further out, the lower, so together they step down
		for (int j = 0; j < 3; j++) boxes.add(new float[]{-3, 0, PZ2, 3, PLINTH - 0.3f * (j + 1), PZ2 + 0.45f * (j + 1)});
		for (int j = 0; j < 5; j++) boxes.add(new float[]{DRIVE_X1, 0, PZ2, DRIVE_X2, PLINTH - 0.2f * (j + 1), PZ2 + 0.7f * (j + 1)});
		// Hedges along the back and down the sides, and the palm trunks
		boxes.add(new float[]{PX1 + 0.4f, PLINTH, PZ1 + 0.2f, PX2 - 0.4f, PLINTH + 1.1f, PZ1 + 0.9f});
		for (int side = -1; side <= 1; side += 2) boxes.add(new float[]{Math.min(side * 17.8f, side * 17.1f), PLINTH, PZ1 + 0.9f, Math.max(side * 17.8f, side * 17.1f), PLINTH + 1.1f, 2});
		for (float[] p : PALMS) boxes.add(new float[]{p[0] - 0.25f, PLINTH, p[1] - 0.25f, p[0] + 0.25f, PLINTH + 3, p[1] + 0.25f});
		// Ground floor: back wall, side walls, the front's glass and frames, and the door; all stop under the slab
		float top = F2 - SLAB, gz = FRONT - GLASS_DEPTH;
		boxes.add(new float[]{-HX, PLINTH, BACK, HX, top, BACK + WALL});
		boxes.add(new float[]{-HX, PLINTH, BACK + WALL, -HX + WALL, top, FRONT});
		boxes.add(new float[]{HX - WALL, PLINTH, BACK + WALL, HX, top, FRONT});
		boxes.add(new float[]{-HX + WALL, PLINTH, gz, -1.2f, top, FRONT});
		boxes.add(new float[]{1.2f, PLINTH, gz, GARAGE_X + 1, top, FRONT});
		boxes.add(new float[]{-1.2f, PLINTH + 2.6f, gz, 1.2f, top, FRONT});
		boxes.add(new float[]{GARAGE_X + 1, PLINTH + 3.4f, gz, HX - 1, top, FRONT});
		boxes.add(new float[]{HX - 1, PLINTH, gz, HX - WALL, top, FRONT});
		// The wall between the house and the garage, with its door
		boxes.add(new float[]{GARAGE_X, PLINTH, BACK + WALL, GARAGE_X + 0.2f, top, -2});
		boxes.add(new float[]{GARAGE_X, PLINTH, 0, GARAGE_X + 0.2f, top, gz});
		boxes.add(new float[]{GARAGE_X, PLINTH + 2.6f, -2, GARAGE_X + 0.2f, top, 0});
		// The first floor over everything, open over the stairs, and the balcony out front
		slab(boxes, top, F2, -HX, BACK, HX, FRONT);
		boxes.add(new float[]{-HX, top, FRONT, GARAGE_X, F2, FRONT + 2});
		// The stairs, and the rail round the hole at the top
		for (int i = 0; i < STEPS; i++) {
			float stepTop = PLINTH + (F2 - PLINTH) * (i + 1) / STEPS;
			boxes.add(new float[]{STAIR_X1, PLINTH, STAIR_Z + STAIR_RUN * i, STAIR_X2, stepTop, STAIR_Z + STAIR_RUN * (i + 1)});
		}
		boxes.add(new float[]{STAIR_X2, F2, STAIR_Z, STAIR_X2 + 0.08f, F2 + 1, HOLE_END - STAIR_RUN});
		boxes.add(new float[]{STAIR_X1, F2, STAIR_Z - 0.08f, STAIR_X2 + 0.08f, F2 + 1, STAIR_Z});
		// Upstairs: back wall, left wall, the wall to the terrace with its door, and the glass front with the balcony door
		boxes.add(new float[]{-HX, F2, BACK, GARAGE_X, ROOF, BACK + WALL});
		boxes.add(new float[]{-HX, F2, BACK + WALL, -HX + WALL, ROOF, FRONT});
		boxes.add(new float[]{GARAGE_X - WALL, F2, BACK + WALL, GARAGE_X, ROOF, -2});
		boxes.add(new float[]{GARAGE_X - WALL, F2, 0, GARAGE_X, ROOF, FRONT});
		boxes.add(new float[]{GARAGE_X - WALL, F2 + 2.6f, -2, GARAGE_X, ROOF, 0});
		boxes.add(new float[]{-HX + WALL, F2, gz, -1.2f, ROOF, FRONT});
		boxes.add(new float[]{1.2f, F2, gz, GARAGE_X - WALL, ROOF, FRONT});
		boxes.add(new float[]{-1.2f, F2 + 2.6f, gz, 1.2f, ROOF, FRONT});
		// Glass railings round the balcony and the roof terrace
		boxes.add(new float[]{-HX, F2, FRONT + 1.92f, GARAGE_X, F2 + 1, FRONT + 2});
		boxes.add(new float[]{-HX, F2, FRONT, -HX + 0.08f, F2 + 1, FRONT + 2});
		boxes.add(new float[]{GARAGE_X - 0.08f, F2, FRONT, GARAGE_X, F2 + 1, FRONT + 2});
		boxes.add(new float[]{HX - 0.08f, F2, BACK, HX, F2 + 1, FRONT});
		boxes.add(new float[]{GARAGE_X, F2, FRONT - 0.08f, HX, F2 + 1, FRONT});
		boxes.add(new float[]{GARAGE_X, F2, BACK, HX, F2 + 1, BACK + 0.08f});
		// The roof
		boxes.add(new float[]{-HX - 0.5f, ROOF, BACK - 0.5f, GARAGE_X + 0.5f, ROOF + 0.35f, FRONT + 0.5f});
		// The cash room upstairs: its vault walls with the doorway, the open vault door, the shelves, the pallet and the counting table
		boxes.add(new float[]{CASH_X1, F2, CASH_Z1, CASH_X1 + VAULT_WALL, ROOF, VAULT_DOOR_Z1});
		boxes.add(new float[]{CASH_X1, F2, VAULT_DOOR_Z2, CASH_X1 + VAULT_WALL, ROOF, CASH_Z2});
		boxes.add(new float[]{CASH_X1, F2 + 2.3f, VAULT_DOOR_Z1, CASH_X1 + VAULT_WALL, ROOF, VAULT_DOOR_Z2});
		boxes.add(new float[]{CASH_X1, F2, CASH_Z1, GARAGE_X - WALL, ROOF, CASH_Z1 + VAULT_WALL});
		boxes.add(new float[]{CASH_X1, F2, CASH_Z2 - VAULT_WALL, GARAGE_X - WALL, ROOF, CASH_Z2});
		boxes.add(new float[]{CASH_X1 - 1.28f, F2, VAULT_DOOR_Z2, CASH_X1 - 0.02f, F2 + 2.3f, VAULT_DOOR_Z2 + 0.24f});
		boxes.add(new float[]{CASH_X1 + VAULT_WALL, F2, CASH_Z1 + VAULT_WALL, GARAGE_X - WALL, ROOF, CASH_Z1 + VAULT_WALL + 0.5f});
		boxes.add(new float[]{GARAGE_X - WALL - 0.5f, F2, CASH_Z1 + VAULT_WALL + 0.5f, GARAGE_X - WALL, ROOF, CASH_Z2 - VAULT_WALL});
		boxes.add(new float[]{2.4f, F2, -5.6f, 3.8f, F2 + 0.75f, -4.4f});
		boxes.add(new float[]{1.35f, F2, -3.35f, 2.35f, F2 + 0.8f, -2.75f});
		// The big things you'd bump into: the kitchen island, the dining table and the bed
		boxes.add(new float[]{-6, PLINTH, -9.2f, 0, PLINTH + 0.95f, -7.8f});
		boxes.add(new float[]{0.5f, PLINTH, -6, 3.5f, PLINTH + 0.78f, -2});
		boxes.add(new float[]{-10, F2, -12.7f, -5, F2 + 0.6f, -8});
		// The watch room: the winder wall, and the two vitrines up to their glass tops
		boxes.add(new float[]{-11.3f, F2, -4.3f, -6.1f, F2 + 2.6f, -3.9f});
		boxes.add(new float[]{-12.92f, F2, -2.62f, -7.08f, F2 + 1.16f, -1.78f});
		boxes.add(new float[]{-12.92f, F2, 0.58f, -7.08f, F2 + 1.16f, 1.42f});
		return boxes;
	}

	private static final List<float[]> BOXES = boxes();

	/**
	 * What the raid's agents walk round: everything solid, plus the furniture you can walk into
	 * (sofas, chairs, stools, tables, the watch cases, plants), the pool and the hole over the stairs.
	 */
	static List<float[]> navBoxes() {
		List<float[]> out = new ArrayList<>(BOXES);
		float p = PLINTH, u = F2, gz = FRONT - GLASS_DEPTH;
		// Living room: the L sofa, the coffee table and the fireplace
		out.add(new float[]{-9, p, 1, -4, p + 0.9f, 2});
		out.add(new float[]{-9, p, -2, -8, p + 0.9f, 1});
		out.add(new float[]{-7, p, -1, -5, p + 0.6f, 0.2f});
		out.add(new float[]{-HX + WALL, p, -2.2f, -HX + 1.0f, p + 1, 1.2f});
		// Kitchen units and the stools along the island
		out.add(new float[]{-8, p, BACK, 2, p + 1, BACK + 0.92f});
		out.add(new float[]{-5.25f, p, -7.4f, -0.85f, p + 0.8f, -6.9f});
		// The dining chairs either side of the table
		out.add(new float[]{-0.25f, p, -5.65f, 0.25f, p + 1.05f, -2.15f});
		out.add(new float[]{3.75f, p, -5.65f, 4.25f, p + 1.05f, -2.15f});
		// Plants in the corners
		for (float[] q : new float[][]{{-HX + 0.8f, p}, {GARAGE_X - 0.7f, p}, {-HX + 0.8f, u}, {GARAGE_X - WALL - 0.7f, u}})
			out.add(new float[]{q[0] - 0.35f, q[1], gz - 0.95f, q[0] + 0.35f, q[1] + 1.3f, gz - 0.25f});
		// Out on the deck: the pool, the loungers and parasols
		out.add(new float[]{POOL_X1 - COPING, p, POOL_Z1 - COPING, POOL_X2 + COPING, p + 1, POOL_Z2 + COPING});
		for (float z : new float[]{7.5f, 11.2f}) {
			out.add(new float[]{3.74f, p, z, 6.26f, p + 0.8f, z + 2});
		}
		// Upstairs: the hole over the stairs, the nightstands, wardrobe, desk and its stool, the sofa
		out.add(new float[]{STAIR_X1, u, STAIR_Z, STAIR_X2 + 0.08f, u + 1, HOLE_END - 0.35f});
		for (float x : new float[]{-11, -4}) out.add(new float[]{x - 0.4f, u, -12.6f, x + 0.4f, u + 1, -12});
		out.add(new float[]{GARAGE_X - WALL - 0.65f, u, -11, GARAGE_X - WALL, u + 2.6f, -7});
		out.add(new float[]{0, u, -12.6f, 3, u + 0.75f, -11.8f});
		out.add(new float[]{1.2f, u, -11.4f, 1.8f, u + 0.5f, -10.8f});
		out.add(new float[]{-4, u, 0, 1, u + 0.9f, 1});
		// (The watch room's winder wall and vitrines are solid, so already in BOXES)
		return out;
	}

	/**
	 * For EntityRendererMixin: the block light the mansion's lamps give something standing at
	 * {@code pos}: bright indoors, lamplit out on the grounds, or 0 away from it.
	 */
	public int lampLight(Vec3 pos) {
		if (!isEnabled() || at == null) return 0;
		Vector3f v = new Vector3f((float) (pos.x - at.x), (float) (pos.y - at.y), (float) (pos.z - at.z)).rotate(new Quaternionf().rotateY((float) Math.toRadians(yaw)));
		if (v.y < -2 || v.y > ROOF + 4) return 0;
		if (v.x > -HX - 0.5f && v.x < HX + 0.5f && v.z > BACK - 0.5f && v.z < FRONT + 2.5f) return 14;
		if (v.x > PX1 - 3 && v.x < PX2 + 3 && v.z > PZ1 - 3 && v.z < PZ2 + 14) return 11;
		return 0;
	}

	/** Called through LarpProps: its floors, walls, glass, stairs, pool and hedges as solid boxes. */
	public List<VoxelShape> collisions(Entity entity, AABB area) {
		if (!isEnabled() || at == null || !LarpProps.solidFor(entity)) return new ArrayList<>();
		return shapes(entity, area);
	}

	private List<VoxelShape> shapes(Entity entity, AABB area) {
		List<VoxelShape> shapes = new ArrayList<>();
		if (at.distanceToSqr(entity.position()) > 50 * 50) return shapes;
		Quaternionf turn = turn();
		for (float[] b : BOXES) {
			Vector3f p = new Vector3f(b[0], b[1], b[2]).rotate(turn), q = new Vector3f(b[3], b[4], b[5]).rotate(turn);
			AABB box = new AABB(at.x + p.x, at.y + p.y, at.z + p.z, at.x + q.x, at.y + q.y, at.z + q.z);
			if (box.intersects(area)) shapes.add(Shapes.create(box));
		}
		return shapes;
	}

	// ---------------------------------------------------------------- Client Sync

	public double[] shareState() {
		return isEnabled() && at != null ? new double[]{at.x, at.y, at.z, yaw} : null;
	}

	public int[] shareLook() {
		return new int[]{wallColor.get(), stoneColor.get(), woodColor.get()};
	}

	public void drawShared(Render3DEvent event, double[] s, int[] colors) {
		Vec3 wasAt = at;
		float wasYaw = yaw;
		int[] wasLook = look;
		at = new Vec3(s[0], s[1], s[2]);
		yaw = (float) s[3];
		look = colors.length >= 3 ? colors : null;
		try {
			draw(event);
		} finally {
			at = wasAt;
			yaw = wasYaw;
			look = wasLook;
		}
	}

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

	// ---------------------------------------------------------------- drawing

	@Override
	public void onRender3D(Render3DEvent event) {
		if (at != null) draw(event);
	}

	private void draw(Render3DEvent event) {
		Vec3 cam = event.camera().pos;
		if (cam.distanceToSqr(at) > 260 * 260) return;
		float ox = (float) (at.x - cam.x), oy = (float) (at.y - cam.y), oz = (float) (at.z - cam.z);
		Quaternionf turn = turn();
		// Floodlit outside and the lamps on inside, so it's never a dark hulk at night
		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn, ox, oy, oz).lit(0.6f);
		ShapeBuilder in = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn, ox, oy, oz).soft().lit(0.9f);
		ShapeBuilder glass = new ShapeBuilder(null, event.matrices().last(), turn, ox, oy, oz);
		int wall = look != null ? look[0] : wallColor.get(), stone = look != null ? look[1] : stoneColor.get(), wood = look != null ? look[2] : woodColor.get();
		drawGrounds(b, glass, stone);
		drawHouse(b, glass, wall, stone, wood);
		watchLights.clear();
		drawInside(in, glass, wood);
		// The cash room's walls, vault door and shelving, with the rest of the inside (before another batch closes this one)
		drawCashRoomShell(in, F2 + 0.016f);
		// The watch room, in a batch of its own, only up close: it's a lot of little shapes
		Vec3 room = world(-9.5f, F2 + 1, -0.5f);
		if (cam.distanceToSqr(room) < 20 * 20) {
			ShapeBuilder watches = new ShapeBuilder(FxRender.solidDetail(event), event.matrices().last(), turn, ox, oy, oz).soft().lit(0.9f);
			drawWatchRoom(watches, glass, F2 + 0.016f);
		}
		// The cash in the cash room and round the house, close up only, gone once the FBI's seized it
		Vec3 middle = world(-2, F2, -5);
		if (cam.distanceToSqr(middle) < 26 * 26) {
			ShapeBuilder cash = new ShapeBuilder(FxRender.solidDetail(event), event.matrices().last(), turn, ox, oy, oz).soft().lit(0.9f);
			drawCash(cash, glass, PLINTH + 0.016f, F2 + 0.016f, (System.currentTimeMillis() % 600_000) / 1000f);
		}
		glass.flush(FxRender.quads(event, false, false));

		// Lights: the pool from under the water, lamps round the deck, a light each side of the doors, and the rooms at night
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (float x = POOL_X1 + 1.5f; x < POOL_X2; x += 2.5f) {
			light(event, glow, turn, ox, oy, oz, x, 0.7f, POOL_Z1 + 0.1f, 1.4f, 0x6030A8FF);
			light(event, glow, turn, ox, oy, oz, x, 0.7f, POOL_Z2 - 0.1f, 1.4f, 0x6030A8FF);
		}
		for (float[] p : lamps()) light(event, glow, turn, ox, oy, oz, p[0], PLINTH + 2.12f, p[1], 0.5f, 0xC0FFE2B0);
		for (float x : new float[]{-1.6f, 1.6f}) light(event, glow, turn, ox, oy, oz, x, PLINTH + 2.4f, FRONT + 0.15f, 0.35f, 0xC0FFE2B0);
		for (float x = -11; x < 5; x += 4) {
			for (float z = -10; z < 3; z += 4) {
				light(event, glow, turn, ox, oy, oz, x, F2 - SLAB - 0.15f, z, 0.35f, 0x90FFE2B0);
				light(event, glow, turn, ox, oy, oz, x, ROOF - 0.15f, z, 0.35f, 0x90FFE2B0);
			}
		}
		light(event, glow, turn, ox, oy, oz, 2, F2 - SLAB - 1.25f, -4, 0.8f, 0xA0FFE8C0);
		// At night: uplights washing the front and the ends of the house, and a glow out of every room through the glass
		float night = LarpLight.night();
		if (night > 0.05f) {
			int wash = (Math.round(0x70 * night) << 24) | 0xFFD9A0;
			for (float x = -12; x <= 12; x += 3) light(event, glow, turn, ox, oy, oz, x, PLINTH + 0.6f, FRONT + 0.5f, 1.7f, wash);
			for (float x = -12; x <= 4; x += 4) light(event, glow, turn, ox, oy, oz, x, F2 + 0.8f, FRONT + 0.6f, 1.6f, wash);
			for (float z = -11; z <= 2; z += 4.3f) {
				light(event, glow, turn, ox, oy, oz, -HX - 0.5f, PLINTH + 0.6f, z, 1.6f, wash);
				light(event, glow, turn, ox, oy, oz, HX + 0.5f, PLINTH + 0.6f, z, 1.6f, wash);
			}
			int roomGlow = (Math.round(0x58 * night) << 24) | 0xFFE6B8;
			for (float x = -11; x < 5; x += 3.2f) {
				for (float z = -10; z < 3; z += 3.2f) {
					light(event, glow, turn, ox, oy, oz, x, PLINTH + 1.6f, z, 2.2f, roomGlow);
					light(event, glow, turn, ox, oy, oz, x, F2 + 1.5f, z, 2.2f, roomGlow);
				}
			}
		}
		for (float x = -5; x <= -1; x += 2) light(event, glow, turn, ox, oy, oz, x, F2 - SLAB - 1, -8.5f, 0.3f, 0xA0FFE8C0);
		// Spotlights over the cash
		for (float[] l : new float[][]{{3.1f, -5.0f}, {2.0f, -6.5f}, {4.6f, -6.5f}, {5.3f, -4.5f}}) light(event, glow, turn, ox, oy, oz, l[0], ROOF - 0.12f, l[1], 0.45f, 0xB0FFE6B8);
		// The fire in the fireplace
		long ms = System.currentTimeMillis();
		float flicker = 0.8f + 0.2f * (float) Math.sin(ms / 90.0) * (float) Math.sin(ms / 230.0);
		light(event, glow, turn, ox, oy, oz, -HX + 0.55f, PLINTH + 0.4f, -0.5f, 0.6f * flicker, 0xE0FF8A2A);
		light(event, glow, turn, ox, oy, oz, -HX + 0.55f, PLINTH + 0.3f, -0.5f, 0.35f, 0xF0FFD27A);
		// The fire table on the roof terrace
		light(event, glow, turn, ox, oy, oz, GARAGE_X + 5.2f, F2 + 0.65f, -1.6f, 0.7f * flicker, 0xD0FF8A2A);
		// Garage strip lights
		for (float z = -11; z < 3; z += 3.5f) light(event, glow, turn, ox, oy, oz, 10, F2 - SLAB - 0.1f, z, 0.4f, 0x80F2F6FF);
		// The watch room's case lights, and diamonds twinkling on the iced ones
		for (float[] w : watchLights) {
			int color = Float.floatToRawIntBits(w[4]);
			float size = w[3];
			if (w.length > 5) {
				// A twinkle: a quick sharp glint now and then, each diamond on its own beat
				double phase = (ms / 1000.0 * 1.7 + w[5]) % 1.0;
				float glint = phase < 0.12 ? (float) Math.sin(phase / 0.12 * Math.PI) : 0;
				if (glint <= 0.02f) continue;
				size *= glint;
			}
			light(event, glow, turn, ox, oy, oz, w[0], w[1], w[2], size, color);
		}
		// The living room TV, facing the sofa, last since it's its own layer
		dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.larp.TvModule.class).drawBuiltIn(event, turn, ox, oy, oz,
			new Vector3f(-HX + WALL + 0.075f, PLINTH + 2.4f, -0.5f), new Vector3f(0, 0, -1), 2.78f, 1.48f);
	}

	private static float[][] lamps() {
		return new float[][]{{-15, 15.3f}, {15, 15.3f}, {-16.5f, 5}, {16.5f, 5}, {4.5f, 15.3f}, {-4.5f, 15.3f}};
	}

	private static void light(Render3DEvent event, VertexConsumer glow, Quaternionf rot, float ox, float oy, float oz, float x, float y, float z, float size, int color) {
		Vector3f p = new Vector3f(x, y, z).rotate(rot);
		FxRender.billboard(event, glow, ox + p.x, oy + p.y, oz + p.z, size, 0, LarpLight.glow(color));
	}

	/** Whether a rectangle on the deck overlaps something that isn't paving: the house, the pool and its coping, or the driveway. */
	private static boolean notPaving(float x1, float z1, float x2, float z2) {
		boolean house = x2 > -HX && x1 < HX && z2 > BACK && z1 < FRONT;
		boolean pool = x2 > POOL_X1 - COPING && x1 < POOL_X2 + COPING && z2 > POOL_Z1 - COPING && z1 < POOL_Z2 + COPING;
		boolean drive = x2 > DRIVE_X1 && x1 < DRIVE_X2 && z2 > FRONT && z1 < PZ2;
		return house || pool || drive;
	}

	/**
	 * The grounds: the plinth with stone courses round it, paving in slabs with grout between, the
	 * pool with its coping, steps, ladder and water, the front steps and driveway, hedges, loungers
	 * under parasols, lamp posts and palm trees.
	 */
	private void drawGrounds(ShapeBuilder b, ShapeBuilder glass, int stone) {
		int grout = ShapeBuilder.shade(stone, 0.8f), course = ShapeBuilder.shade(stone, 0.88f), cap = ShapeBuilder.shade(stone, 1.06f);
		// The plinth round the pool
		b.box(PX1, 0, PZ1, PX2, PLINTH, POOL_Z1, grout);
		b.box(PX1, 0, POOL_Z1, POOL_X1, PLINTH, POOL_Z2, grout);
		b.box(POOL_X2, 0, POOL_Z1, PX2, PLINTH, POOL_Z2, grout);
		b.box(PX1, 0, POOL_Z2, PX2, PLINTH, PZ2, grout);
		// Courses of stone round its sides, and a capping along the top edge, a little proud
		for (float y : new float[]{0.36f, 0.76f}) {
			b.box(PX1 - 0.012f, y, PZ1 - 0.012f, PX2 + 0.012f, y + 0.04f, PZ1, course);
			b.box(PX1 - 0.012f, y, PZ1, PX1, y + 0.04f, PZ2, course);
			b.box(PX2, y, PZ1, PX2 + 0.012f, y + 0.04f, PZ2, course);
			for (float[] span : new float[][]{{PX1, -3}, {3, DRIVE_X1}, {DRIVE_X2, PX2}}) b.box(span[0], y, PZ2, span[1], y + 0.04f, PZ2 + 0.012f, course);
		}
		b.box(PX1 - 0.03f, PLINTH - 0.1f, PZ1 - 0.03f, PX2 + 0.03f, PLINTH + 0.005f, PZ1, cap);
		b.box(PX1 - 0.03f, PLINTH - 0.1f, PZ1, PX1, PLINTH + 0.005f, PZ2, cap);
		b.box(PX2, PLINTH - 0.1f, PZ1, PX2 + 0.03f, PLINTH + 0.005f, PZ2, cap);
		for (float[] span : new float[][]{{PX1 - 0.03f, -3}, {3, DRIVE_X1}, {DRIVE_X2, PX2 + 0.03f}}) b.box(span[0], PLINTH - 0.1f, PZ2, span[1], PLINTH + 0.005f, PZ2 + 0.03f, cap);
		// Paving: large slabs with grout lines, each a touch different, never over the house, pool or driveway
		for (float x = PX1; x < PX2 - 0.01f; x += 1.5f) {
			for (float z = PZ1; z < PZ2 - 0.01f; z += 1.6f) {
				float x2 = Math.min(PX2, x + 1.5f), z2 = Math.min(PZ2, z + 1.6f);
				if (notPaving(x, z, x2, z2)) continue;
				int h = Math.floorMod((int) (x * 7 + z * 13), 5);
				b.box(x + 0.02f, PLINTH, z + 0.02f, x2 - 0.02f, PLINTH + 0.012f, z2 - 0.02f, ShapeBuilder.shade(stone, 0.96f + h * 0.015f));
			}
		}
		// The driveway, in darker setts
		for (float z = FRONT; z < PZ2 - 0.01f; z += 1) {
			for (float x = DRIVE_X1; x < DRIVE_X2 - 0.01f; x += 1) {
				b.box(x + 0.02f, PLINTH, z + 0.02f, x + 0.98f, PLINTH + 0.012f, z + 0.98f, ((int) (x + z)) % 2 == 0 ? 0xFF5E6168 : 0xFF575A61);
			}
		}
		drawPool(b, glass, stone);
		// The front steps, and the driveway ramp, each step a little set back
		// Each step is its own block from the ground up, so no two share a face
		for (int j = 0; j < 3; j++) b.box(-3, 0, PZ2 + 0.45f * j, 3, PLINTH - 0.3f * (j + 1), PZ2 + 0.45f * (j + 1), j % 2 == 0 ? cap : stone);
		for (int j = 0; j < 5; j++) b.box(DRIVE_X1, 0, PZ2 + 0.7f * j, DRIVE_X2, PLINTH - 0.2f * (j + 1), PZ2 + 0.7f * (j + 1), j % 2 == 0 ? 0xFF5A5D63 : 0xFF55585E);
		// Hedges along the back and down the sides
		int hedge = 0xFF2F5A27, hedgeTop = 0xFF3A6B2F;
		hedgeBox(b, PX1 + 0.4f, PZ1 + 0.2f, PX2 - 0.4f, PZ1 + 0.9f, hedge, hedgeTop);
		for (int side = -1; side <= 1; side += 2) hedgeBox(b, Math.min(side * 17.8f, side * 17.1f), PZ1 + 0.9f, Math.max(side * 17.8f, side * 17.1f), 2, hedge, hedgeTop);
		// Loungers on the deck between the path and the driveway, under parasols
		for (float z : new float[]{7.5f, 11.2f}) {
			lounger(b, 4.1f, z);
			lounger(b, 5.9f, z);
			parasol(b, 5.0f, z + 1.0f);
		}
		// Lamp posts
		for (float[] p : lamps()) {
			b.box(p[0] - 0.07f, PLINTH + 0.013f, p[1] - 0.07f, p[0] + 0.07f, PLINTH + 2, p[1] + 0.07f, 0xFF2A2C31);
			b.box(p[0] - 0.18f, PLINTH + 2, p[1] - 0.18f, p[0] + 0.18f, PLINTH + 2.25f, p[1] + 0.18f, 0xFFF4EBD8);
			b.box(p[0] - 0.21f, PLINTH + 2.25f, p[1] - 0.21f, p[0] + 0.21f, PLINTH + 2.3f, p[1] + 0.21f, 0xFF2A2C31);
		}
		for (float[] p : PALMS) palm(b, p[0], p[1], p[2], p[3]);
	}

	/** A clipped hedge: a dark box with a lighter top, rounded off a little at the top edges. */
	private static void hedgeBox(ShapeBuilder b, float x1, float z1, float x2, float z2, int side, int top) {
		b.box(x1, PLINTH + 0.013f, z1, x2, PLINTH + 0.95f, z2, side);
		b.box(x1 + 0.05f, PLINTH + 0.95f, z1 + 0.05f, x2 - 0.05f, PLINTH + 1.1f, z2 - 0.05f, top);
	}

	/**
	 * The pool: tiled floor and walls, a darker band at the waterline, steps down at the shallow
	 * end, a ladder at the deep end, a stone coping round it, and the water, a little below the top.
	 */
	private void drawPool(ShapeBuilder b, ShapeBuilder glass, int stone) {
		int tile = 0xFF3E9CC6, deep = 0xFF2E7FA8, band = 0xFF1F5E80, coping = ShapeBuilder.shade(stone, 1.08f), white = 0xFFF2F2F0;
		b.box(POOL_X1, 0, POOL_Z1, POOL_X2, POOL_FLOOR, POOL_Z2, deep);
		// Tiles up each wall, just in from the plinth so the faces never meet, with the waterline band
		float in = 0.015f, wy = POOL_FLOOR + 0.001f, water = PLINTH - 0.18f;
		b.box(POOL_X1, wy, POOL_Z1, POOL_X1 + in, PLINTH, POOL_Z2, tile);
		b.box(POOL_X2 - in, wy, POOL_Z1, POOL_X2, PLINTH, POOL_Z2, tile);
		b.box(POOL_X1 + in, wy, POOL_Z1, POOL_X2 - in, PLINTH, POOL_Z1 + in, tile);
		b.box(POOL_X1 + in, wy, POOL_Z2 - in, POOL_X2 - in, PLINTH, POOL_Z2, tile);
		b.box(POOL_X1 + in, water - 0.06f, POOL_Z1 + in, POOL_X1 + in + 0.006f, water + 0.06f, POOL_Z2 - in, band);
		b.box(POOL_X1 + in + 0.006f, water - 0.06f, POOL_Z1 + in, POOL_X2 - in - 0.006f, water + 0.06f, POOL_Z1 + in + 0.006f, band);
		b.box(POOL_X1 + in + 0.006f, water - 0.06f, POOL_Z2 - in - 0.006f, POOL_X2 - in - 0.006f, water + 0.06f, POOL_Z2 - in, band);
		// A lane line along the floor
		b.box(POOL_X1 + 1, POOL_FLOOR, (POOL_Z1 + POOL_Z2) / 2 - 0.1f, POOL_X2 - 2, POOL_FLOOR + 0.006f, (POOL_Z1 + POOL_Z2) / 2 + 0.1f, 0xFF1A4F6E);
		// Steps down at the shallow end
		for (int k = 0; k < 3; k++) {
			float x1 = POOL_X2 - in - 0.45f * (3 - k);
			b.box(x1, POOL_FLOOR + 0.001f, POOL_Z1 + 1.5f, POOL_X2 - in - 0.001f, POOL_FLOOR + 0.3f * (k + 1), POOL_Z2 - 1.5f, white);
			b.box(x1, POOL_FLOOR + 0.3f * (k + 1), POOL_Z1 + 1.5f, x1 + 0.06f, POOL_FLOOR + 0.3f * (k + 1) + 0.004f, POOL_Z2 - 1.5f, deep);
		}
		// A ladder at the deep end, its rails curving over the coping
		for (float z : new float[]{9.8f, 10.6f}) {
			b.box(POOL_X1 + 0.12f, 0.5f, z - 0.03f, POOL_X1 + 0.18f, PLINTH + 0.8f, z + 0.03f, 0xFFD6DAE0);
			b.box(POOL_X1 - 0.35f, PLINTH + 0.74f, z - 0.03f, POOL_X1 + 0.18f, PLINTH + 0.8f, z + 0.03f, 0xFFD6DAE0);
			b.box(POOL_X1 - 0.35f, PLINTH + 0.05f, z - 0.03f, POOL_X1 - 0.29f, PLINTH + 0.74f, z + 0.03f, 0xFFD6DAE0);
		}
		for (float y = 0.6f; y < PLINTH; y += 0.3f) b.box(POOL_X1 + 0.13f, y, 9.83f, POOL_X1 + 0.17f, y + 0.04f, 10.57f, 0xFFD6DAE0);
		// Coping round the top, overhanging the pool a little
		b.box(POOL_X1 - COPING, PLINTH, POOL_Z1 - COPING, POOL_X2 + COPING, PLINTH + 0.05f, POOL_Z1 + 0.05f, coping);
		b.box(POOL_X1 - COPING, PLINTH, POOL_Z2 - 0.05f, POOL_X2 + COPING, PLINTH + 0.05f, POOL_Z2 + COPING, coping);
		b.box(POOL_X1 - COPING, PLINTH, POOL_Z1 + 0.05f, POOL_X1 + 0.05f, PLINTH + 0.05f, POOL_Z2 - 0.05f, coping);
		b.box(POOL_X2 - 0.05f, PLINTH, POOL_Z1 + 0.05f, POOL_X2 + COPING, PLINTH + 0.05f, POOL_Z2 - 0.05f, coping);
		// The water: see-through, inside the tiles, its surface below the coping
		glass.box(POOL_X1 + in + 0.01f, POOL_FLOOR + 0.01f, POOL_Z1 + in + 0.01f, POOL_X2 - in - 0.01f, water, POOL_Z2 - in - 0.01f, 0x7040B8E8);
	}

	/** A sun lounger lying along z: four legs, a slatted frame, a cushion, a reclined back and a towel. */
	private static void lounger(ShapeBuilder b, float x, float z) {
		lounger(b, x, z, PLINTH + 0.013f);
	}

	private static void lounger(ShapeBuilder b, float x, float z, float y) {
		int frame = 0xFF3A3530, cushion = 0xFFF4F1EA;
		float w = 0.36f, foot = z + 2.0f;
		for (float lx : new float[]{x - w + 0.05f, x + w - 0.05f}) {
			for (float lz : new float[]{z + 0.15f, foot - 0.1f}) b.box(lx - 0.03f, y, lz - 0.03f, lx + 0.03f, y + 0.3f, lz + 0.03f, frame);
		}
		b.box(x - w, y + 0.3f, z, x + w, y + 0.36f, foot, frame);
		b.box(x - w + 0.03f, y + 0.36f, z + 0.65f, x + w - 0.03f, y + 0.46f, foot - 0.02f, cushion);
		// The back, tipped up at about 40 degrees from its hinge
		slope(b, x - w + 0.03f, x + w - 0.03f, z + 0.65f, y + 0.4f, z + 0.05f, y + 0.95f, 0.1f, cushion);
		// A towel draped over the foot
		b.box(x - w + 0.06f, y + 0.461f, foot - 0.75f, x + w - 0.06f, y + 0.48f, foot - 0.02f, 0xFFFF77C8);
	}

	/** A slab running from (zA, yA) to (zB, yB), {@code thick} deep, across x1..x2: for leaning backs. */
	private static void slope(ShapeBuilder b, float x1, float x2, float zA, float yA, float zB, float yB, float thick, int color) {
		float dz = zB - zA, dy = yB - yA, length = (float) Math.sqrt(dz * dz + dy * dy);
		float nz = -dy / length * thick, ny = dz / length * thick;
		float[][] top = {{zA, yA}, {zB, yB}}, under = {{zA + nz, yA + ny}, {zB + nz, yB + ny}};
		b.quad(new float[]{x1, top[0][1], top[0][0]}, new float[]{x1, top[1][1], top[1][0]}, new float[]{x2, top[1][1], top[1][0]}, new float[]{x2, top[0][1], top[0][0]}, color);
		b.quad(new float[]{x2, under[0][1], under[0][0]}, new float[]{x2, under[1][1], under[1][0]}, new float[]{x1, under[1][1], under[1][0]}, new float[]{x1, under[0][1], under[0][0]}, color);
		for (float x : new float[]{x1, x2}) {
			b.quad(new float[]{x, top[0][1], top[0][0]}, new float[]{x, under[0][1], under[0][0]}, new float[]{x, under[1][1], under[1][0]}, new float[]{x, top[1][1], top[1][0]}, color);
		}
		b.quad(new float[]{x1, top[1][1], top[1][0]}, new float[]{x1, under[1][1], under[1][0]}, new float[]{x2, under[1][1], under[1][0]}, new float[]{x2, top[1][1], top[1][0]}, color);
	}

	/** A parasol: a pole on a heavy base and an eight-sided canopy, white with a pink trim. */
	private static void parasol(ShapeBuilder b, float x, float z) {
		float y = PLINTH + 0.013f;
		b.box(x - 0.25f, y, z - 0.25f, x + 0.25f, y + 0.12f, z + 0.25f, 0xFF2A2C31);
		b.box(x - 0.03f, y + 0.12f, z - 0.03f, x + 0.03f, y + 2.35f, z + 0.03f, 0xFFD6DAE0);
		float r = 1.5f, peak = y + 2.45f, rim = y + 2.05f;
		for (int i = 0; i < 8; i++) {
			double a1 = Math.PI * 2 * i / 8, a2 = Math.PI * 2 * (i + 1) / 8;
			float[] p1 = {x + (float) Math.sin(a1) * r, rim, z + (float) Math.cos(a1) * r}, p2 = {x + (float) Math.sin(a2) * r, rim, z + (float) Math.cos(a2) * r};
			b.quad(new float[]{x, peak, z}, p2, p1, p1, i % 2 == 0 ? 0xFFF7F4EE : 0xFFEDE8DE);
			b.quad(p1, p2, new float[]{p2[0], rim - 0.12f, p2[2]}, new float[]{p1[0], rim - 0.12f, p1[2]}, 0xFFFF77C8);
		}
	}

	/** A palm tree: a ringed trunk curving outward as it rises and thinning to the top, coconuts, and drooping fronds. */
	private static void palm(ShapeBuilder b, float x, float z, float leanX, float leanZ) {
		float len = (float) Math.sqrt(leanX * leanX + leanZ * leanZ);
		leanX /= len;
		leanZ /= len;
		int segments = 14;
		float h = 0.55f, cx = x, cz = z;
		for (int i = 0; i < segments; i++) {
			float t = i / (float) segments, r = 0.22f - 0.07f * t;
			float nx = x + leanX * 1.1f * t * t, nz = z + leanZ * 1.1f * t * t;
			b.box(nx - r, PLINTH + 0.013f + i * h, nz - r, nx + r, PLINTH + 0.013f + (i + 1) * h - 0.02f, nz + r, i % 2 == 0 ? 0xFF7A5B3C : 0xFF6C4F33);
			cx = nx;
			cz = nz;
		}
		float top = PLINTH + segments * h;
		for (int k = 0; k < 3; k++) {
			double a = Math.PI * 2 * k / 3;
			float kx = cx + (float) Math.sin(a) * 0.17f, kz = cz + (float) Math.cos(a) * 0.17f;
			b.box(kx - 0.09f, top - 0.32f, kz - 0.09f, kx + 0.09f, top - 0.14f, kz + 0.09f, 0xFF4A3622);
		}
		for (int k = 0; k < 9; k++) {
			double a = Math.PI * 2 * k / 9 + 0.3;
			float dx = (float) Math.sin(a), dz = (float) Math.cos(a), px = -dz, pz = dx;
			// Each frond rises from the crown then droops: two leaf panels along a bent spine
			float[] p0 = {cx, top, cz}, p1 = {cx + dx * 1.2f, top + 0.35f, cz + dz * 1.2f}, p2 = {cx + dx * 2.3f, top - 0.45f, cz + dz * 2.3f};
			int leaf = k % 2 == 0 ? 0xFF2F7A2C : 0xFF3A8A33;
			b.quad(p0, new float[]{p1[0] + px * 0.42f, p1[1], p1[2] + pz * 0.42f}, p1, new float[]{p1[0] - px * 0.42f, p1[1], p1[2] - pz * 0.42f}, leaf);
			b.quad(new float[]{p1[0] - px * 0.42f, p1[1], p1[2] - pz * 0.42f}, p1, new float[]{p1[0] + px * 0.42f, p1[1], p1[2] + pz * 0.42f}, p2, ShapeBuilder.shade(leaf, 0.9f));
		}
	}

	/** The house shell: walls (stopping under the slabs), the glass fronts, the slabs, the balcony, the terrace, railings and the roof. */
	private void drawHouse(ShapeBuilder b, ShapeBuilder glass, int wall, int stone, int wood) {
		int frame = 0xFF1D1F24, win = LarpLight.window(0x60101828);
		float top = F2 - SLAB, gz = FRONT - GLASS_DEPTH;
		// Ground floor: the back wall across with a strip of high windows over the kitchen and in the garage, the side walls
		// between it and the front (the garage's with windows too), a black corner post where each meets the glass
		wallWithWindows(b, glass, false, BACK, BACK + WALL, PLINTH, top, -HX, HX, PLINTH + 2.75f, top - 0.45f, new float[][]{{-12.5f, -0.5f}, {7, 13}}, wall, frame, win);
		b.box(-HX, PLINTH, BACK + WALL, -HX + WALL, top, gz, wall);
		wallWithWindows(b, glass, true, HX - WALL, HX, PLINTH, top, BACK + WALL, gz, PLINTH + 2.0f, PLINTH + 3.1f, new float[][]{{-11, -6.5f}, {-5, -0.5f}}, wall, frame, win);
		b.box(-HX, PLINTH, gz, -HX + WALL, top, FRONT, frame);
		// The garage: wood columns and lintel round its opening, and the wall between it and the house with a door
		b.box(GARAGE_X, PLINTH, gz, GARAGE_X + 1, top, FRONT, wood);
		b.box(HX - 1, PLINTH, gz, HX, top, FRONT, wood);
		b.box(GARAGE_X + 1, PLINTH + 3.4f, gz, HX - 1, top, FRONT, wood);
		b.box(GARAGE_X, PLINTH, BACK + WALL, GARAGE_X + 0.2f, top, -2, wall);
		b.box(GARAGE_X, PLINTH, 0, GARAGE_X + 0.2f, top, gz, wall);
		b.box(GARAGE_X, PLINTH + 2.6f, -2, GARAGE_X + 0.2f, top, 0, wall);
		// Glass fronts, both floors
		glassFront(b, glass, PLINTH, top, -HX + WALL, GARAGE_X, frame, win);
		glassFront(b, glass, F2, ROOF, -HX + WALL, GARAGE_X - WALL, frame, win);
		// The first floor slab, open over the stairs, edged with a band of stone; the balcony out front
		List<float[]> slabs = new ArrayList<>();
		slab(slabs, top, F2, -HX, BACK, HX, FRONT);
		for (float[] s : slabs) b.box(s[0], s[1], s[2], s[3], s[4], s[5], stone);
		b.box(-HX, top, FRONT, GARAGE_X, F2, FRONT + 2, stone);
		// Decking on the roof terrace, a hair above the slab
		for (float z = BACK + 0.2f; z < FRONT - 0.15f; z += 0.3f) b.box(GARAGE_X + 0.2f, F2 + 0.002f, z, HX - 0.15f, F2 + 0.014f, Math.min(FRONT - 0.15f, z + 0.27f), 0xFF8A6A4A);
		// Upstairs walls: a strip of windows high over the bed, and a tall window in the left end by the balcony
		wallWithWindows(b, glass, false, BACK, BACK + WALL, F2, ROOF, -HX, GARAGE_X, F2 + 1.9f, ROOF - 0.4f, new float[][]{{-13.2f, -4.6f}}, wall, frame, win);
		wallWithWindows(b, glass, true, -HX, -HX + WALL, F2, ROOF, BACK + WALL, gz, F2 + 0.6f, ROOF - 0.5f, new float[][]{{-3, 1}}, wall, frame, win);
		b.box(-HX, F2, gz, -HX + WALL, ROOF, FRONT, frame);
		b.box(GARAGE_X - WALL, F2, BACK + WALL, GARAGE_X, ROOF, -2, wall);
		b.box(GARAGE_X - WALL, F2, 0, GARAGE_X, ROOF, gz, wall);
		b.box(GARAGE_X - WALL, F2, gz, GARAGE_X, ROOF, FRONT, frame);
		b.box(GARAGE_X - WALL, F2 + 2.6f, -2, GARAGE_X, ROOF, 0, wall);
		glass.box(GARAGE_X - WALL + 0.06f, F2 + 0.01f, -1.99f, GARAGE_X - 0.06f, F2 + 2.59f, -0.01f, win);
		// Vertical wood slats up the left end, outside, with the wall showing between them; clear of the upstairs window
		for (float z = BACK + 1; z < FRONT - 1.2f; z += 0.32f) {
			boolean byWindow = z + 0.16f > -3.1f && z < 1.1f;
			b.box(-HX - 0.06f, PLINTH + 0.01f, z, -HX - 0.005f, byWindow ? F2 - 0.01f : ROOF - 0.01f, z + 0.16f, ShapeBuilder.shade(wood, 0.92f + 0.08f * ((int) (z * 3) % 2)));
		}
		// Glass railings round the balcony and the roof terrace
		railing(b, glass, -HX, FRONT + 2, GARAGE_X, FRONT + 2);
		railing(b, glass, -HX, FRONT, -HX, FRONT + 2);
		railing(b, glass, GARAGE_X, FRONT, GARAGE_X, FRONT + 2);
		railing(b, glass, HX, BACK, HX, FRONT);
		railing(b, glass, GARAGE_X, FRONT, HX, FRONT);
		railing(b, glass, GARAGE_X, BACK, HX, BACK);
		// The roof, overhanging, with a dark fascia
		b.box(-HX - 0.5f, ROOF, BACK - 0.5f, GARAGE_X + 0.5f, ROOF + 0.35f, FRONT + 0.5f, wall);
		b.box(-HX - 0.52f, ROOF + 0.02f, FRONT + 0.5f, GARAGE_X + 0.52f, ROOF + 0.33f, FRONT + 0.52f, frame);
		// On the roof terrace: two loungers, an outdoor sofa round a fire table, and a planter
		lounger(b, GARAGE_X + 2.2f, -11.5f, F2 + 0.015f);
		lounger(b, GARAGE_X + 3.3f, -11.5f, F2 + 0.015f);
		int frameDark = 0xFF2A2C31, seat = 0xFFD9D4CA;
		b.box(GARAGE_X + 3, F2 + 0.015f, 0.4f, HX - 0.7f, F2 + 0.42f, 1.3f, frameDark);
		b.box(GARAGE_X + 3.05f, F2 + 0.42f, 0.45f, HX - 0.75f, F2 + 0.55f, 1.25f, seat);
		b.box(GARAGE_X + 3, F2 + 0.42f, 1.0f, HX - 0.7f, F2 + 0.95f, 1.3f, seat);
		b.box(HX - 1.6f, F2 + 0.015f, -3.5f, HX - 0.7f, F2 + 0.42f, 0.4f, frameDark);
		b.box(HX - 1.55f, F2 + 0.42f, -3.45f, HX - 0.75f, F2 + 0.55f, 0.4f, seat);
		b.box(HX - 1.0f, F2 + 0.42f, -3.5f, HX - 0.7f, F2 + 0.95f, 1.0f, seat);
		b.box(GARAGE_X + 4, F2 + 0.015f, -2.6f, GARAGE_X + 6.4f, F2 + 0.45f, -0.6f, 0xFF4A4C52);
		b.box(GARAGE_X + 4.3f, F2 + 0.45f, -2.3f, GARAGE_X + 6.1f, F2 + 0.47f, -0.9f, 0xFF1A1B1F);
		b.box(HX - 1.2f, F2 + 0.015f, BACK + 0.5f, HX - 0.3f, F2 + 0.6f, BACK + 4, 0xFF4A4C52);
		b.box(HX - 1.15f, F2 + 0.6f, BACK + 0.55f, HX - 0.35f, F2 + 1.0f, BACK + 3.95f, 0xFF3A6B2F);
	}

	/**
	 * A wall with windows cut into it: solid from {@code y1} to {@code y2} across {@code a1..a2} (x, or
	 * z if {@code alongZ}) between {@code c1..c2} deep, except for each window span between
	 * {@code wy1..wy2}, which gets black frames and glass set into the middle of the wall.
	 */
	private static void wallWithWindows(ShapeBuilder b, ShapeBuilder glass, boolean alongZ, float c1, float c2, float y1, float y2, float a1, float a2,
										float wy1, float wy2, float[][] windows, int wall, int frame, int win) {
		// Below and above the window band, all the way across
		wallPiece(b, alongZ, c1, c2, y1, wy1, a1, a2, wall);
		wallPiece(b, alongZ, c1, c2, wy2, y2, a1, a2, wall);
		// Across the band: wall between the windows, frames and glass in them
		float from = a1;
		for (float[] w : windows) {
			if (w[0] > from) wallPiece(b, alongZ, c1, c2, wy1, wy2, from, w[0], wall);
			wallPiece(b, alongZ, c1, c2, wy1, wy1 + 0.06f, w[0], w[1], frame);
			wallPiece(b, alongZ, c1, c2, wy2 - 0.06f, wy2, w[0], w[1], frame);
			wallPiece(b, alongZ, c1, c2, wy1 + 0.06f, wy2 - 0.06f, w[0], w[0] + 0.06f, frame);
			wallPiece(b, alongZ, c1, c2, wy1 + 0.06f, wy2 - 0.06f, w[1] - 0.06f, w[1], frame);
			for (float m = w[0] + 1.5f; m < w[1] - 0.5f; m += 1.5f) wallPiece(b, alongZ, c1, c2, wy1 + 0.06f, wy2 - 0.06f, m, m + 0.05f, frame);
			float mid1 = c1 + (c2 - c1) * 0.4f, mid2 = c1 + (c2 - c1) * 0.6f;
			if (alongZ) glass.box(mid1, wy1 + 0.07f, w[0] + 0.07f, mid2, wy2 - 0.07f, w[1] - 0.07f, win);
			else glass.box(w[0] + 0.07f, wy1 + 0.07f, mid1, w[1] - 0.07f, wy2 - 0.07f, mid2, win);
			from = w[1];
		}
		if (from < a2) wallPiece(b, alongZ, c1, c2, wy1, wy2, from, a2, wall);
	}

	private static void wallPiece(ShapeBuilder b, boolean alongZ, float c1, float c2, float y1, float y2, float a1, float a2, int color) {
		if (alongZ) b.box(c1, y1, a1, c2, y2, a2, color);
		else b.box(a1, y1, c1, a2, y2, c2, color);
	}

	/**
	 * A floor's glass front between {@code y1} and {@code y2}, from {@code x1} to {@code x2}: panes in
	 * black frames, with a doorway in the middle. The glass sits just inside its frames so no faces meet.
	 */
	private static void glassFront(ShapeBuilder b, ShapeBuilder glass, float y1, float y2, float x1, float x2, int frame, int win) {
		float z1 = FRONT - GLASS_DEPTH, z2 = FRONT;
		b.box(x1, y1, z1, x2, y1 + 0.08f, z2, frame);
		b.box(x1, y2 - 0.08f, z1, x2, y2, z2, frame);
		List<Float> posts = new ArrayList<>();
		for (float x = x1; x < x2 - 0.5f; x += 2.2f) {
			if (x > -1.6f && x < 1.6f) continue;
			posts.add(x);
		}
		posts.add(x2 - 0.06f);
		posts.add(-1.26f);
		posts.add(1.2f);
		posts.sort(Float::compare);
		for (float x : posts) b.box(x, y1 + 0.08f, z1, x + 0.06f, y2 - 0.08f, z2, frame);
		for (int i = 0; i < posts.size() - 1; i++) {
			float a = posts.get(i) + 0.07f, c = posts.get(i + 1) - 0.01f;
			if (c - a < 0.1f || (a > -1.3f && c < 1.3f)) continue;
			glass.box(a, y1 + 0.09f, z1 + 0.06f, c, y2 - 0.09f, z2 - 0.06f, win);
		}
		// A lintel over the door, glass above it
		b.box(-1.2f, y1 + 2.6f, z1, 1.2f, y1 + 2.68f, z2, frame);
		glass.box(-1.19f, y1 + 2.69f, z1 + 0.06f, 1.19f, y2 - 0.09f, z2 - 0.06f, win);
	}

	/** A glass railing a metre high along an edge on the first floor, with a steel cap. */
	private static void railing(ShapeBuilder b, ShapeBuilder glass, float x1, float z1, float x2, float z2) {
		glass.quad(new float[]{x1, F2 + 0.02f, z1}, new float[]{x2, F2 + 0.02f, z2}, new float[]{x2, F2 + 0.95f, z2}, new float[]{x1, F2 + 0.95f, z1}, 0x40C8E0F0);
		float dx = x2 - x1, dz = z2 - z1, length = (float) Math.sqrt(dx * dx + dz * dz);
		if (length < 1e-3f) return;
		float nx = -dz / length * 0.04f, nz = dx / length * 0.04f;
		b.quad(new float[]{x1 - nx, F2 + 0.95f, z1 - nz}, new float[]{x2 - nx, F2 + 0.95f, z2 - nz}, new float[]{x2 + nx, F2 + 1.0f, z2 + nz}, new float[]{x1 + nx, F2 + 1.0f, z1 + nz}, 0xFFB9BEC6);
	}

	/** A box over a floor, with the stairs' hole cut out of it. */
	private static void aroundHole(ShapeBuilder in, float y1, float y2, float x1, float z1, float x2, float z2, int color) {
		in.box(x1, y1, z1, STAIR_X1, y2, z2, color);
		in.box(STAIR_X2, y1, z1, x2, y2, z2, color);
		in.box(STAIR_X1, y1, z1, STAIR_X2, y2, STAIR_Z, color);
		in.box(STAIR_X1, y1, HOLE_END, STAIR_X2, y2, z2, color);
	}

	/**
	 * Inside: wood floors and plain ceilings (cut round the stairs), the living room (sofa, rug,
	 * coffee table, TV, fireplace), the kitchen (an island with stools, units along the back), the
	 * dining table and chairs, floating stairs with a glass balustrade, and upstairs the master
	 * bedroom (bed, nightstands, wardrobe), a desk with two screens, art, plants and a sofa.
	 */
	private void drawInside(ShapeBuilder in, ShapeBuilder glass, int wood) {
		int sofa = 0xFFE9E3D6, dark = 0xFF2A2C31, marble = 0xFFF1EFEA, oak = 0xFF6E4A2E;
		float slabBottom = F2 - SLAB, gz = FRONT - GLASS_DEPTH, y = PLINTH + 0.016f, u = F2 + 0.016f;
		// Floors and ceilings, each a hair off the slab and cut round the stairs
		in.box(-HX + WALL, PLINTH + 0.001f, BACK + WALL, GARAGE_X, PLINTH + 0.015f, gz, wood);
		in.box(GARAGE_X + 0.2f, PLINTH + 0.001f, BACK + WALL, HX - WALL, PLINTH + 0.015f, gz, 0xFF4A4C52);
		aroundHole(in, F2 + 0.001f, F2 + 0.015f, -HX + WALL, BACK + WALL, GARAGE_X - WALL, gz, wood);
		aroundHole(in, slabBottom - 0.02f, slabBottom - 0.005f, -HX + WALL, BACK + WALL, GARAGE_X, gz, 0xFFF7F6F3);
		in.box(GARAGE_X + 0.2f, slabBottom - 0.02f, BACK + WALL, HX - WALL, slabBottom - 0.005f, gz, 0xFFF7F6F3);
		in.box(-HX + WALL, ROOF - 0.02f, BACK + WALL, GARAGE_X - WALL, ROOF - 0.005f, gz, 0xFFF7F6F3);
		// The rooms' own wall surfaces, a hair in from the walls: lit by the lamps at night while the outside darkens
		int plaster = 0xFFF4F2EE;
		float l = 0.006f, gTop = slabBottom - 0.02f;
		lining(in, false, BACK + WALL, BACK + WALL + l, PLINTH + 0.015f, gTop, -HX + WALL, GARAGE_X, PLINTH + 2.75f, slabBottom - 0.45f, new float[][]{{-12.5f, -0.5f}}, plaster);
		lining(in, true, -HX + WALL, -HX + WALL + l, PLINTH + 0.015f, gTop, BACK + WALL, gz, 0, 0, new float[0][], plaster);
		lining(in, true, GARAGE_X - l, GARAGE_X, PLINTH + 0.015f, gTop, BACK + WALL, gz, PLINTH + 0.015f, PLINTH + 2.6f, new float[][]{{-2, 0}}, plaster);
		float uTop = ROOF - 0.02f;
		lining(in, false, BACK + WALL, BACK + WALL + l, F2 + 0.015f, uTop, -HX + WALL, GARAGE_X - WALL, F2 + 1.9f, ROOF - 0.4f, new float[][]{{-13.2f, -4.6f}}, plaster);
		lining(in, true, -HX + WALL, -HX + WALL + l, F2 + 0.015f, uTop, BACK + WALL, gz, F2 + 0.6f, ROOF - 0.5f, new float[][]{{-3, 1}}, plaster);
		lining(in, true, GARAGE_X - WALL - l, GARAGE_X - WALL, F2 + 0.015f, uTop, BACK + WALL, gz, F2 + 0.015f, F2 + 2.6f, new float[][]{{-2, 0}}, plaster);
		// Living room: a rug, an L-shaped sofa with cushions, a low coffee table, the TV over the fireplace
		in.box(-9.5f, y, -2.5f, -3.5f, y + 0.01f, 2.5f, 0xFF9A8F80);
		in.box(-9, y + 0.01f, 1, -4, y + 0.42f, 2, sofa);
		in.box(-9, y + 0.42f, 1.75f, -4, y + 0.9f, 2, sofa);
		in.box(-9, y + 0.01f, -2, -8, y + 0.42f, 1, sofa);
		in.box(-9, y + 0.42f, -2, -8.75f, y + 0.9f, 1, sofa);
		for (float x = -7.8f; x < -4; x += 1.2f) in.box(x, y + 0.42f, 1.45f, x + 0.5f, y + 0.8f, 1.75f, 0xFFD6CFC0);
		in.box(-7, y + 0.01f, -1, -5, y + 0.36f, 0.2f, oak);
		in.box(-6.6f, y + 0.36f, -0.7f, -6.2f, y + 0.6f, -0.3f, 0xFF3A6B2F);
		in.box(-HX + WALL + 0.01f, PLINTH + 1.6f, -1.95f, -HX + WALL + 0.07f, PLINTH + 3.2f, 0.95f, 0xFF0B0C0F);
		in.box(-HX + WALL + 0.07f, PLINTH + 1.65f, -1.9f, -HX + WALL + 0.075f, PLINTH + 3.15f, 0.9f, 0xFF050507);
		in.box(-HX + WALL + 0.01f, y, -2, -HX + 0.85f, y + 0.9f, 1, 0xFF3A3B40);
		in.box(-HX + 0.85f, y + 0.9f, -2.2f, -HX + 1.0f, y + 1.0f, 1.2f, marble);
		in.box(-HX + 0.86f, y + 0.12f, -1.4f, -HX + 0.87f, y + 0.68f, 0.4f, 0xFF120E0A);
		// Kitchen: the island with stools, units with a splashback along the back wall, and pendant lights
		in.box(-6, y + 0.01f, -9.2f, 0, y + 0.9f, -7.8f, oak);
		in.box(-6.05f, y + 0.9f, -9.25f, 0.05f, y + 0.95f, -7.75f, marble);
		for (float x = -5; x <= -1; x += 1.3f) {
			in.box(x - 0.04f, y + 0.01f, -7.2f, x + 0.04f, y + 0.7f, -7.1f, dark);
			in.box(x - 0.22f, y + 0.7f, -7.36f, x + 0.22f, y + 0.78f, -6.94f, dark);
		}
		in.box(-8, y + 0.01f, BACK + WALL + 0.01f, 2, y + 0.9f, BACK + 0.9f, oak);
		in.box(-8, y + 0.9f, BACK + WALL + 0.01f, 2, y + 0.95f, BACK + 0.92f, marble);
		in.box(-8, y + 0.95f, BACK + WALL + 0.01f, 2, y + 1.6f, BACK + WALL + 0.03f, 0xFF3A3B40);
		in.box(-8, y + 1.9f, BACK + WALL + 0.01f, 2, y + 2.6f, BACK + 0.6f, oak);
		for (float x = -5; x <= -1; x += 2) {
			in.box(x - 0.01f, slabBottom - 1, -8.51f, x + 0.01f, slabBottom - 0.02f, -8.49f, dark);
			in.box(x - 0.15f, slabBottom - 1.15f, -8.65f, x + 0.15f, slabBottom - 1, -8.35f, 0xFFC9A14A);
		}
		// Dining: the table on four legs, six chairs with backs, a chandelier over it
		in.box(0.5f, y + 0.72f, -6, 3.5f, y + 0.78f, -2, oak);
		for (float[] leg : new float[][]{{0.7f, -5.8f}, {3.3f, -5.8f}, {0.7f, -2.2f}, {3.3f, -2.2f}}) in.box(leg[0] - 0.05f, y + 0.01f, leg[1] - 0.05f, leg[0] + 0.05f, y + 0.72f, leg[1] + 0.05f, dark);
		for (float z = -5.4f; z <= -2.4f; z += 1.5f) {
			chair(in, 0.0f, z, -1);
			chair(in, 4.0f, z, 1);
		}
		in.box(1.98f, slabBottom - 1.3f, -4.02f, 2.02f, slabBottom - 0.02f, -3.98f, 0xFFC9A14A);
		in.box(1.4f, slabBottom - 1.45f, -4.6f, 2.6f, slabBottom - 1.3f, -3.4f, 0xFFC9A14A);
		// The stairs: floating oak treads off the wall, a glass balustrade up the open side
		for (int i = 0; i < STEPS; i++) {
			float stepTop = PLINTH + (F2 - PLINTH) * (i + 1) / STEPS;
			in.box(STAIR_X1, stepTop - 0.08f, STAIR_Z + STAIR_RUN * i + 0.01f, STAIR_X2, stepTop, STAIR_Z + STAIR_RUN * (i + 1) - 0.01f, oak);
		}
		float rise = (F2 - PLINTH) / STEPS;
		glass.quad(new float[]{STAIR_X2 + 0.03f, PLINTH + rise, STAIR_Z}, new float[]{STAIR_X2 + 0.03f, F2, HOLE_END}, new float[]{STAIR_X2 + 0.03f, F2 + 0.95f, HOLE_END},
			new float[]{STAIR_X2 + 0.03f, PLINTH + rise + 0.95f, STAIR_Z}, 0x40C8E0F0);
		// Upstairs: glass round the stair hole
		railing(in, glass, STAIR_X2 + 0.04f, STAIR_Z, STAIR_X2 + 0.04f, HOLE_END - STAIR_RUN);
		railing(in, glass, STAIR_X1, STAIR_Z - 0.04f, STAIR_X2 + 0.04f, STAIR_Z - 0.04f);
		// The bed with a headboard, pillows and a throw; nightstands either side
		in.box(-10, u, -12.7f, -5, u + 0.55f, -8, 0xFFF7F4EE);
		in.box(-10.01f, u + 0.55f, -10.4f, -4.99f, u + 0.62f, -8, 0xFF3B4A6B);
		in.box(-10.2f, u, BACK + WALL + 0.01f, -4.8f, u + 1.5f, -12.6f, oak);
		in.box(-9.5f, u + 0.55f, -12.5f, -8, u + 0.8f, -11.9f, 0xFFFFFFFF);
		in.box(-7, u + 0.55f, -12.5f, -5.5f, u + 0.8f, -11.9f, 0xFFFFFFFF);
		for (float x : new float[]{-11, -4}) {
			in.box(x - 0.4f, u, -12.6f, x + 0.4f, u + 0.6f, -12, oak);
			in.box(x - 0.12f, u + 0.6f, -12.4f, x + 0.12f, u + 0.95f, -12.16f, 0xFFF4EBD8);
		}
		// The wardrobe along the wall by the terrace door, a desk with two screens, art on the back wall
		in.box(GARAGE_X - WALL - 0.65f, u, -11, GARAGE_X - WALL - 0.01f, u + 2.6f, -7, 0xFFE6E0D4);
		in.box(0, u, -12.6f, 3, u + 0.75f, -11.8f, oak);
		in.box(0.4f, u + 0.75f, -12.5f, 1.4f, u + 1.35f, -12.44f, 0xFF0B0C0F);
		in.box(1.6f, u + 0.75f, -12.5f, 2.6f, u + 1.35f, -12.44f, 0xFF0B0C0F);
		in.box(1.2f, u, -11.4f, 1.8f, u + 0.5f, -10.8f, dark);
		// Your Ledger on the desk, until the FBI bag it
		java.util.UUID deskOwner = dev.femboyware.FemboyWare.getModules().get(TvModule.class).drawingForOwner();
		if (!dev.femboyware.FemboyWare.getModules().get(FbiRaidModule.class).ledgerGone(deskOwner)) {
			drawLedger(in, FbiRaidModule.LEDGER_SPOT[0], u + 0.75f, FbiRaidModule.LEDGER_SPOT[2]);
		}
		int[] canvas = {0xFFE85D75, 0xFF4BA3C3, 0xFFF2C14E};
		for (int i = 0; i < 3; i++) {
			float x = -3.5f + i * 1.2f;
			in.box(x, u + 1.4f, BACK + WALL + 0.01f, x + 0.9f, u + 2.4f, BACK + WALL + 0.05f, 0xFF1D1F24);
			in.box(x + 0.06f, u + 1.46f, BACK + WALL + 0.05f, x + 0.84f, u + 2.34f, BACK + WALL + 0.06f, canvas[i]);
		}
		// A sofa facing the balcony doors
		in.box(-4, u, 0, 1, u + 0.42f, 1, sofa);
		in.box(-4, u + 0.42f, 0, 1, u + 0.9f, 0.25f, sofa);
		// Plants in the corners by the glass, both floors
		for (float[] p : new float[][]{{-HX + 0.8f, gz - 0.6f, y}, {GARAGE_X - 0.7f, gz - 0.6f, y}, {-HX + 0.8f, gz - 0.6f, u}, {GARAGE_X - WALL - 0.7f, gz - 0.6f, u}}) {
			in.box(p[0] - 0.25f, p[2], p[1] - 0.25f, p[0] + 0.25f, p[2] + 0.5f, p[1] + 0.25f, 0xFF2A2C31);
			in.box(p[0] - 0.35f, p[2] + 0.5f, p[1] - 0.35f, p[0] + 0.35f, p[2] + 1.3f, p[1] + 0.35f, 0xFF3A6B2F);
		}
	}

	/**
	 * The cash room's vault: steel walls round it, a heavy door frame on the doorway, and the vault
	 * door standing open into the room outside with its wheel and bolts. Its floor, shelves and pallet too.
	 */
	private void drawCashRoomShell(ShapeBuilder in, float u) {
		int steel = 0xFF50545C, dark = 0xFF2B2D33, chrome = 0xFFC3C7CE, shelf = 0xFF1C1D21;
		float top = ROOF - 0.02f, x2 = GARAGE_X - WALL - 0.01f, w = CASH_X1 + VAULT_WALL;
		// Walls
		in.box(CASH_X1, u, CASH_Z1, w, top, VAULT_DOOR_Z1, steel);
		in.box(CASH_X1, u, VAULT_DOOR_Z2, w, top, CASH_Z2, steel);
		in.box(CASH_X1, u + 2.3f, VAULT_DOOR_Z1, w, top, VAULT_DOOR_Z2, steel);
		in.box(w, u, CASH_Z1, x2, top, CASH_Z1 + VAULT_WALL, steel);
		in.box(w, u, CASH_Z2 - VAULT_WALL, x2, top, CASH_Z2, steel);
		// The door frame, thick round the opening on the outside
		in.box(CASH_X1 - 0.08f, u, VAULT_DOOR_Z1 - 0.14f, CASH_X1, u + 2.44f, VAULT_DOOR_Z1, dark);
		in.box(CASH_X1 - 0.08f, u, VAULT_DOOR_Z2, CASH_X1, u + 2.44f, VAULT_DOOR_Z2 + 0.14f, dark);
		in.box(CASH_X1 - 0.08f, u + 2.3f, VAULT_DOOR_Z1, CASH_X1, u + 2.44f, VAULT_DOOR_Z2, dark);
		// The vault door, swung open: a thick slab out from the hinge, the wheel on its face, bolts along its edge
		float dz1 = VAULT_DOOR_Z2 + 0.02f, dz2 = dz1 + 0.22f, dx1 = CASH_X1 - 1.28f, dx2 = CASH_X1 - 0.02f;
		in.box(dx1, u, dz1, dx2, u + 2.28f, dz2, steel);
		in.box(dx1 + 0.08f, u + 0.08f, dz2, dx2 - 0.08f, u + 2.2f, dz2 + 0.02f, ShapeBuilder.shade(steel, 1.1f));
		float cx = (dx1 + dx2) / 2, cy = u + 1.15f, fz = dz2 + 0.02f;
		in.box(cx - 0.06f, cy - 0.06f, fz, cx + 0.06f, cy + 0.06f, fz + 0.08f, chrome);
		for (int i = 0; i < 3; i++) {
			double a = Math.PI * i / 3;
			float ex = (float) Math.cos(a) * 0.32f, ey = (float) Math.sin(a) * 0.32f;
			float[][] bar = {{cx - ex - 0.015f, cy - ey, fz + 0.04f}, {cx + ex - 0.015f, cy + ey, fz + 0.04f}, {cx + ex + 0.015f, cy + ey, fz + 0.04f}, {cx - ex + 0.015f, cy - ey, fz + 0.04f}};
			in.plate(bar, 0, 0, 1, 0.03f, chrome);
			in.box(cx + ex - 0.03f, cy + ey - 0.03f, fz, cx + ex + 0.03f, cy + ey + 0.03f, fz + 0.07f, chrome);
			in.box(cx - ex - 0.03f, cy - ey - 0.03f, fz, cx - ex + 0.03f, cy - ey + 0.03f, fz + 0.07f, chrome);
		}
		for (float by = u + 0.35f; by < u + 2.1f; by += 0.42f) in.box(dx1 - 0.09f, by, dz1 + 0.06f, dx1, by + 0.09f, dz2 - 0.06f, chrome);
		// Inside: a dark floor, steel shelving along the back and right walls, the pallet's base, the counting table
		in.box(w, u, CASH_Z1 + VAULT_WALL, x2, u + 0.004f, CASH_Z2 - VAULT_WALL, 0xFF1B1B1E);
		float bz1 = CASH_Z1 + VAULT_WALL, bz2 = bz1 + 0.5f;
		for (float sx : new float[]{w + 0.05f, 2.75f, 4.2f, x2 - 0.05f}) in.box(sx - 0.025f, u, bz1, sx + 0.025f, u + 2.5f, bz2, shelf);
		float rx1 = x2 - 0.5f, rz2 = CASH_Z2 - VAULT_WALL;
		for (float sz : new float[]{bz2 + 0.03f, -4.6f, rz2 - 0.05f}) in.box(rx1, u, sz - 0.025f, x2, u + 2.5f, sz + 0.025f, shelf);
		for (float sy : SHELVES) {
			in.box(w + 0.02f, u + sy - 0.03f, bz1, x2, u + sy, bz2, shelf);
			in.box(rx1, u + sy - 0.03f, bz2, x2, u + sy, rz2, shelf);
		}
		in.box(2.4f, u, -5.6f, 3.8f, u + 0.14f, -4.4f, 0xFF9C7A50);
		in.box(1.35f, u + 0.76f, -3.35f, 2.35f, u + 0.8f, -2.75f, 0xFF16171A);
		for (float lx : new float[]{1.4f, 2.3f}) in.box(lx - 0.02f, u, -3.1f, lx + 0.02f, u + 0.76f, -3.0f, 0xFF8C9098);
	}

	/** A Ledger lying flat, its bottom at {@code y}: the black body, the steel swivel cover and the little screen. */
	static void drawLedger(ShapeBuilder b, float x, float y, float z) {
		b.box(x - 0.17f, y, z - 0.045f, x + 0.17f, y + 0.035f, z + 0.045f, 0xFF15161A);
		b.box(x - 0.1f, y + 0.035f, z - 0.048f, x + 0.19f, y + 0.042f, z + 0.048f, 0xFFB9BBC2);
		b.box(x - 0.16f, y + 0.035f, z - 0.03f, x - 0.11f, y + 0.039f, z + 0.03f, 0xFF262A30);
		b.box(x - 0.08f, y + 0.042f, z - 0.025f, x + 0.04f, y + 0.044f, z + 0.025f, 0xFF7FB2E8);
	}

	/** The heights of the cash room's shelf boards, from the floor. */
	private static final float[] SHELVES = {0.06f, 0.62f, 1.18f, 1.74f, 2.3f};

	/**
	 * Cash everywhere: the cash room's shelves full of bricks, a shrink-wrapped pallet and a counting
	 * machine running, and stacks and loose bills left on the coffee table, the kitchen island, the
	 * dining table, the bed, the nightstand, the desk and the sofa. Gone once the FBI's seized it.
	 */
	private void drawCash(ShapeBuilder in, ShapeBuilder glass, float y, float u, float seconds) {
		java.util.UUID owner = dev.femboyware.FemboyWare.getModules().get(TvModule.class).drawingForOwner();
		boolean seized = dev.femboyware.FemboyWare.getModules().get(FbiRaidModule.class).watchesTaken(owner) >= WATCH_COUNT;
		float x2 = GARAGE_X - WALL - 0.01f, bz1 = CASH_Z1 + VAULT_WALL, w = CASH_X1 + VAULT_WALL;
		// The counting machines: one on the dining table, one in the cash room (both running unless it's all gone)
		Cash.counter(in, 2.0f, y + 0.78f, -4.6f, 1, !seized, seconds);
		Cash.counter(in, 1.85f, u + 0.8f, -3.05f, -1, !seized, seconds + 3);
		if (seized) return;
		// Back wall shelves: each bay a row of bricks five high at the front, packed solid behind
		float[] bays = {w + 0.08f, 2.78f, 4.23f};
		float[] bayEnds = {2.72f, 4.17f, x2 - 0.08f};
		for (int s = 0; s < SHELVES.length; s++) {
			int layers = s == SHELVES.length - 1 ? 2 : 5;
			for (int bay = 0; bay < 3; bay++) {
				int across = (int) ((bayEnds[bay] - bays[bay]) / (Cash.LENGTH + 0.01f));
				in.box(bays[bay] + 0.02f, u + SHELVES[s], bz1 + 0.02f, bays[bay] + across * (Cash.LENGTH + 0.01f) - 0.02f, u + SHELVES[s] + Cash.height(layers) - 0.01f, bz1 + 0.33f, 0xFF8E9E7E);
				Cash.stack(in, bays[bay], u + SHELVES[s], bz1 + 0.34f, across, 1, layers, true);
			}
		}
		// Right wall shelves: bricks end on, two bays
		float rx1 = x2 - 0.5f;
		float[][] rbays = {{bz1 + 0.56f, -4.63f}, {-4.57f, CASH_Z2 - VAULT_WALL - 0.08f}};
		for (int s = 0; s < SHELVES.length; s++) {
			int layers = s == SHELVES.length - 1 ? 2 : 5;
			for (float[] rb : rbays) {
				int deep = (int) ((rb[1] - rb[0]) / (Cash.WIDTH + 0.01f));
				in.box(rx1 + 0.33f, u + SHELVES[s], rb[0] + 0.02f, x2 - 0.02f, u + SHELVES[s] + Cash.height(layers) - 0.01f, rb[0] + deep * (Cash.WIDTH + 0.01f) - 0.02f, 0xFF8E9E7E);
				Cash.stack(in, rx1 + 0.01f, u + SHELVES[s], rb[0], 1, deep, layers, true);
			}
		}
		// The pallet: a block of bricks, its outside and top drawn brick by brick, wrapped in film
		float px = 2.45f, pz = -5.55f, py = u + 0.14f;
		int across = 4, deep = 7, layers = 7;
		float sx = Cash.LENGTH + 0.01f, sz = Cash.WIDTH + 0.01f;
		in.box(px + 0.03f, py, pz + 0.03f, px + across * sx - 0.03f, py + Cash.height(layers) - 0.01f, pz + deep * sz - 0.03f, 0xFF8E9E7E);
		for (int l = 0; l < layers; l++) {
			float ly = py + Cash.height(l);
			boolean top = l == layers - 1;
			for (int i = 0; i < across; i++) {
				for (int j = 0; j < deep; j++) {
					if (!top && i > 0 && i < across - 1 && j > 0 && j < deep - 1) continue;
					Cash.brick(in, px + sx * (i + 0.5f), ly, pz + sz * (j + 0.5f), true);
				}
			}
		}
		glass.box(px - 0.01f, py, pz - 0.01f, px + across * sx + 0.01f, py + Cash.height(layers) + 0.01f, pz + deep * sz + 0.01f, 0x28E8F0F8);
		// Loose bills on the counting table and spilled on the vault floor
		Cash.stack(in, 2.05f, u + 0.8f, -3.3f, 1, 2, 3, false);
		Cash.loose(in, 3.2f, u + 0.005f, -3.6f, 14, 1.2f, 11);
		// Round the house
		Cash.stack(in, -6.9f, y + 0.36f, -0.95f, 2, 1, 2, true);
		Cash.loose(in, -5.6f, y + 0.36f, -0.4f, 6, 0.5f, 1);
		in.box(-3.3f, y + 0.01f, -1.05f, -2.55f, y + 0.3f, -0.6f, 0xFF1A1A1C);
		Cash.stack(in, -3.25f, y + 0.3f, -1.0f, 2, 2, 1, true);
		Cash.loose(in, -4.2f, y + 0.012f, -1.6f, 9, 0.9f, 7);
		Cash.stack(in, -5.6f, y + 0.95f, -8.95f, 2, 2, 3, true);
		Cash.stack(in, -1.4f, y + 0.95f, -8.85f, 1, 2, 2, true);
		Cash.loose(in, -3.5f, y + 0.95f, -8.5f, 6, 0.8f, 2);
		Cash.stack(in, 0.9f, y + 0.78f, -3.4f, 2, 2, 4, false);
		Cash.loose(in, 2.8f, y + 0.78f, -2.9f, 8, 0.7f, 3);
		Cash.stack(in, -8.7f, u + 0.62f, -9.7f, 2, 2, 2, true);
		Cash.loose(in, -6.8f, u + 0.62f, -9.2f, 12, 1.2f, 4);
		Cash.brick(in, -4.25f, u + 0.6f, -12.3f, false);
		Cash.stack(in, 2.65f, u + 0.75f, -12.35f, 1, 1, 4, false);
		Cash.loose(in, 1.0f, u + 0.75f, -12.0f, 4, 0.4f, 5);
		Cash.stack(in, -1.6f, u + 0.42f, 0.35f, 2, 1, 1, true);
		Cash.loose(in, 0.0f, u + 0.42f, 0.6f, 3, 0.4f, 6);
	}

	/** Lights and twinkles from the watch room to draw with the others: x, y, z, size, colour (as float bits), and a twinkle phase for diamonds. */
	private final List<float[]> watchLights = new ArrayList<>();
	private static final float WATCH_MINUTES = 10 * 60 + 10;
	/** Watches in the room: three rows of eight in the winder wall, and two vitrines of two rows of nine. */
	public static final int WATCH_COUNT = 3 * 8 + 2 * 2 * 9;

	/**
	 * The watch room upstairs at the front: a dark floor, a walnut wall of lit winder cubbies with a
	 * watch standing in each, and two black lacquer vitrines with glass tops, watches laid out on
	 * velvet under spotlights. Rolexes and Audemars Piguets, a few iced out.
	 */
	private void drawWatchRoom(ShapeBuilder in, ShapeBuilder glass, float u) {
		int walnut = 0xFF3B2416, lacquer = 0xFF101012, gold = 0xFFC9A14A, velvet = 0xFF2B1220, pillow = 0xFFEDE6DA;
		int next = 0;
		// Bagged by the FBI in a raid (or gone while your assets are frozen): those cases stay empty
		java.util.UUID owner = dev.femboyware.FemboyWare.getModules().get(TvModule.class).drawingForOwner();
		FbiRaidModule raid = dev.femboyware.FemboyWare.getModules().get(FbiRaidModule.class);
		in.box(-13.4f, u + 0.001f, -3.9f, -6.1f, u + 0.006f, 3.0f, 0xFF1B1B1E);
		// The winder wall: a walnut partition, drawers below, three rows of lit cubbies above
		in.box(-11.3f, u, -4.25f, -6.1f, u + 2.55f, -3.95f, walnut);
		in.box(-11.3f, u + 2.55f, -4.3f, -6.1f, u + 2.6f, -3.9f, gold);
		for (float x = -11.0f; x < -6.3f; x += 1.15f) in.box(x, u + 0.12f, -3.95f, x + 1.0f, u + 0.68f, -3.93f, ShapeBuilder.shade(walnut, 1.2f));
		for (int row = 0; row < 3; row++) {
			float cy = u + 1.05f + row * 0.5f;
			for (int col = 0; col < 8; col++) {
				float cx = -10.85f + col * 0.6f;
				in.box(cx - 0.25f, cy - 0.21f, -3.95f, cx + 0.25f, cy + 0.21f, -3.935f, gold);
				in.box(cx - 0.23f, cy - 0.19f, -3.935f, cx + 0.23f, cy + 0.19f, -3.93f, 0xFF0E0E10);
				in.box(cx - 0.07f, cy - 0.16f, -3.93f, cx + 0.07f, cy + 0.08f, -3.86f, pillow);
				if (raid.watchGone(owner, next)) {
					next++;
					continue;
				}
				Watches.Style style = Watches.COLLECTION.get(next++ % Watches.COLLECTION.size());
				float[] at = {cx, cy - 0.02f, -3.84f};
				Watches.draw(in, at, new float[]{1, 0, 0}, new float[]{0, 1, 0}, 0.14f, style, WATCH_MINUTES);
				watchLights.add(new float[]{cx, cy + 0.16f, -3.9f, 0.16f, Float.intBitsToFloat(0x50FFE8C0)});
				for (float[] p : Watches.sparkles(at, new float[]{1, 0, 0}, new float[]{0, 1, 0}, 0.14f, style))
					watchLights.add(new float[]{p[0], p[1], p[2], 0.07f, Float.intBitsToFloat(0xF0FFFFFF), (float) Math.random()});
			}
		}
		// Two vitrines either side of the walkway, watches laid out facing whoever's in between
		for (int side = 0; side < 2; side++) {
			float z1 = side == 0 ? -2.6f : 0.6f, z2 = z1 + 0.8f, top = u + 0.86f;
			// The lacquer stops under the gold rim, so their tops don't fight over the same plane
			in.box(-12.9f, u, z1, -7.1f, top - 0.03f, z2, lacquer);
			in.box(-12.92f, top - 0.03f, z1 - 0.02f, -7.08f, top, z2 + 0.02f, gold);
			in.box(-12.85f, top + 0.002f, z1 + 0.05f, -7.15f, top + 0.01f, z2 - 0.05f, velvet);
			// The glass: its four sides, and the lid, which lifts up on chrome posts when the FBI open it
			float lid = raid.caseLid(owner, side), g1 = top + 0.3f, lift = 0.45f * lid, pane = 0.012f;
			int glassTint = 0x30C8E0F0, chrome = 0xFFD6D9DE;
			glass.box(-12.9f, top + 0.01f, z1, -7.1f, g1, z1 + pane, glassTint);
			glass.box(-12.9f, top + 0.01f, z2 - pane, -7.1f, g1, z2, glassTint);
			glass.box(-12.9f, top + 0.01f, z1 + pane, -12.9f + pane, g1, z2 - pane, glassTint);
			glass.box(-7.1f - pane, top + 0.01f, z1 + pane, -7.1f, g1, z2 - pane, glassTint);
			glass.box(-12.9f, g1 + lift - 0.015f, z1, -7.1f, g1 + lift, z2, glassTint);
			if (lift > 0.01f) {
				for (float px : new float[]{-12.88f, -7.14f}) for (float pz : new float[]{z1 + 0.02f, z2 - 0.04f})
					in.box(px, g1, pz, px + 0.02f, g1 + lift, pz + 0.02f, chrome);
				// A thin gold rim round the lifted glass
				float ry = g1 + lift - 0.02f, rt = g1 + lift - 0.012f, rw = 0.025f;
				in.box(-12.9f, ry, z1, -7.1f, rt, z1 + rw, gold);
				in.box(-12.9f, ry, z2 - rw, -7.1f, rt, z2, gold);
				in.box(-12.9f, ry, z1 + rw, -12.9f + rw, rt, z2 - rw, gold);
				in.box(-7.1f - rw, ry, z1 + rw, -7.1f, rt, z2 - rw, gold);
			}
			float[] right = side == 0 ? new float[]{1, 0, 0} : new float[]{-1, 0, 0};
			float[] up = side == 0 ? new float[]{0, 0, -1} : new float[]{0, 0, 1};
			for (int row = 0; row < 2; row++) {
				float cz = z1 + 0.22f + row * 0.36f;
				for (int col = 0; col < 9; col++) {
					float cx = -12.55f + col * 0.64f;
					if (raid.watchGone(owner, next)) {
						next++;
						continue;
					}
					Watches.Style style = Watches.COLLECTION.get(next++ % Watches.COLLECTION.size());
					float[] at = {cx, top + 0.035f, cz};
					Watches.draw(in, at, right, up, 0.16f, style, WATCH_MINUTES);
					for (float[] p : Watches.sparkles(at, right, up, 0.16f, style))
						watchLights.add(new float[]{p[0], p[1], p[2], 0.08f, Float.intBitsToFloat(0xF0FFFFFF), (float) Math.random()});
				}
			}
			for (float x = -12.3f; x < -7.2f; x += 1.3f) watchLights.add(new float[]{x, top + 0.28f, (z1 + z2) / 2, 0.35f, Float.intBitsToFloat(0x40FFF0D8)});
		}
	}

	/**
	 * A wall's inside surface: a thin layer over it from {@code y1} to {@code y2}, with the window (or
	 * door) spans between {@code wy1..wy2} left open.
	 */
	private static void lining(ShapeBuilder in, boolean alongZ, float c1, float c2, float y1, float y2, float a1, float a2, float wy1, float wy2, float[][] openings, int color) {
		if (openings.length == 0) {
			wallPiece(in, alongZ, c1, c2, y1, y2, a1, a2, color);
			return;
		}
		if (wy1 > y1) wallPiece(in, alongZ, c1, c2, y1, wy1, a1, a2, color);
		if (wy2 < y2) wallPiece(in, alongZ, c1, c2, wy2, y2, a1, a2, color);
		float from = a1;
		for (float[] o : openings) {
			if (o[0] > from) wallPiece(in, alongZ, c1, c2, wy1, wy2, from, o[0], color);
			from = o[1];
		}
		if (from < a2) wallPiece(in, alongZ, c1, c2, wy1, wy2, from, a2, color);
	}

	/** A dining chair: four legs, a seat and a back, facing {@code facing} (-1 toward +x, 1 toward -x). */
	private static void chair(ShapeBuilder in, float x, float z, int facing) {
		float y = PLINTH + 0.016f;
		for (float dx : new float[]{-0.2f, 0.2f}) {
			for (float dz : new float[]{-0.2f, 0.2f}) in.box(x + dx - 0.025f, y + 0.01f, z + dz - 0.025f, x + dx + 0.025f, y + 0.45f, z + dz + 0.025f, 0xFF2A2C31);
		}
		in.box(x - 0.24f, y + 0.45f, z - 0.24f, x + 0.24f, y + 0.52f, z + 0.24f, 0xFFE9E3D6);
		float back = facing < 0 ? x - 0.24f : x + 0.18f;
		in.box(back, y + 0.52f, z - 0.24f, back + 0.06f, y + 1.05f, z + 0.24f, 0xFFE9E3D6);
	}
}
