package dev.femboyware.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.larp.Watches;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.HeldBlades;
import java.time.LocalTime;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/**
 * Your sword drawn as something else in your hand, first and third person: CS-style knives with
 * their finishes (karambit, butterfly, bayonet), a katana, a Ledger, or a watch from the vitrine.
 * Built from real geometry (bevelled blades, round grips), only on your screen.
 */
public class SwordModelsModule extends Module {
	public enum Model { KARAMBIT, BUTTERFLY, BAYONET, KATANA, LEDGER, WATCH }

	public enum Finish { FADE, DOPPLER, TIGER_TOOTH, CRIMSON_WEB, SLAUGHTER, MARBLE_FADE, CHROME, GOLD, BLACK }

	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/white.png");

	private final EnumSetting<Model> model = add(new EnumSetting<>("Model", "What your sword looks like", Model.KARAMBIT));
	private final EnumSetting<Finish> finish = add(new EnumSetting<>("Finish", "The knife's skin", Finish.FADE));
	private final NumberSetting watch = add(new NumberSetting("Watch", "Which watch, for the Watch model (the vitrine's collection, in order)", 0, 0, Watches.COLLECTION.size() - 1, 1));
	private final BoolSetting axes = add(new BoolSetting("Axes Too", "Axes get the model as well", false));
	private final BoolSetting others = add(new BoolSetting("Others Too", "Other players' swords as well (only on your screen)", false));

