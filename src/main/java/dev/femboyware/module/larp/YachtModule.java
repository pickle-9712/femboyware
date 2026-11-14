package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
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
 * A superyacht you can walk round: boarding stairs up from the waterline and a swim platform at
 * the stern; a teak main deck with railings; a salon with sofas and a bar; stairs from the
 * foredeck up to the upper deck and its bridge; and a helipad out over the stern, where your
 * helicopter's parked. Its decks, walls and railings are solid for you while larping, and for fake
 * players, valets and dropped items always.
 */
public class YachtModule extends Module {
	private final ColorSetting hull = add(new ColorSetting("Hull", "The hull and the superstructure", 0xFFF5F6F8));
	private final ColorSetting stripe = add(new ColorSetting("Stripe", "The stripe along the waterline and the window bands", 0xFF1B2A4A));
	private final ColorSetting deck = add(new ColorSetting("Deck", "The teak decks", 0xFFB07A48));
	private final BoolSetting underwaterLights = add(new BoolSetting("Underwater Lights", "Blue lights glowing round the hull at the waterline", true));
	private final BoolSetting bringHelicopter = add(new BoolSetting("Helicopter", "Park your helicopter on the helipad when the yacht arrives", true));

	/** Its own space: x across (+x is the left side, looking forward), y up, z forward. The waterline is y = 0. */
	private static final float LENGTH = 24, BEAM = 6, MAIN = 2.6f, BULWARK = 1.0f, UPPER = 5.7f, BRIDGE_TOP = 8.6f;
	private static final float SALON_BACK = -10, SALON_FRONT = 10, SALON_HALF = 4.6f, UPPER_HALF = 5.2f, UPPER_FRONT = 12;
	private static final float PAD_Z = -15, PAD_RADIUS = 6;
	/** The helipad's surface: a step above the upper deck, so the two never lie flat on each other. */
	private static final float PAD_TOP = UPPER + 0.06f;
	/** The foredeck stairs: steps going up aft from here, each this long. */
	private static final int STAIR_STEPS = 9;
	private static final float STAIR_START = 18.3f, STAIR_RUN = 0.7f, STAIR_HALF = 1.2f;
	/** The boarding stairs up the right side from the waterline. */
	private static final int BOARDING_STEPS = 7;

	private Vec3 at;
	private float yaw;
	/** Someone else's colours (hull, stripe, deck) while their yacht's drawn, or null for yours. */
	private int[] look;

	public YachtModule() {
		super("Yacht", "A superyacht with a salon, upper deck, bridge and helipad you can walk round. Use " + CommandManager.PREFIX + "yacht", Category.LARP);
	}

	@Override
	public String getInfo() {
		return at != null ? "Moored" : null;
	}

	/** How wide the hull is (half) at {@code z}: full beam aft, rounding off a little at the stern, tapering to the bow. */
	private static float halfWidth(float z) {
		if (z > 8) {
			float t = Math.min(1, (z - 8) / (LENGTH - 8));
			return BEAM * (float) Math.sqrt(Math.max(0, 1 - t * t));
		}
		if (z < -LENGTH + 2) return BEAM - 0.6f * (-LENGTH + 2 - z) / 2;
		return BEAM;
	}

	private Quaternionf turn() {
		return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
	}

	private Vec3 world(float x, float y, float z) {
		Vector3f v = new Vector3f(x, y, z).rotate(turn());
		return at.add(v.x, v.y, v.z);
	}

	// ---------------------------------------------------------------- commands