	public SwordModelsModule() {
		super("Sword Models", "Your sword as a karambit, butterfly knife, bayonet, katana, Ledger or watch", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return model.get() == Model.WATCH ? style().name() : model.getDisplayName();
	}

	/** Handles {@code .swordmodel <model> [finish|watch number]}. */
	public void command(String[] args) {
		if (args.length < 2) {
			dev.femboyware.util.ChatUtil.info("Sword Models: " + getInfo() + ", " + finish.getDisplayName() + ". Usage: "
				+ dev.femboyware.manager.CommandManager.PREFIX + "swordmodel <karambit|butterfly|bayonet|katana|ledger|watch> [finish | watch number]");
			return;
		}
		try {
			model.set(Model.valueOf(args[1].toUpperCase(java.util.Locale.ROOT)));
			if (args.length > 2) {
				if (model.get() == Model.WATCH) watch.set(Double.parseDouble(args[2]) - 1);
				else finish.set(Finish.valueOf(args[2].toUpperCase(java.util.Locale.ROOT).replace('-', '_')));
			}
		} catch (IllegalArgumentException e) {
			dev.femboyware.util.ChatUtil.info("Unknown model or finish. Finishes: fade, doppler, tiger_tooth, crimson_web, slaughter, marble_fade, chrome, gold, black");
			return;
		}
		if (!isEnabled()) setEnabled(true);
		FemboyWare.getConfig().save();
		dev.femboyware.util.ChatUtil.info("Sword: " + getInfo() + (model.get() == Model.WATCH ? "" : " | " + finish.getDisplayName()));
	}

	private Watches.Style style() {
		return Watches.COLLECTION.get(Math.max(0, Math.min(Watches.COLLECTION.size() - 1, watch.getInt())));
	}

	/**
	 * From ItemStackRenderStateMixin, for every item drawn: true if it's a held sword this has drawn
	 * instead (and the item itself should be skipped).
	 */
	public static boolean replace(PoseStack matrices, SubmitNodeCollector queue, int light) {
		HeldBlades.Hand hand = HeldBlades.takeHand();
		if (hand == null) return false;
		SwordModelsModule self = FemboyWare.getModules().get(SwordModelsModule.class);
		if (self == null || !self.isEnabled() || !self.applies(hand)) return false;
		matrices.pushPose();
		HeldBlades.handheld(matrices, hand.firstPerson(), hand.left());
		// Item space: the sprite's hilt to its tip runs corner to corner; the model's blade runs up +y from the hilt
		matrices.translate(4.5f / 16, 4.5f / 16, 0.5f);
		HeldBlades.rotate(matrices, new org.joml.Quaternionf().rotationZ((float) Math.toRadians(-45)));
		Model m = self.model.get();
		Finish f = self.finish.get();
		Watches.Style s = self.style();
		// A watch the size of a sword is a bit much
		if (m == Model.WATCH) matrices.scale(0.7f, 0.7f, 0.7f);
		queue.submitCustomGeometry(matrices, layer(), (pose, c) -> {
			Mesh mesh = new Mesh(c, pose, light);
			switch (m) {
				case KARAMBIT -> karambit(mesh, f);
				case BUTTERFLY -> butterfly(mesh, f);
				case BAYONET -> bayonet(mesh, f);
				case KATANA -> katana(mesh, f);
				case LEDGER -> ledger(mesh);
				case WATCH -> watch(mesh, s);
			}
		});
		matrices.popPose();
		return true;
	}

	private boolean applies(HeldBlades.Hand hand) {
		ItemStack stack = hand.stack();
		if (!(stack.is(ItemTags.SWORDS) || axes.get() && stack.is(ItemTags.AXES))) return false;
		return others.get() || mc.player != null && hand.playerId() == mc.player.getId();
	}

	private static RenderType layer() {
		//? if >=26.1 {
		/*return RenderTypes.entityCutout(WHITE);
		*///?} else {
		return RenderTypes.entityCutoutNoCull(WHITE);
		//?}
	}

	// ---------------------------------------------------------------- the models
	// Blade space: the hilt at the origin, the blade up +y (about 0.95 long for a sword), x across the
	// blade and z its thickness, facing out of the sprite's plane.

	private static void karambit(Mesh m, Finish f) {
		// A claw: the blade hooks round toward the edge side, inside of the curve sharp
		int n = 18;
		float[][] path = new float[n][];
		float[] spine = new float[n], edge = new float[n], thick = new float[n];
		float r = 0.36f;
		for (int i = 0; i < n; i++) {
			float t = i / (n - 1f);
			double a = Math.toRadians(80 * t);
			path[i] = new float[]{(float) (-r + r * Math.cos(a)), (float) (r * Math.sin(a)) + 0.02f};
			float taper = t < 0.65f ? 1 : 1 - (t - 0.65f) / 0.35f;
			spine[i] = 0.018f * taper;
			edge[i] = (0.055f - 0.015f * t) * (float) Math.sqrt(taper);
			thick[i] = 0.026f * (0.4f + 0.6f * taper);
		}
		m.blade(path, spine, edge, thick, 1, f);
		// Bolster, a grippy curved handle and the finger ring at its end
		m.box(-0.045f, -0.02f, -0.022f, 0.045f, 0.03f, 0.022f, 0xFF2A2A2E);
		m.tube(new float[][]{{0, -0.02f}, {0.01f, -0.15f}, {0.03f, -0.28f}, {0.06f, -0.38f}}, 0.034f, 0.024f, 10, 0xFF16161A);
		float[][] ring = new float[25][];
		for (int i = 0; i < ring.length; i++) {
			double a = Math.PI * 2 * i / (ring.length - 1) + Math.PI * 0.5;
			ring[i] = new float[]{0.1f + 0.085f * (float) Math.cos(a), -0.47f + 0.085f * (float) Math.sin(a)};
		}
		m.tube(ring, 0.022f, 0.022f, 8, 0xFF2A2A2E);
		// Rivets down the handle
		for (int i = 0; i < 3; i++) m.box(-0.01f + 0.012f * i, -0.08f - 0.1f * i, 0.023f, 0.012f + 0.012f * i, -0.058f - 0.1f * i, 0.03f, 0xFFB8BEC6);
	}

	private static void butterfly(Mesh m, Finish f) {
		int n = 14;
		float[][] path = new float[n][];
		float[] spine = new float[n], edge = new float[n], thick = new float[n];
		for (int i = 0; i < n; i++) {
			float t = i / (n - 1f);
			path[i] = new float[]{0, 0.04f + 0.55f * t};
			// A clip point: the spine drops toward the tip over the last third
			float clip = t < 0.68f ? 1 : 1 - (t - 0.68f) / 0.32f;
			spine[i] = 0.02f * clip;
			edge[i] = 0.05f * (t < 0.8f ? 1 : 1 - (t - 0.8f) / 0.2f * 0.85f) * (i == n - 1 ? 0 : 1);
			thick[i] = 0.024f * (0.35f + 0.65f * clip);
		}
		m.blade(path, spine, edge, thick, 1, f);
		// The tang and the two pivot pins
		m.box(-0.03f, -0.01f, -0.012f, 0.05f, 0.05f, 0.012f, 0xFFC9CDD3);
		for (int side = -1; side <= 1; side += 2) {
			float z = side * 0.03f;
			m.tube(new float[][]{{0, 0.02f}, {0, 0.021f}}, 0.012f, 0.012f, 8, 0xFF8A8F96);
			// Each handle: two rails with crossbars between, the skeleton look
			float z1 = z - 0.009f, z2 = z + 0.009f;
			m.box(-0.045f, -0.48f, z1, -0.03f, 0.03f, z2, 0xFF1C1C20);
			m.box(0.03f, -0.48f, z1, 0.045f, 0.03f, z2, 0xFF1C1C20);
			for (int b = 0; b < 6; b++) {
				float y = -0.44f + b * 0.085f;
				m.box(-0.03f, y, z1, 0.03f, y + 0.022f, z2, 0xFF1C1C20);
			}
			m.box(-0.045f, -0.5f, z1, 0.045f, -0.48f, z2, 0xFF1C1C20);
		}
		// The latch at the bottom
		m.box(-0.015f, -0.56f, -0.008f, 0.015f, -0.48f, 0.008f, 0xFFC9CDD3);
	}

	private static void bayonet(Mesh m, Finish f) {
		int n = 16;
		float[][] path = new float[n][];
		float[] spine = new float[n], edge = new float[n], thick = new float[n];
		for (int i = 0; i < n; i++) {
			float t = i / (n - 1f);
			path[i] = new float[]{0, 0.03f + 0.66f * t};
			float clip = t < 0.72f ? 1 : 1 - (t - 0.72f) / 0.28f;
			spine[i] = 0.024f * clip;
			edge[i] = 0.05f * (t < 0.85f ? 1 : 1 - (t - 0.85f) / 0.15f) + 0.0001f;
			thick[i] = 0.028f * (0.35f + 0.65f * clip);
		}
		m.blade(path, spine, edge, thick, 1, f);
		// Sawback teeth down the spine
		for (int i = 0; i < 9; i++) {
			float y = 0.12f + i * 0.04f;
			m.box(-0.036f, y, -0.008f, -0.024f, y + 0.02f, 0.008f, finishAt(f, 0.15f + i * 0.05f, 0));
		}
		// Crossguard with its muzzle ring, grooved grip, pommel
		m.box(-0.1f, 0, -0.03f, 0.08f, 0.035f, 0.03f, 0xFF3A3D42);
		m.tube(new float[][]{{-0.11f, 0.0f}, {-0.11f, 0.035f}}, 0.03f, 0.03f, 10, 0xFF3A3D42);
		for (int i = 0; i < 8; i++) {
			float y = -0.015f - i * 0.045f;
			m.tube(new float[][]{{0, y}, {0, y - 0.03f}}, 0.04f, 0.03f, 10, 0xFF1F2A1E);
			m.tube(new float[][]{{0, y - 0.03f}, {0, y - 0.045f}}, 0.034f, 0.026f, 10, 0xFF182017);
		}
		m.box(-0.045f, -0.42f, -0.034f, 0.045f, -0.37f, 0.034f, 0xFF3A3D42);
	}

	private static void katana(Mesh m, Finish f) {
		int n = 24;
		float[][] path = new float[n][];
		float[] spine = new float[n], edge = new float[n], thick = new float[n];
		for (int i = 0; i < n; i++) {
			float t = i / (n - 1f);
			// A gentle curve back toward the spine (sori)
			path[i] = new float[]{-0.07f * t * t, 0.05f + 1.0f * t};
			float tip = t < 0.9f ? 1 : 1 - (t - 0.9f) / 0.1f;
			spine[i] = 0.008f * tip;
			edge[i] = 0.036f * (t < 0.9f ? 1 - 0.15f * t : tip * 0.86f);
			thick[i] = 0.022f * (0.4f + 0.6f * tip);
		}
		m.blade(path, spine, edge, thick, 1, f);
		// Habaki (the gold collar), tsuba (the guard), then the wrapped grip and the cap
		m.box(-0.016f, 0.02f, -0.016f, 0.042f, 0.07f, 0.016f, 0xFFD9B04F);
		float[][] disc = new float[26][];
		for (int i = 0; i < disc.length; i++) {
			double a = Math.PI * 2 * i / (disc.length - 1);
			disc[i] = new float[]{0.08f * (float) Math.cos(a), 0.06f * (float) Math.sin(a)};
		}
		m.slab(disc, 0.0f, 0.02f, 0xFF2B2622);
		for (int i = 0; i < 9; i++) {
			float y = -0.005f - i * 0.04f;
			m.tube(new float[][]{{0, y}, {0, y - 0.04f}}, 0.035f, 0.024f, 8, i % 2 == 0 ? 0xFF14141A : 0xFFE8E2D2);
			// The diamonds of the wrap
			m.box(-0.012f, y - 0.03f, 0.022f, 0.012f, y - 0.01f, 0.027f, 0xFFE8E2D2);
		}
		m.box(-0.038f, -0.4f, -0.027f, 0.038f, -0.37f, 0.027f, 0xFF2B2622);
	}

	private static void ledger(Mesh m) {
		// A Ledger Nano, sword sized: black body, a steel swivel cover over half of it, the screen and two buttons
		m.box(-0.075f, -0.3f, -0.032f, 0.075f, 0.52f, 0.032f, 0xFF17171A);
		m.box(-0.08f, -0.3f, 0.032f, 0.08f, 0.16f, 0.04f, 0xFFC5CAD1);
		m.box(-0.08f, -0.3f, -0.04f, 0.08f, 0.16f, -0.032f, 0xFFC5CAD1);
		m.box(-0.08f, -0.3f, -0.04f, -0.075f, 0.16f, 0.04f, 0xFFC5CAD1);
		m.tube(new float[][]{{0, -0.24f}, {0, -0.239f}}, 0.06f, 0.06f, 16, 0xFFD6DADF);
		m.box(-0.04f, 0.22f, 0.032f, 0.04f, 0.46f, 0.036f, 0xFF050608);
		// "Ledger" on the screen, and a balance line
		m.box(-0.025f, 0.38f, 0.0365f, 0.025f, 0.395f, 0.038f, 0xFFE8EAEE);
		m.box(-0.03f, 0.3f, 0.0365f, 0.03f, 0.335f, 0.038f, 0xFF4FD18B);
		m.box(-0.09f, 0.4f, -0.015f, -0.075f, 0.44f, 0.015f, 0xFF2A2A2E);
		m.box(0.075f, 0.4f, -0.015f, 0.09f, 0.44f, 0.015f, 0xFF2A2A2E);
		// The USB end
		m.box(-0.03f, 0.52f, -0.012f, 0.03f, 0.58f, 0.012f, 0xFF9AA0A8);
	}

	private static void watch(Mesh m, Watches.Style s) {
		float cy = 0.28f, r = 0.15f;
		// Bracelet links both ways from the case
		for (int dir = -1; dir <= 1; dir += 2) {
			for (int i = 0; i < 9; i++) {
				float y1 = cy + dir * (r * 0.8f + i * 0.055f), y2 = y1 + dir * 0.05f;
				float lo = Math.min(y1, y2), hi = Math.max(y1, y2);
				m.box(-0.09f, lo, -0.02f, -0.035f, hi, 0.02f, s.metal());
				m.box(-0.032f, lo, -0.022f, 0.032f, hi, 0.022f, ShapeShade.lighter(s.metal()));
				m.box(0.035f, lo, -0.02f, 0.09f, hi, 0.02f, s.metal());
			}
		}
		// Case, bezel, dial (round or the Royal Oak's octagon)
		int sides = s.shape() == Watches.Shape.OCTAGON ? 8 : 40;
		float[][] caseRing = ring(cy, r, sides, s.shape() == Watches.Shape.OCTAGON ? Math.PI / 8 : 0);
		m.slab(caseRing, -0.035f, 0.035f, s.metal());
		float[][] bezel = ring(cy, r * 0.93f, sides, s.shape() == Watches.Shape.OCTAGON ? Math.PI / 8 : 0);
		m.slab(bezel, 0.035f, 0.045f, s.iced() ? 0xFFF4F8FF : s.bezel());
		if (s.bezel2() != 0) {
			// Two-tone: the bottom half of the bezel's insert
			float[][] half = new float[21][];
			for (int i = 0; i < half.length; i++) {
				double a = Math.PI + Math.PI * i / (half.length - 1);
				half[i] = new float[]{r * 0.93f * (float) Math.cos(a), cy + r * 0.93f * (float) Math.sin(a)};
			}
			m.slab(half, 0.045f, 0.047f, s.bezel2());
		}
		m.slab(ring(cy, r * 0.72f, sides, s.shape() == Watches.Shape.OCTAGON ? Math.PI / 8 : 0), 0.045f, 0.049f, s.dial());
		// Hour markers and the hands, at the real time
		for (int h = 0; h < 12; h++) {
			double a = Math.PI / 2 - Math.PI * 2 * h / 12;
			float x = (float) Math.cos(a) * r * 0.6f, y = cy + (float) Math.sin(a) * r * 0.6f;
			float w = h % 3 == 0 ? 0.011f : 0.007f;
			m.box(x - w, y - w, 0.049f, x + w, y + w, 0.052f, s.iced() ? 0xFFFFFFFF : 0xFFE9ECEF);
		}
		LocalTime now = LocalTime.now();
		hand(m, cy, Math.PI / 2 - Math.PI * 2 * ((now.getHour() % 12) + now.getMinute() / 60.0) / 12, r * 0.38f, 0.008f, 0.053f, 0xFFE9ECEF);
		hand(m, cy, Math.PI / 2 - Math.PI * 2 * (now.getMinute() + now.getSecond() / 60.0) / 60, r * 0.58f, 0.006f, 0.056f, 0xFFE9ECEF);
		hand(m, cy, Math.PI / 2 - Math.PI * 2 * now.getSecond() / 60.0, r * 0.62f, 0.003f, 0.059f, 0xFFD03A3A);
		// Crown
		m.tube(new float[][]{{r + 0.0f, cy}, {r + 0.035f, cy}}, 0.018f, 0.018f, 10, s.metal());
	}

	private static void hand(Mesh m, float cy, double angle, float length, float width, float z, int color) {
		float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle), px = -dy * width, py = dx * width;
		m.quad(new float[]{px, cy + py, z}, new float[]{-px, cy - py, z}, new float[]{dx * length - px, cy + dy * length - py, z},
			new float[]{dx * length + px, cy + dy * length + py, z}, color, color, color, color);
	}

	private static float[][] ring(float cy, float r, int sides, double turn) {
		float[][] out = new float[sides + 1][];
		for (int i = 0; i <= sides; i++) {
			double a = turn + Math.PI * 2 * i / sides;
			out[i] = new float[]{r * (float) Math.cos(a), cy + r * (float) Math.sin(a)};
		}
		return out;
	}

	// ---------------------------------------------------------------- finishes

	/** A finish's colour at {@code t} along the blade (0 hilt, 1 tip) and {@code k} across it (0 spine, 1 edge). */
	static int finishAt(Finish f, float t, float k) {
		return switch (f) {
			case FADE -> t < 0.5f ? mix(0xFFFFE27A, 0xFFFF6FB7, t * 2) : mix(0xFFFF6FB7, 0xFF8B5CF6, (t - 0.5f) * 2);
			case DOPPLER -> {
				float v = (float) (0.5 + 0.25 * Math.sin(t * 13 + k * 4) + 0.25 * Math.sin(t * 7.3 - k * 9 + 1.7));
				yield v < 0.5f ? mix(0xFF120C2E, 0xFF4B2BB8, v * 2) : mix(0xFF4B2BB8, 0xFFC04BD8, (v - 0.5f) * 2);
			}
			case TIGER_TOOTH -> Math.sin(t * 26 + k * 5 + Math.sin(t * 9) * 1.5) > 0.35 ? 0xFFB85A0C : 0xFFFFB733;
			case CRIMSON_WEB -> Math.abs(Math.sin(t * 22 + Math.sin(k * 6) * 2)) < 0.14 || Math.abs(Math.sin(k * 9 + t * 3)) < 0.1 ? 0xFF120708 : 0xFFB3121F;
			case SLAUGHTER -> {
				float v = (float) (0.5 + 0.3 * Math.sin(t * 17 + k * 7) + 0.2 * Math.sin(t * 31 - k * 5));
				yield v < 0.45f ? 0xFFC42A35 : v < 0.75f ? 0xFFEE7C8A : 0xFFF4CACB;
			}
			case MARBLE_FADE -> t < 0.33f ? mix(0xFFFFD43B, 0xFFE8343F, t * 3) : t < 0.66f ? mix(0xFFE8343F, 0xFF2F6FE0, (t - 0.33f) * 3) : mix(0xFF2F6FE0, 0xFFFFD43B, (t - 0.66f) * 3);
			case CHROME -> mix(0xFFB7BEC8, 0xFFF1F4F8, k);
			case GOLD -> mix(0xFFC99A35, 0xFFF6DA86, k);
			case BLACK -> mix(0xFF16161A, 0xFF3A3A42, k);
		};
	}