	/** Handles {@code .yacht [spawn|remove]}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "spawn";
		switch (action) {
			case "spawn", "park", "moor" -> {
				if (mc.player == null) return;
				// Squared to a compass direction so its boxes fit it, moored on your left with its boarding stairs by you
				yaw = Math.round(mc.player.getYRot() / 90f) * 90f;
				Vector3f offset = new Vector3f(BEAM + 4.5f, 0, 5).rotate(new Quaternionf().rotateY((float) Math.toRadians(-yaw)));
				at = mc.player.position().add(offset.x, 0, offset.z);
				// Spawned in or under water (or lava): up to the surface, so it floats
				at = new Vec3(at.x, surfaced(), at.z);
				if (!isEnabled()) setEnabled(true);
				if (bringHelicopter.get()) FemboyWare.getModules().get(HelicopterModule.class).parkAt(world(0, PAD_TOP, PAD_Z), yaw);
				dev.femboyware.util.Mc.addChat(net.minecraft.network.chat.Component.literal("<Captain> The yacht's moored and ready for you. Come aboard whenever you like."));
			}
			case "remove", "clear" -> {
				clearYacht();
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "yacht <spawn|remove>");
		}
	}

	/**
	 * Where the waterline should be: if any of the hull's length is in water (or any liquid), the top
	 * of it there, so the yacht floats on the surface rather than sitting submerged; otherwise where
	 * you're standing.
	 */
	private double surfaced() {
		double best = at.y;
		for (float z = -LENGTH + 2; z <= LENGTH - 2; z += 4) {
			Vec3 point = world(0, 0, z);
			net.minecraft.core.BlockPos.MutableBlockPos pos = net.minecraft.core.BlockPos.containing(point.x, at.y - 1, point.z).mutable();
			// Down a little first, in case you're stood just above the water
			int below = 0;
			while (below < 3 && mc.level.getFluidState(pos).isEmpty()) {
				pos.move(0, 1, 0);
				below++;
			}
			if (mc.level.getFluidState(pos).isEmpty()) continue;
			// Then up through it to the top
			int climbed = 0;
			while (climbed < 256 && !mc.level.getFluidState(pos.above()).isEmpty()) {
				pos.move(0, 1, 0);
				climbed++;
			}
			float height = mc.level.getFluidState(pos).getHeight(mc.level, pos);
			best = Math.max(best, pos.getY() + height);
		}
		return best;
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

	private void clearYacht() {
		if (at != null && bringHelicopter.get()) {
			HelicopterModule heli = FemboyWare.getModules().get(HelicopterModule.class);
			// Only take the helicopter if it's still sat on our pad
			double[] s = heli.shareState();
			if (s != null && new Vec3(s[7], s[8], s[9]).distanceToSqr(world(0, PAD_TOP, PAD_Z)) < 1) heli.clear();
		}
		at = null;
	}

	@Override
	protected void onDisable() {
		if (at != null) clearYacht();
	}

	// ---------------------------------------------------------------- collision

	/** Every solid box, in its own space: x1, y1, z1, x2, y2, z2. */
	private static List<float[]> boxes() {
		List<float[]> boxes = new ArrayList<>();
		// The hull, solid up to the main deck, a slice per block along it
		for (float z = -LENGTH; z < LENGTH; z += 1) {
			float w = Math.min(halfWidth(z), halfWidth(z + 1));
			if (w < 0.3f) continue;
			boxes.add(new float[]{-w, -0.5f, z, w, MAIN, z + 1});
			// Bulwarks: the railings along both sides, open for the boarding stairs on the right
			boolean boarding = z >= -9 && z < -6;
			boxes.add(new float[]{w - 0.15f, MAIN, z, w, MAIN + BULWARK, z + 1});
			if (!boarding) boxes.add(new float[]{-w, MAIN, z, -w + 0.15f, MAIN + BULWARK, z + 1});
		}
		// Across the stern, open in the middle down to the swim platform
		boxes.add(new float[]{-BEAM + 0.6f, MAIN, -LENGTH, -1.2f, MAIN + BULWARK, -LENGTH + 0.15f});
		boxes.add(new float[]{1.2f, MAIN, -LENGTH, BEAM - 0.6f, MAIN + BULWARK, -LENGTH + 0.15f});
		// The swim platform, and steps from it up to the deck through the gap
		boxes.add(new float[]{-5, 0.3f, -LENGTH - 1.4f, 5, 0.55f, -LENGTH});
		// Each step runs to the transom, so together they climb toward it
		for (int i = 0; i < 6; i++) {
			float top = 0.55f + (MAIN - 0.55f) * (i + 1) / 6f;
			boxes.add(new float[]{-1.2f, 0.55f, -LENGTH - 1.4f + 0.233f * i, 1.2f, top, -LENGTH});
		}
		// The boarding stairs up the right side from the waterline
		for (int i = 0; i < BOARDING_STEPS; i++) {
			float top = MAIN * (i + 1) / BOARDING_STEPS;
			float z1 = -2 - i, z2 = -1 - i;
			boxes.add(new float[]{-BEAM - 1.3f, 0, Math.min(z1, z2), -BEAM, top, Math.max(z1, z2)});
			boxes.add(new float[]{-BEAM - 1.42f, top, Math.min(z1, z2), -BEAM - 1.3f, top + 1, Math.max(z1, z2)});
		}
		// The salon: walls with a doorway at each end
		float sh = SALON_HALF, top = UPPER - 0.2f;
		boxes.add(new float[]{-sh, MAIN, SALON_BACK, -1.2f, top, SALON_BACK + 0.2f});
		boxes.add(new float[]{1.2f, MAIN, SALON_BACK, sh, top, SALON_BACK + 0.2f});
		boxes.add(new float[]{-1.2f, MAIN + 2.3f, SALON_BACK, 1.2f, top, SALON_BACK + 0.2f});
		boxes.add(new float[]{-sh, MAIN, SALON_FRONT - 0.2f, -1.2f, top, SALON_FRONT});
		boxes.add(new float[]{1.2f, MAIN, SALON_FRONT - 0.2f, sh, top, SALON_FRONT});
		boxes.add(new float[]{-1.2f, MAIN + 2.3f, SALON_FRONT - 0.2f, 1.2f, top, SALON_FRONT});
		boxes.add(new float[]{-sh, MAIN, SALON_BACK, -sh + 0.2f, top, SALON_FRONT});
		boxes.add(new float[]{sh - 0.2f, MAIN, SALON_BACK, sh, top, SALON_FRONT});
		// The bar along the back of the salon
		boxes.add(new float[]{-3.8f, MAIN, SALON_FRONT - 1.2f, 3.8f, MAIN + 1.1f, SALON_FRONT - 0.2f});
		// The upper deck over it, and its railings (open at the front for the stairs, and at the back onto the helipad)
		float uh = UPPER_HALF;
		boxes.add(new float[]{-uh, UPPER - 0.2f, SALON_BACK, uh, UPPER, UPPER_FRONT});
		boxes.add(new float[]{-uh, UPPER, SALON_BACK, -uh + 0.12f, UPPER + 1, UPPER_FRONT});
		boxes.add(new float[]{uh - 0.12f, UPPER, SALON_BACK, uh, UPPER + 1, UPPER_FRONT});
		boxes.add(new float[]{-uh, UPPER, UPPER_FRONT - 0.12f, -STAIR_HALF, UPPER + 1, UPPER_FRONT});
		boxes.add(new float[]{STAIR_HALF, UPPER, UPPER_FRONT - 0.12f, uh, UPPER + 1, UPPER_FRONT});
		float join = padJoin();
		boxes.add(new float[]{-uh, UPPER, SALON_BACK, -join, UPPER + 1, SALON_BACK + 0.12f});
		boxes.add(new float[]{join, UPPER, SALON_BACK, uh, UPPER + 1, SALON_BACK + 0.12f});
		// Stairs up from the foredeck, climbing aft to the upper deck's front edge
		for (int i = 0; i < STAIR_STEPS; i++) {
			float stepTop = MAIN + (UPPER - MAIN) * (i + 1) / STAIR_STEPS;
			float z2 = STAIR_START - STAIR_RUN * i, z1 = z2 - STAIR_RUN;
			boxes.add(new float[]{-STAIR_HALF, MAIN, z1, STAIR_HALF, stepTop, z2});
			// The glass sides: you can't step off the stairs
			boxes.add(new float[]{-STAIR_HALF - 0.1f, stepTop, z1, -STAIR_HALF, stepTop + 1, z2});
			boxes.add(new float[]{STAIR_HALF, stepTop, z1, STAIR_HALF + 0.1f, stepTop + 1, z2});
		}
		// The bridge: walls with a door at the back, and its roof
		float bh = 3.4f;
		boxes.add(new float[]{-bh, UPPER, 2, -1, BRIDGE_TOP, 2.2f});
		boxes.add(new float[]{1, UPPER, 2, bh, BRIDGE_TOP, 2.2f});
		boxes.add(new float[]{-1, UPPER + 2.3f, 2, 1, BRIDGE_TOP, 2.2f});
		boxes.add(new float[]{-bh, UPPER, 9.8f, bh, BRIDGE_TOP, 10});
		boxes.add(new float[]{-bh, UPPER, 2, -bh + 0.2f, BRIDGE_TOP, 10});
		boxes.add(new float[]{bh - 0.2f, UPPER, 2, bh, BRIDGE_TOP, 10});
		boxes.add(new float[]{-bh - 0.2f, BRIDGE_TOP, 1.8f, bh + 0.2f, BRIDGE_TOP + 0.2f, 10.2f});
		// The helm console inside
		boxes.add(new float[]{-2.6f, UPPER, 8.6f, 2.6f, UPPER + 1.1f, 9.8f});
		// The helipad: a round deck out over the stern, as strips, with a railing round all but where it joins the deck
		for (float x = -PAD_RADIUS; x < PAD_RADIUS; x += 1) {
			float mid = x + 0.5f, half = (float) Math.sqrt(Math.max(0, PAD_RADIUS * PAD_RADIUS - mid * mid));
			boxes.add(new float[]{x, UPPER - 0.2f, PAD_Z - half, x + 1, PAD_TOP, Math.min(SALON_BACK + 0.5f, PAD_Z + half)});
		}
		int segments = 28;
		for (int i = 0; i < segments; i++) {
			double a1 = Math.PI * 2 * i / segments, a2 = Math.PI * 2 * (i + 1) / segments, mid = (a1 + a2) / 2;
			// The front of the pad meets the upper deck: no railing there
			if (Math.cos(mid) > 0.62) continue;
			float x1 = (float) Math.sin(a1) * PAD_RADIUS, z1 = PAD_Z + (float) Math.cos(a1) * PAD_RADIUS;
			float x2 = (float) Math.sin(a2) * PAD_RADIUS, z2 = PAD_Z + (float) Math.cos(a2) * PAD_RADIUS;
			boxes.add(new float[]{Math.min(x1, x2) - 0.06f, PAD_TOP, Math.min(z1, z2) - 0.06f, Math.max(x1, x2) + 0.06f, PAD_TOP + 1, Math.max(z1, z2) + 0.06f});
		}
		// The hot tub's rim on the upper deck
		boxes.add(new float[]{-1.7f, UPPER, -8.2f, 1.7f, UPPER + 0.6f, -7.95f});
		boxes.add(new float[]{-1.7f, UPPER, -4.85f, 1.7f, UPPER + 0.6f, -4.6f});
		boxes.add(new float[]{-1.7f, UPPER, -7.95f, -1.45f, UPPER + 0.6f, -4.85f});
		boxes.add(new float[]{1.45f, UPPER, -7.95f, 1.7f, UPPER + 0.6f, -4.85f});
		boxes.add(new float[]{-1.45f, UPPER, -7.95f, 1.45f, UPPER + 0.2f, -4.85f});
		// Posts holding the helipad up over the aft deck
		for (float[] p : new float[][]{{-4, -12}, {4, -12}, {-4, -19}, {4, -19}}) {
			boxes.add(new float[]{p[0] - 0.2f, MAIN, p[1] - 0.2f, p[0] + 0.2f, UPPER - 0.2f, p[1] + 0.2f});
		}
		return boxes;
	}

	private static final List<float[]> BOXES = boxes();

	/** Called through LarpProps: the yacht's decks, walls, stairs and railings as solid boxes. */
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

	/** For Client Sync: where your yacht is (x, y, z, yaw), or null. */
	public double[] shareState() {
		return isEnabled() && at != null ? new double[]{at.x, at.y, at.z, yaw} : null;
	}

	public int[] shareLook() {
		return new int[]{hull.get(), stripe.get(), deck.get(), underwaterLights.get() ? 1 : 0};
	}

	/** Someone else's yacht. */
	public void drawShared(Render3DEvent event, double[] s, int[] colors) {
		Vec3 wasAt = at;
		float wasYaw = yaw;
		int[] wasLook = look;
		at = new Vec3(s[0], s[1], s[2]);
		yaw = (float) s[3];
		look = colors.length >= 4 ? colors : null;
		try {
			draw(event);
		} finally {
			at = wasAt;
			yaw = wasYaw;
			look = wasLook;
		}
	}

	/** Someone else's yacht as solid boxes. */
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
		if (cam.distanceToSqr(at) > 250 * 250) return;
		float ox = (float) (at.x - cam.x), oy = (float) (at.y - cam.y), oz = (float) (at.z - cam.z);
		Quaternionf turn = turn();
		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn, ox, oy, oz);
		ShapeBuilder in = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn, ox, oy, oz).soft();
		ShapeBuilder glass = new ShapeBuilder(null, event.matrices().last(), turn, ox, oy, oz);
		int white = look != null ? look[0] : hull.get(), navy = look != null ? look[1] : stripe.get(), teak = look != null ? look[2] : deck.get();
		drawHull(b, glass, white, navy, teak);
		drawSalon(b, in, glass, white, navy, teak);
		drawUpper(b, in, glass, white, navy, teak);
		glass.flush(FxRender.quads(event, false, false));

		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		boolean lights = look != null ? look[3] != 0 : underwaterLights.get();
		if (lights) {
			// Blue glow round the hull just under the waterline
			for (float z = -LENGTH + 2; z < LENGTH - 4; z += 3) {
				for (int side = -1; side <= 1; side += 2) light(event, glow, turn, ox, oy, oz, side * (halfWidth(z) + 0.4f), -0.15f, z, 2.2f, 0x6020A0FF);
			}
		}
		// Navigation lights: red on the left, green on the right, white at the masthead
		light(event, glow, turn, ox, oy, oz, BEAM - 0.2f, MAIN + BULWARK + 0.1f, 6, 0.35f, 0xE0FF2A2A);
		light(event, glow, turn, ox, oy, oz, -BEAM + 0.2f, MAIN + BULWARK + 0.1f, 6, 0.35f, 0xE02AFF4A);
		light(event, glow, turn, ox, oy, oz, 0, BRIDGE_TOP + 3.1f, 6, 0.4f, 0xF0FFFFFF);
		// Warm lights along the salon ceiling
		for (float z = SALON_BACK + 2; z < SALON_FRONT - 1; z += 3) light(event, glow, turn, ox, oy, oz, 0, UPPER - 0.35f, z, 0.25f, 0x90FFE2B0);
		// The salon TV, last since it's its own layer
		dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.larp.TvModule.class).drawBuiltIn(event, turn, ox, oy, oz,
			new Vector3f(0, MAIN + 1.31f, -7.615f), new Vector3f(1, 0, 0), 2.34f, 1.28f);
	}

	private static void light(Render3DEvent event, VertexConsumer glow, Quaternionf rot, float ox, float oy, float oz, float x, float y, float z, float size, int color) {
		Vector3f at = new Vector3f(x, y, z).rotate(rot);
		FxRender.billboard(event, glow, ox + at.x, oy + at.y, oz + at.z, size, 0, LarpLight.glow(color));
	}

	/**
	 * The hull: white sides with a navy stripe at the waterline, a gold pinstripe, a chrome rub rail
	 * and portholes; the bulwarks; the teak deck; the transom, swim platform and jet ski; both sets
	 * of boarding stairs with glass sides; the sun pads, anchors and the stern flag.
	 */
	private void drawHull(ShapeBuilder b, ShapeBuilder glass, int white, int navy, int teak) {
		float bottom = -0.5f, stripeTop = 0.7f;
		int chrome = 0xFFC9CED6, gold = 0xFFC9A14A, dark = 0xFF141820;
		for (float z = -LENGTH; z < LENGTH; z += 1) {
			float w1 = halfWidth(z), w2 = halfWidth(z + 1);
			for (int side = -1; side <= 1; side += 2) {
				float a = side * w1, c = side * w2;
				quadSide(b, side, a, c, z, z + 1, bottom, stripeTop, navy);
				quadSide(b, side, a, c, z, z + 1, stripeTop, MAIN + BULWARK, white);
				// Lines along it, just proud of the side so they never fight with it
				float pa = a + side * 0.012f, pc = c + side * 0.012f;
				quadSide(b, side, pa, pc, z, z + 1, stripeTop + 0.05f, stripeTop + 0.11f, gold);
				quadSide(b, side, pa, pc, z, z + 1, MAIN - 0.32f, MAIN - 0.2f, chrome);
				// The inside of the bulwark, and its varnished cap
				float ia = a - side * 0.15f, ic = c - side * 0.15f;
				quadSide(b, -side, ia, ic, z, z + 1, MAIN, MAIN + BULWARK, ShapeBuilder.shade(white, 0.92f));
				b.flat(new float[]{a, MAIN + BULWARK, z}, new float[]{c, MAIN + BULWARK, z + 1}, new float[]{ic, MAIN + BULWARK, z + 1}, new float[]{ia, MAIN + BULWARK, z}, 0xFFC8A06A);
			}
			b.flat(new float[]{-w1 + 0.15f, MAIN, z}, new float[]{w1 - 0.15f, MAIN, z}, new float[]{w2 - 0.15f, MAIN, z + 1}, new float[]{-w2 + 0.15f, MAIN, z + 1}, teak);
			b.flat(new float[]{-w2, bottom, z + 1}, new float[]{w2, bottom, z + 1}, new float[]{w1, bottom, z}, new float[]{-w1, bottom, z}, 0xFF20242C);
		}
		// Dark caulking between the planks, raised a hair off the deck
		for (float x = -BEAM + 0.65f; x < BEAM - 0.6f; x += 0.45f) {
			b.flat(new float[]{x - 0.012f, MAIN + 0.006f, -LENGTH + 0.2f}, new float[]{x + 0.012f, MAIN + 0.006f, -LENGTH + 0.2f},
				new float[]{x + 0.012f, MAIN + 0.006f, 8}, new float[]{x - 0.012f, MAIN + 0.006f, 8}, ShapeBuilder.shade(teak, 0.68f));
		}
		// Portholes down both sides where the hull runs straight, below the deck
		for (float z = -20; z <= 5; z += 2.5f) {
			for (int side = -1; side <= 1; side += 2) {
				float x = side * (BEAM + 0.012f), x2 = side * (BEAM + 0.035f);
				b.box(Math.min(x, x2), 1.15f, z - 0.5f, Math.max(x, x2), 1.6f, z + 0.5f, chrome);
				float x3 = side * (BEAM + 0.04f);
				b.box(Math.min(x2, x3), 1.2f, z - 0.44f, Math.max(x2, x3), 1.55f, z + 0.44f, dark);
			}
		}
		// Anchors stowed either side of the bow
		for (int side = -1; side <= 1; side += 2) {
			float z = LENGTH - 5, x = side * (halfWidth(z) + 0.02f), x2 = side * (halfWidth(z) + 0.12f);
			b.box(Math.min(x, x2), MAIN - 1.3f, z - 0.25f, Math.max(x, x2), MAIN - 0.4f, z + 0.25f, 0xFF3A3D44);
		}
		// The transom across the stern, with the gap down to the swim platform
		float w = halfWidth(-LENGTH);
		b.box(-w, bottom, -LENGTH - 0.05f, w, MAIN, -LENGTH, white);
		b.box(-w, bottom, -LENGTH - 0.06f, w, 0.7f, -LENGTH - 0.05f, navy);
		b.box(-w, 0.75f, -LENGTH - 0.065f, w, 0.81f, -LENGTH - 0.05f, gold);
		b.box(-w + 0.01f, MAIN, -LENGTH + 0.01f, -1.2f, MAIN + BULWARK, -LENGTH + 0.15f, white);
		b.box(1.2f, MAIN, -LENGTH + 0.01f, w - 0.01f, MAIN + BULWARK, -LENGTH + 0.15f, white);
		// The swim platform, and steps up through the gap
		b.box(-5, 0.3f, -LENGTH - 1.4f, 5, 0.55f, -LENGTH - 0.06f, teak);
		for (int i = 0; i < 6; i++) {
			float top = 0.55f + (MAIN - 0.55f) * (i + 1) / 6f;
			b.box(-1.2f, 0.551f, -LENGTH - 1.4f + 0.233f * i, 1.2f, top, -LENGTH - 1.4f + 0.233f * (i + 1), i % 2 == 0 ? teak : ShapeBuilder.shade(teak, 0.9f));
		}
		// A jet ski on the platform
		jetSki(b, 3.2f, -LENGTH - 0.75f);
		// The stern flag
		b.box(w - 0.6f, MAIN + BULWARK, -LENGTH + 0.3f, w - 0.52f, MAIN + BULWARK + 2.2f, -LENGTH + 0.38f, chrome);
		b.quad(new float[]{w - 0.56f, MAIN + BULWARK + 2.15f, -LENGTH + 0.34f}, new float[]{w - 0.56f, MAIN + BULWARK + 1.45f, -LENGTH + 0.34f},
			new float[]{w - 0.56f, MAIN + BULWARK + 1.5f, -LENGTH - 0.8f}, new float[]{w - 0.56f, MAIN + BULWARK + 2.1f, -LENGTH - 0.8f}, 0xFFFF77C8);
		// The boarding stairs up the right side, off the hull so they don't touch it, with glass sides
		float outer = -BEAM - 1.3f, inner = -BEAM - 0.02f;
		for (int i = 0; i < BOARDING_STEPS; i++) {
			float top = MAIN * (i + 1) / BOARDING_STEPS;
			b.box(outer, top - 0.12f, -2 - i, inner, top, -1 - i, teak);
			b.box(outer + 0.01f, 0, -2 - i, inner - 0.01f, top - 0.12f, -1 - i, ShapeBuilder.shade(white, 0.94f));
		}
		// Each step's front corner is at z = -1 - i: the line through them runs to the top step, then the landing
		float rise = MAIN / BOARDING_STEPS;
		stairSide(b, glass, outer, -1, 0, -1, rise, -BOARDING_STEPS, MAIN, -1 - BOARDING_STEPS, white);
		// Sun pads on the foredeck, with a pair of towels
		b.box(-3, MAIN, 19, 3, MAIN + 0.35f, 22, 0xFFF0EEE8);
		b.box(-3, MAIN + 0.35f, 19, 3, MAIN + 0.45f, 22, 0xFFE9E3D6);
		b.box(-2.6f, MAIN + 0.451f, 19.4f, -0.6f, MAIN + 0.47f, 21.6f, 0xFFFF77C8);
		b.box(0.6f, MAIN + 0.451f, 19.4f, 2.6f, MAIN + 0.47f, 21.6f, 0xFF6BC7F2);
		b.box(-0.4f, MAIN - 0.6f, LENGTH - 1.6f, 0.4f, MAIN + 0.01f, LENGTH - 1, 0xFF8A8F98);
	}

	/** A jet ski sat on the swim platform, nose to the right: hull, seat and handlebars. */
	private static void jetSki(ShapeBuilder b, float x, float z) {
		float y = 0.551f;
		b.box(x - 1.3f, y, z - 0.45f, x + 1.1f, y + 0.4f, z + 0.45f, 0xFFF2F2F2);
		b.box(x + 1.1f, y + 0.05f, z - 0.3f, x + 1.5f, y + 0.35f, z + 0.3f, 0xFFF2F2F2);
		b.box(x - 1.3f, y + 0.12f, z - 0.46f, x + 1.4f, y + 0.2f, z + 0.46f, 0xFFFF77C8);
		b.box(x - 1.0f, y + 0.4f, z - 0.25f, x + 0.2f, y + 0.62f, z + 0.25f, 0xFF1A1B1F);
		b.box(x + 0.3f, y + 0.4f, z - 0.08f, x + 0.45f, y + 0.85f, z + 0.08f, 0xFF2A2C31);
		b.box(x + 0.3f, y + 0.8f, z - 0.4f, x + 0.45f, y + 0.88f, z + 0.4f, 0xFF2A2C31);
	}

	/** One side panel of the hull between two stations, facing out to {@code side}. */
	private static void quadSide(ShapeBuilder b, int side, float x1, float x2, float z1, float z2, float y1, float y2, int color) {
		if (side > 0) b.quad(new float[]{x1, y1, z1}, new float[]{x1, y2, z1}, new float[]{x2, y2, z2}, new float[]{x2, y1, z2}, color);
		else b.quad(new float[]{x1, y1, z1}, new float[]{x2, y1, z2}, new float[]{x2, y2, z2}, new float[]{x1, y2, z1}, color);
	}

	/**
	 * The side of a flight of stairs at {@code x}, {@code out} the way away from the steps: a solid
	 * stringer up to the line through the steps' front corners, from ({@code z1}, {@code y1}) to
	 * ({@code z2}, {@code y2}), then flat to {@code z3}; above it a glass panel and a steel handrail.
	 * All of it stands just outside the steps, so nothing passes through them. {@code base} is the
	 * floor the stringer stands on.
	 */
	private static void stairSide(ShapeBuilder b, ShapeBuilder glass, float x, int out, float base, float z1, float y1, float z2, float y2, float z3, int color) {
		float s = x + out * 0.02f, so = s + out * 0.03f, g = x + out * 0.07f, h = 0.95f;
		// The stringer, both faces, its top edge
		for (float face : new float[]{s, so}) {
			b.quad(new float[]{face, base, z1}, new float[]{face, y1, z1}, new float[]{face, y2, z2}, new float[]{face, base, z2}, color);
			b.quad(new float[]{face, base, z2}, new float[]{face, y2, z2}, new float[]{face, y2, z3}, new float[]{face, base, z3}, color);
		}
		b.quad(new float[]{s, y1, z1}, new float[]{so, y1, z1}, new float[]{so, y2, z2}, new float[]{s, y2, z2}, color);
		b.quad(new float[]{s, y2, z2}, new float[]{so, y2, z2}, new float[]{so, y2, z3}, new float[]{s, y2, z3}, color);
		// Glass above it, following the slope then the landing
		glass.quad(new float[]{g, y1 + 0.01f, z1}, new float[]{g, y2 + 0.01f, z2}, new float[]{g, y2 + h, z2}, new float[]{g, y1 + h, z1}, 0x48C8E0F0);
		glass.quad(new float[]{g, y2 + 0.01f, z2}, new float[]{g, y2 + 0.01f, z3}, new float[]{g, y2 + h, z3}, new float[]{g, y2 + h, z2}, 0x48C8E0F0);
		// The handrail: a slanted bar on top of the glass, then level along the landing
		handrail(b, g, z1, y1 + h, z2, y2 + h);
		handrail(b, g, z2, y2 + h, z3, y2 + h);
	}

	private static void handrail(ShapeBuilder b, float x, float z1, float y1, float z2, float y2) {
		int steel = 0xFFB9BEC6;
		b.quad(new float[]{x - 0.04f, y1, z1}, new float[]{x - 0.04f, y2, z2}, new float[]{x - 0.04f, y2 + 0.07f, z2}, new float[]{x - 0.04f, y1 + 0.07f, z1}, steel);
		b.quad(new float[]{x + 0.04f, y1 + 0.07f, z1}, new float[]{x + 0.04f, y2 + 0.07f, z2}, new float[]{x + 0.04f, y2, z2}, new float[]{x + 0.04f, y1, z1}, steel);
		b.quad(new float[]{x - 0.04f, y1 + 0.07f, z1}, new float[]{x - 0.04f, y2 + 0.07f, z2}, new float[]{x + 0.04f, y2 + 0.07f, z2}, new float[]{x + 0.04f, y1 + 0.07f, z1}, steel);
	}

	/** The salon: white walls with long dark windows, doors at both ends, a pale floor, sofas, a table, the bar and its stools. */
	private void drawSalon(ShapeBuilder b, ShapeBuilder in, ShapeBuilder glass, int white, int navy, int teak) {
		float sh = SALON_HALF, top = UPPER - 0.2f, sill = MAIN + 0.9f, head = MAIN + 2.4f;
		for (int side = -1; side <= 1; side += 2) {
			float x = side * sh, xi = x - side * 0.2f, lo = Math.min(x, xi), hi = Math.max(x, xi);
			b.box(lo, MAIN, SALON_BACK, hi, sill, SALON_FRONT, white);
			b.box(lo, head, SALON_BACK, hi, top, SALON_FRONT, white);
			for (float z = SALON_BACK + 0.2f; z <= SALON_FRONT - 0.2f; z += 3.9f) b.box(lo, sill, z - 0.12f, hi, head, z + 0.12f, white);
			glass.box(lo + 0.06f, sill + 0.01f, SALON_BACK + 0.2f, hi - 0.06f, head - 0.01f, SALON_FRONT - 0.2f, LarpLight.window(0x70101828));
			// A navy band over the windows, just proud of the wall
			float bx = x + side * 0.015f;
			b.box(Math.min(x, bx), head, SALON_BACK, Math.max(x, bx), head + 0.14f, SALON_FRONT, navy);
		}
		// The end walls sit between the side walls, so no faces share a corner
		float ew = sh - 0.2f;
		for (float z : new float[]{SALON_BACK, SALON_FRONT - 0.2f}) {
			for (int side = -1; side <= 1; side += 2) {
				// Either side of the door: wall under and over a big window, with posts each side of it
				float o1 = side * 1.2f, o2 = side * ew, w1 = side * 1.6f, w2 = side * 3.4f;
				b.box(Math.min(o1, o2), MAIN, z, Math.max(o1, o2), sill, z + 0.2f, white);
				b.box(Math.min(o1, o2), head, z, Math.max(o1, o2), top, z + 0.2f, white);
				b.box(Math.min(o1, w1), sill, z, Math.max(o1, w1), head, z + 0.2f, white);
				b.box(Math.min(w2, o2), sill, z, Math.max(w2, o2), head, z + 0.2f, white);
				glass.box(Math.min(w1, w2) + 0.01f, sill + 0.01f, z + 0.06f, Math.max(w1, w2) - 0.01f, head - 0.01f, z + 0.14f, LarpLight.window(0x70101828));
			}
			b.box(-1.2f, MAIN + 2.3f, z, 1.2f, head, z + 0.2f, white);
		}
		// Inside: a pale floor, sofas both sides with cushions, a coffee table, the bar and its stools, and the ceiling
		in.box(-ew, MAIN + 0.005f, SALON_BACK + 0.2f, ew, MAIN + 0.025f, SALON_FRONT - 0.2f, 0xFFE6E0D4);
		in.box(-2.4f, MAIN + 0.026f, -5, 2.4f, MAIN + 0.035f, 1, 0xFF8C7A66);
		for (int side = -1; side <= 1; side += 2) {
			float x = side * (sh - 0.75f);
			in.box(x - 0.5f, MAIN + 0.025f, -6, x + 0.5f, MAIN + 0.45f, 2, 0xFFEDE6DA);
			in.box(side > 0 ? x + 0.3f : x - 0.5f, MAIN + 0.45f, -6, side > 0 ? x + 0.5f : x - 0.3f, MAIN + 1.0f, 2, 0xFFE2D9C8);
			for (float z = -5.5f; z < 2; z += 1.6f) in.box(x - 0.35f, MAIN + 0.45f, z, x + 0.25f * side, MAIN + 0.62f, z + 1.2f, 0xFFF6F1E6);
			// A lamp at each end of the sofa
			in.box(x - 0.2f, MAIN + 0.025f, 2.3f, x + 0.2f, MAIN + 0.6f, 2.7f, 0xFF3B2A1E);
		}
		in.box(-1, MAIN + 0.025f, -3.5f, 1, MAIN + 0.4f, -0.5f, 0xFF3B2A1E);
		// The TV on a low cabinet at the back of the salon
		in.box(-1.35f, MAIN + 0.025f, -8.0f, 1.35f, MAIN + 0.5f, -7.45f, 0xFF3B2A1E);
		in.box(-0.12f, MAIN + 0.5f, -7.78f, 0.12f, MAIN + 0.66f, -7.68f, 0xFF141418);
		in.box(-1.22f, MAIN + 0.62f, -7.7f, 1.22f, MAIN + 2.0f, -7.62f, 0xFF141418);
		in.box(-1.17f, MAIN + 0.67f, -7.62f, 1.17f, MAIN + 1.95f, -7.615f, 0xFF050507);
		in.box(-0.9f, MAIN + 0.4f, -3.4f, 0.9f, MAIN + 0.42f, -0.6f, 0xFFD6C7A8);
		in.box(-3.8f, MAIN + 0.025f, SALON_FRONT - 1.2f, 3.8f, MAIN + 1.1f, SALON_FRONT - 0.22f, 0xFF2A2C31);
		in.box(-3.9f, MAIN + 1.1f, SALON_FRONT - 1.3f, 3.9f, MAIN + 1.18f, SALON_FRONT - 0.22f, 0xFFD6C7A8);
		for (float x = -3; x <= 3; x += 1.5f) {
			in.box(x - 0.06f, MAIN + 0.025f, SALON_FRONT - 1.75f, x + 0.06f, MAIN + 0.75f, SALON_FRONT - 1.63f, 0xFF8A8F98);
			in.box(x - 0.22f, MAIN + 0.75f, SALON_FRONT - 1.9f, x + 0.22f, MAIN + 0.82f, SALON_FRONT - 1.48f, 0xFF1A1B1F);
		}
		in.box(-ew, top - 0.03f, SALON_BACK + 0.2f, ew, top - 0.01f, SALON_FRONT - 0.2f, 0xFFF3F1EC);
	}

	/**
	 * The upper deck over the salon, its loungers and hot tub, the foredeck stairs with glass sides,
	 * the bridge with its window frames, helm and radar mast, and the helipad out over the stern.
	 */
	private void drawUpper(ShapeBuilder b, ShapeBuilder in, ShapeBuilder glass, int white, int navy, int teak) {
		float uh = UPPER_HALF;
		b.box(-uh, UPPER - 0.2f, SALON_BACK, uh, UPPER, UPPER_FRONT, white);
		b.box(-uh + 0.12f, UPPER, SALON_BACK, uh - 0.12f, UPPER + 0.012f, UPPER_FRONT, teak);
		// Railings: glass panels with a steel top rail
		rail(b, glass, -uh, SALON_BACK, -uh, UPPER_FRONT);
		rail(b, glass, uh, SALON_BACK, uh, UPPER_FRONT);
		rail(b, glass, -uh, UPPER_FRONT, -STAIR_HALF, UPPER_FRONT);
		rail(b, glass, STAIR_HALF, UPPER_FRONT, uh, UPPER_FRONT);
		rail(b, glass, -uh, SALON_BACK, -padJoin(), SALON_BACK);
		rail(b, glass, padJoin(), SALON_BACK, uh, SALON_BACK);
		// The foredeck stairs up to it, with a glass panel and handrail up each side
		for (int i = 0; i < STAIR_STEPS; i++) {
			float stepTop = MAIN + (UPPER - MAIN) * (i + 1) / STAIR_STEPS;
			float z2 = STAIR_START - STAIR_RUN * i, z1 = z2 - STAIR_RUN;
			b.box(-STAIR_HALF, stepTop - 0.12f, z1, STAIR_HALF, stepTop, z2, teak);
			b.box(-STAIR_HALF + 0.01f, MAIN + 0.012f, z1, STAIR_HALF - 0.01f, stepTop - 0.12f, z2, white);
		}
		// Each step's front corner is at its larger z: the line runs from the first to the last, then flat to the deck
		float stairRise = (UPPER - MAIN) / STAIR_STEPS;
		float lastFront = STAIR_START - STAIR_RUN * (STAIR_STEPS - 1);
		for (int side = -1; side <= 1; side += 2) {
			stairSide(b, glass, side * STAIR_HALF, side, MAIN + 0.012f, STAIR_START, MAIN + stairRise, lastFront, UPPER, UPPER_FRONT, white);
		}
		// Two sun loungers and a hot tub on the upper deck behind the bridge
		for (int side = -1; side <= 1; side += 2) {
			float x = side * 3.6f;
			in.box(x - 0.55f, UPPER + 0.02f, -2.4f, x + 0.55f, UPPER + 0.35f, 0.6f, 0xFFF0EEE8);
			in.box(x - 0.55f, UPPER + 0.35f, 0.0f, x + 0.55f, UPPER + 0.75f, 0.6f, 0xFFE9E3D6);
		}
		float tz1 = -8.2f, tz2 = -4.6f, tx = 1.7f, rim = UPPER + 0.6f, wall = 0.25f, base = UPPER + 0.02f;
		b.box(-tx, base, tz1, tx, rim, tz1 + wall, white);
		b.box(-tx, base, tz2 - wall, tx, rim, tz2, white);
		b.box(-tx, base, tz1 + wall + 0.005f, -tx + wall, rim - 0.005f, tz2 - wall - 0.005f, white);
		b.box(tx - wall, base, tz1 + wall + 0.005f, tx, rim - 0.005f, tz2 - wall - 0.005f, white);
		// The tiled floor inside, then the water over it, each a little in from the rim
		float ix = tx - wall - 0.01f, iz1 = tz1 + wall + 0.01f, iz2 = tz2 - wall - 0.01f;
		b.box(-ix, base + 0.005f, iz1, ix, UPPER + 0.2f, iz2, 0xFF2E7FA8);
		glass.box(-ix + 0.01f, UPPER + 0.215f, iz1 + 0.01f, ix - 0.01f, rim - 0.12f, iz2 - 0.01f, 0x9045C8F0);
		// The bridge: the end walls sit between the side walls so no faces meet
		float bh = 3.4f, sill = UPPER + 1.0f, bi = bh - 0.2f;
		for (int side = -1; side <= 1; side += 2) {
			float x = side * bh, xi = x - side * 0.2f, lo = Math.min(x, xi), hi = Math.max(x, xi);
			b.box(lo, UPPER, 2, hi, sill, 10, white);
			glass.box(lo + 0.06f, sill + 0.01f, 2.2f, hi - 0.06f, BRIDGE_TOP - 0.21f, 9.8f, LarpLight.window(0x80101828));
			b.box(lo, BRIDGE_TOP - 0.2f, 2, hi, BRIDGE_TOP, 10, white);
			// Black window frames
			for (float z = 2.2f; z <= 9.8f; z += 2.5f) b.box(lo - 0.01f, sill, z - 0.06f, hi + 0.01f, BRIDGE_TOP - 0.2f, z + 0.06f, 0xFF15171B);
		}
		b.box(-bi, UPPER, 9.8f, bi, sill, 10, white);
		glass.box(-bi + 0.01f, sill + 0.01f, 9.86f, bi - 0.01f, BRIDGE_TOP - 0.21f, 9.94f, LarpLight.window(0x80101828));
		for (float x = -bi; x <= bi + 0.01f; x += bi) b.box(x - 0.06f, sill, 9.8f, x + 0.06f, BRIDGE_TOP - 0.2f, 10.01f, 0xFF15171B);
		b.box(-bi, BRIDGE_TOP - 0.2f, 9.8f, bi, BRIDGE_TOP, 10, white);
		b.box(-bi, UPPER, 2, -1, BRIDGE_TOP, 2.2f, white);
		b.box(1, UPPER, 2, bi, BRIDGE_TOP, 2.2f, white);
		b.box(-1, UPPER + 2.3f, 2, 1, BRIDGE_TOP, 2.2f, white);
		b.box(-bh - 0.2f, BRIDGE_TOP, 1.8f, bh + 0.2f, BRIDGE_TOP + 0.2f, 10.2f, white);
		b.box(-bh - 0.2f, BRIDGE_TOP - 0.15f, 10.01f, bh + 0.2f, BRIDGE_TOP, 10.25f, navy);
		// The helm: a console with screens and a wheel, clear of the front wall
		in.box(-2.6f, UPPER + 0.035f, 8.6f, 2.6f, UPPER + 1.1f, 9.78f, 0xFF2A2C31);
		in.box(-2.2f, UPPER + 1.1f, 9.2f, -0.4f, UPPER + 1.6f, 9.3f, 0xFF1F4B7A);
		in.box(0.4f, UPPER + 1.1f, 9.2f, 2.2f, UPPER + 1.6f, 9.3f, 0xFF2E6A3E);
		in.box(-0.3f, UPPER + 1.1f, 8.5f, 0.3f, UPPER + 1.7f, 8.6f, 0xFF4A3528);
		in.box(-bi, UPPER + 0.013f, 2.2f, bi, UPPER + 0.03f, 9.8f, 0xFF3A3B40);
		// Two captain's chairs facing the helm
		for (int side = -1; side <= 1; side += 2) {
			in.box(side * 1.2f - 0.3f, UPPER + 0.03f, 7.4f, side * 1.2f + 0.3f, UPPER + 0.6f, 8.0f, 0xFFE9E3D6);
			in.box(side * 1.2f - 0.3f, UPPER + 0.6f, 7.4f, side * 1.2f + 0.3f, UPPER + 1.3f, 7.55f, 0xFFE9E3D6);
		}
		// The radar mast on the bridge roof, with a dome and a spinning-looking bar
		b.box(-0.12f, BRIDGE_TOP + 0.2f, 5.9f, 0.12f, BRIDGE_TOP + 3, 6.1f, white);
		b.box(-1.6f, BRIDGE_TOP + 2.2f, 5.8f, 1.6f, BRIDGE_TOP + 2.35f, 6.2f, white);
		b.box(-1.2f, BRIDGE_TOP + 2.5f, 5.85f, 1.2f, BRIDGE_TOP + 2.6f, 6.15f, 0xFF2A2C31);
		b.box(1.0f, BRIDGE_TOP + 0.2f, 4.0f, 1.8f, BRIDGE_TOP + 0.9f, 4.8f, white);
		b.box(1.1f, BRIDGE_TOP + 0.9f, 4.1f, 1.7f, BRIDGE_TOP + 1.05f, 4.7f, white);
		// The helipad: a round grey deck a step up from the upper deck (so the two never overlap flat), a white rim, a railing and four posts
		int sides = 40;
		for (int i = 0; i < sides; i++) {
			double a1 = Math.PI * 2 * i / sides, a2 = Math.PI * 2 * (i + 1) / sides;
			float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1), c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);
			b.flat(new float[]{0, PAD_TOP, PAD_Z}, new float[]{s2 * PAD_RADIUS, PAD_TOP, PAD_Z + c2 * PAD_RADIUS}, new float[]{s1 * PAD_RADIUS, PAD_TOP, PAD_Z + c1 * PAD_RADIUS},
				new float[]{s1 * PAD_RADIUS, PAD_TOP, PAD_Z + c1 * PAD_RADIUS}, 0xFF3A3C42);
			b.flat(new float[]{s1 * PAD_RADIUS, UPPER - 0.22f, PAD_Z + c1 * PAD_RADIUS}, new float[]{s2 * PAD_RADIUS, UPPER - 0.22f, PAD_Z + c2 * PAD_RADIUS},
				new float[]{0, UPPER - 0.22f, PAD_Z}, new float[]{0, UPPER - 0.22f, PAD_Z}, white);
			b.quad(new float[]{s1 * PAD_RADIUS, UPPER - 0.22f, PAD_Z + c1 * PAD_RADIUS}, new float[]{s1 * PAD_RADIUS, PAD_TOP, PAD_Z + c1 * PAD_RADIUS},
				new float[]{s2 * PAD_RADIUS, PAD_TOP, PAD_Z + c2 * PAD_RADIUS}, new float[]{s2 * PAD_RADIUS, UPPER - 0.22f, PAD_Z + c2 * PAD_RADIUS}, white);
			double mid = (a1 + a2) / 2;
			if (Math.cos(mid) > 0.62) continue;
			rail(b, glass, s1 * PAD_RADIUS, PAD_Z + c1 * PAD_RADIUS, s2 * PAD_RADIUS, PAD_Z + c2 * PAD_RADIUS);
		}
		for (float[] p : new float[][]{{-4, -12}, {4, -12}, {-4, -19}, {4, -19}}) b.box(p[0] - 0.2f, MAIN + 0.012f, p[1] - 0.2f, p[0] + 0.2f, UPPER - 0.22f, p[1] + 0.2f, white);
		// Under the helipad: a dining table and chairs on the covered aft deck
		in.box(-0.9f, MAIN + 0.7f, -16.5f, 0.9f, MAIN + 0.78f, -13.5f, 0xFF3B2A1E);
		in.box(-0.15f, MAIN + 0.012f, -15.15f, 0.15f, MAIN + 0.7f, -14.85f, 0xFF2A2C31);
		for (float z = -16; z <= -14; z += 1) {
			for (int side = -1; side <= 1; side += 2) {
				float x = side * 1.45f;
				in.box(x - 0.25f, MAIN + 0.012f, z - 0.25f, x + 0.25f, MAIN + 0.45f, z + 0.25f, 0xFFF0EEE8);
				in.box(side > 0 ? x + 0.15f : x - 0.25f, MAIN + 0.45f, z - 0.25f, side > 0 ? x + 0.25f : x - 0.15f, MAIN + 1.0f, z + 0.25f, 0xFFF0EEE8);
			}
		}
	}

	private static float sh() {
		return 3.2f;
	}

	/** How far across the helipad is where it meets the back of the upper deck. */
	private static float padJoin() {
		float d = SALON_BACK - PAD_Z;
		return (float) Math.sqrt(Math.max(0, PAD_RADIUS * PAD_RADIUS - d * d));
	}

	/** A railing from one point to another on the upper level: a glass panel and a steel top rail. */
	private static void rail(ShapeBuilder b, ShapeBuilder glass, float x1, float z1, float x2, float z2) {
		float dx = x2 - x1, dz = z2 - z1, length = (float) Math.sqrt(dx * dx + dz * dz);
		if (length < 1e-3f) return;
		float nx = -dz / length * 0.04f, nz = dx / length * 0.04f;
		glass.quad(new float[]{x1, UPPER, z1}, new float[]{x2, UPPER, z2}, new float[]{x2, UPPER + 0.95f, z2}, new float[]{x1, UPPER + 0.95f, z1}, 0x40C8E0F0);
		b.quad(new float[]{x1 - nx, UPPER + 0.95f, z1 - nz}, new float[]{x2 - nx, UPPER + 0.95f, z2 - nz}, new float[]{x2 + nx, UPPER + 1.02f, z2 + nz}, new float[]{x1 + nx, UPPER + 1.02f, z1 + nz}, 0xFFB9BEC6);
	}
}