	private static int lift(int c) {
		int r = Math.min(255, (int) (((c >> 16) & 0xFF) * 1.18f + 14)), g = Math.min(255, (int) (((c >> 8) & 0xFF) * 1.18f + 14)), b = Math.min(255, (int) ((c & 0xFF) * 1.18f + 14));
		return (c & 0xFF000000) | r << 16 | g << 8 | b;
	}

	static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}

	private static final class ShapeShade {
		static int lighter(int c) {
			return mix(c, 0xFFFFFFFF, 0.25f);
		}
	}

	// ---------------------------------------------------------------- geometry

	/** Writes lit, coloured, two-sided quads in blade space. */
	private static final class Mesh {
		final VertexConsumer c;
		final PoseStack.Pose pose;
		final int light;
		private final Vector3f u = new Vector3f(), v = new Vector3f();

		Mesh(VertexConsumer c, PoseStack.Pose pose, int light) {
			this.c = c;
			this.pose = pose;
			this.light = light;
		}

		void quad(float[] a, float[] b, float[] d, float[] e, int ca, int cb, int cd, int ce) {
			u.set(d[0] - a[0], d[1] - a[1], d[2] - a[2]);
			v.set(e[0] - b[0], e[1] - b[1], e[2] - b[2]);
			u.cross(v);
			if (u.lengthSquared() < 1e-12f) return;
			u.normalize();
			vertex(a, ca, u.x, u.y, u.z);
			vertex(b, cb, u.x, u.y, u.z);
			vertex(d, cd, u.x, u.y, u.z);
			vertex(e, ce, u.x, u.y, u.z);
			vertex(e, ce, -u.x, -u.y, -u.z);
			vertex(d, cd, -u.x, -u.y, -u.z);
			vertex(b, cb, -u.x, -u.y, -u.z);
			vertex(a, ca, -u.x, -u.y, -u.z);
		}

		private void vertex(float[] p, int color, float nx, float ny, float nz) {
			// Held items are lit dimly from the side: lift the colours so metal reads as metal
			color = lift(color);
			c.addVertex(pose, p[0], p[1], p[2]).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
		}

		void box(float x1, float y1, float z1, float x2, float y2, float z2, int color) {
			float[] p000 = {x1, y1, z1}, p100 = {x2, y1, z1}, p110 = {x2, y2, z1}, p010 = {x1, y2, z1};
			float[] p001 = {x1, y1, z2}, p101 = {x2, y1, z2}, p111 = {x2, y2, z2}, p011 = {x1, y2, z2};
			quad(p000, p010, p110, p100, color, color, color, color);
			quad(p001, p101, p111, p011, color, color, color, color);
			quad(p000, p001, p011, p010, color, color, color, color);
			quad(p100, p110, p111, p101, color, color, color, color);
			quad(p000, p100, p101, p001, color, color, color, color);
			quad(p010, p011, p111, p110, color, color, color, color);
		}

		/** A flat shape (outline in x, y, closed: last point the first) made solid from {@code z1} to {@code z2}. */
		void slab(float[][] outline, float z1, float z2, int color) {
			float cx = 0, cy = 0;
			int n = outline.length - 1;
			for (int i = 0; i < n; i++) {
				cx += outline[i][0];
				cy += outline[i][1];
			}
			cx /= n;
			cy /= n;
			for (int i = 0; i < n; i++) {
				float[] a = outline[i], b = outline[i + 1];
				float[] c1 = {cx, cy, z2}, a2 = {a[0], a[1], z2}, b2 = {b[0], b[1], z2};
				quad(c1, a2, b2, b2, color, color, color, color);
				float[] c0 = {cx, cy, z1}, a1 = {a[0], a[1], z1}, b1 = {b[0], b[1], z1};
				quad(c0, b1, a1, a1, color, color, color, color);
				quad(a1, b1, b2, a2, color, color, color, color);
			}
		}

		/** A round (elliptical: {@code rx} across, {@code rz} in depth) tube along a path in x, y. */
		void tube(float[][] path, float rx, float rz, int sides, int color) {
			float[][][] rings = new float[path.length][sides + 1][];
			for (int i = 0; i < path.length; i++) {
				float[] p = path[i], before = path[Math.max(0, i - 1)], after = path[Math.min(path.length - 1, i + 1)];
				float tx = after[0] - before[0], ty = after[1] - before[1];
				float len = (float) Math.sqrt(tx * tx + ty * ty);
				float nx = len == 0 ? 1 : -ty / len, ny = len == 0 ? 0 : tx / len;
				for (int s = 0; s <= sides; s++) {
					double a = Math.PI * 2 * s / sides;
					float ca = (float) Math.cos(a) * rx, sa = (float) Math.sin(a) * rz;
					rings[i][s] = new float[]{p[0] + nx * ca, p[1] + ny * ca, sa};
				}
			}
			for (int i = 0; i < path.length - 1; i++) {
				for (int s = 0; s < sides; s++) {
					// A touch lighter on top so it reads as round under the flat lighting of held items
					int shade = mix(color, 0xFFFFFFFF, 0.08f * (float) Math.max(0, Math.sin(Math.PI * 2 * s / sides)));
					quad(rings[i][s], rings[i + 1][s], rings[i + 1][s + 1], rings[i][s + 1], shade, shade, shade, shade);
				}
			}
			// Capped ends
			for (int end = 0; end < 2; end++) {
				int i = end == 0 ? 0 : path.length - 1;
				float[] center = {path[i][0], path[i][1], 0};
				for (int s = 0; s < sides; s++) quad(center, rings[i][s], rings[i][s + 1], rings[i][s + 1], color, color, color, color);
			}
		}

		/**
		 * A blade along {@code path} (x, y): at each point it runs from its spine ({@code spine} back
		 * from the path) to its edge ({@code edge} out the other way, toward the path's left when
		 * {@code side} is 1), full thickness across the flat and ground down to nothing at the edge.
		 */
		void blade(float[][] path, float[] spine, float[] edge, float[] thick, int side, Finish f) {
			int n = path.length;
			float[][] sTop = new float[n][], sBot = new float[n][], gTop = new float[n][], gBot = new float[n][], e = new float[n][];
			int[] cs = new int[n], cg = new int[n], ce = new int[n];
			for (int i = 0; i < n; i++) {
				float[] p = path[i], before = path[Math.max(0, i - 1)], after = path[Math.min(n - 1, i + 1)];
				float tx = after[0] - before[0], ty = after[1] - before[1];
				float len = (float) Math.sqrt(tx * tx + ty * ty);
				float nx = -ty / len * side, ny = tx / len * side;
				float sx = p[0] - nx * spine[i], sy = p[1] - ny * spine[i];
				float ex = p[0] + nx * edge[i], ey = p[1] + ny * edge[i];
				// The grind starts a little over halfway from the spine to the edge
				float gx = sx + (ex - sx) * 0.55f, gy = sy + (ey - sy) * 0.55f;
				float h = thick[i] / 2;
				sTop[i] = new float[]{sx, sy, h};
				sBot[i] = new float[]{sx, sy, -h};
				gTop[i] = new float[]{gx, gy, h};
				gBot[i] = new float[]{gx, gy, -h};
				e[i] = new float[]{ex, ey, 0};
				float t = i / (n - 1f);
				cs[i] = finishAt(f, t, 0);
				cg[i] = finishAt(f, t, 0.55f);
				// The sharpened edge catches the light
				ce[i] = mix(finishAt(f, t, 1), 0xFFFFFFFF, 0.35f);
			}
			for (int i = 0; i < n - 1; i++) {
				int j = i + 1;
				quad(sTop[i], sTop[j], gTop[j], gTop[i], cs[i], cs[j], cg[j], cg[i]);
				quad(gTop[i], gTop[j], e[j], e[i], cg[i], cg[j], ce[j], ce[i]);
				quad(sBot[i], gBot[i], gBot[j], sBot[j], cs[i], cg[i], cg[j], cs[j]);
				quad(gBot[i], e[i], e[j], gBot[j], cg[i], ce[i], ce[j], cg[j]);
				int dark = mix(cs[i], 0xFF000000, 0.2f);
				quad(sTop[i], sBot[i], sBot[j], sTop[j], dark, dark, dark, dark);
			}
			// Closed at the hilt
			quad(sTop[0], gTop[0], gBot[0], sBot[0], cs[0], cg[0], cg[0], cs[0]);
			quad(gTop[0], e[0], e[0], gBot[0], cg[0], ce[0], ce[0], cg[0]);
		}
	}
}
