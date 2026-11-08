package dev.femboyware.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;

/**
 * Turns you into a furry: a fluffy light blue fursona skin (slim arms), fox ears on your head and a
 * swaying tail in matching colours, and your armour hidden so the fur shows. Only you see it.
 */
public class FurryModule extends Module {
	private static final ClientAsset.Texture SKIN = new ClientAsset.ResourceTexture(
		Identifier.fromNamespaceAndPath("femboyware", "furry_skin"), Identifier.fromNamespaceAndPath("femboyware", "textures/furry_skin.png"));
	/** Light comes from above and a little in front, in the model's own space (y points down). */
	private static final Vec3 LIGHT = new Vec3(0.2, -1, -0.5).normalize();

	private final ColorSetting fur = add(new ColorSetting("Fur Color", "Colour of the ears and tail, to match the skin", 0xFFC6E0FA));
	private final ColorSetting belly = add(new ColorSetting("Inner Color", "Inside the ears and the tip of the tail", 0xFFEFEFF4));
	private final ColorSetting paws = add(new ColorSetting("Ear Tip Color", "The tips of the ears", 0xFF8DBBE8));
	private final BoolSetting skin = add(new BoolSetting("Skin", "Swap your skin for the fursona", true));
	private final BoolSetting ears = add(new BoolSetting("Ears", "Pointed ears on your head", true));
	private final BoolSetting tail = add(new BoolSetting("Tail", "A fluffy swaying tail", true));
	private final NumberSetting tailSize = add(new NumberSetting("Tail Size", "How big the tail is: longer and fluffier as it grows", 1, 0.5, 2.5, 0.05)).visibleWhen(tail::get);
	private final BoolSetting hideArmor = add(new BoolSetting("Hide Armor", "Hide your armour so the fur shows", true));

	public FurryModule() {
		super("Furry", "Turns you into a furry with a fursona skin, ears and a tail :3", Category.COSMETIC);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> player) helper.register(new Feature(asContext(player)));
		});
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<AvatarRenderState, PlayerModel> asContext(AvatarRenderer<?> renderer) {
		return (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer;
	}

	/** Called with the skin of the player called {@code name}: gives you the fursona skin. */
	public PlayerSkin apply(PlayerSkin textures, String name) {
		if (!isEnabled() || !skin.get() || mc.player == null) return textures;
		if (!name.equalsIgnoreCase(mc.getUser().getName()) && !name.equalsIgnoreCase(mc.player.getGameProfile().name())) return textures;
		return new PlayerSkin(SKIN, textures.cape(), textures.elytra(), PlayerModelType.SLIM, textures.secure());
	}

	/** Whether to hide this player's armour; read by ArmorFeatureRendererMixin. */
	public boolean hidesArmor(AvatarRenderState state) {
		return isEnabled() && hideArmor.get() && mc.player != null && state.id == mc.player.getId();
	}

	private boolean shows(AvatarRenderState state) {
		return isEnabled() && !state.isInvisible && mc.player != null && state.id == mc.player.getId();
	}

	// ---------------------------------------------------------------- ears and tail

	/** Draws the ears and tail in your model space, after the vanilla model. */
	private class Feature extends RenderLayer<AvatarRenderState, PlayerModel> {
		Feature(RenderLayerParent<AvatarRenderState, PlayerModel> context) {
			super(context);
		}

		@Override
		public void submit(PoseStack matrices, SubmitNodeCollector queue, int light, AvatarRenderState state, float headYaw, float headPitch) {
			if (!shows(state)) return;
			if (ears.get()) {
				matrices.pushPose();
				getParentModel().head.translateAndRotate(matrices);
				queue.submitCustomGeometry(matrices, RenderTypes.debugQuads(), (entry, consumer) -> drawEars(entry, consumer));
				matrices.popPose();
			}
			if (tail.get()) {
				float age = state.ageInTicks;
				// How fast the legs swing, so the tail sways more as you walk
				float walk = state.walkAnimationSpeed;
				matrices.pushPose();
				getParentModel().body.translateAndRotate(matrices);
				queue.submitCustomGeometry(matrices, RenderTypes.debugQuads(), (entry, consumer) -> drawTail(entry, consumer, age, walk));
				matrices.popPose();
			}
		}
	}

	/** Points round each ear's outline, base to base over a rounded tip; ear space, x across, y up. */
	private static final int EAR_POINTS = 18;

	/**
	 * Two soft, rounded ears on top of the head, leaning out a little: fur outside, a pale rounded
	 * inside and a darker tip. Each is a thin slab cut to a curved outline. Head space: blocks, y
	 * down, front is -z.
	 */
	private void drawEars(PoseStack.Pose entry, VertexConsumer consumer) {
		double[][] outline = earOutline(0.1, 0.25, 0.045);
		double[][] inside = earOutline(0.06, 0.17, 0.03);
		for (int side = -1; side <= 1; side += 2) {
			double center = side * 0.17;
			double base = -0.455;
			double lean = side * 0.18;
			double front = -0.035;
			double back = 0.045;
			// The fur slab: front and back faces as fans from the middle of the base, then the rim
			Vec3[] f = place(outline, center, base, lean, front, 0);
			Vec3[] b = place(outline, center, base, lean, back, 0);
			Vec3 frontMiddle = new Vec3(center, base, front);
			Vec3 backMiddle = new Vec3(center, base, back);
			for (int i = 0; i < f.length - 1; i++) {
				triangle(consumer, entry, frontMiddle, f[i], f[i + 1], fur.get());
				triangle(consumer, entry, backMiddle, b[i + 1], b[i], fur.get());
				quad(consumer, entry, f[i], b[i], b[i + 1], f[i + 1], fur.get());
			}
			quad(consumer, entry, f[f.length - 1], b[b.length - 1], b[0], f[0], fur.get());
			// The pale rounded inside, just in front, and a darker cap over the top of it
			Vec3[] in = place(inside, center, base, lean, front - 0.002, 0.012);
			Vec3 insideMiddle = new Vec3(center, base - 0.012, front - 0.002);
			for (int i = 0; i < in.length - 1; i++) triangle(consumer, entry, insideMiddle, in[i], in[i + 1], belly.get());
			Vec3[] cap = place(outline, center, base, lean, front - 0.003, 0);
			int capFrom = (int) (EAR_POINTS * 0.33);
			int capTo = EAR_POINTS - capFrom;
			Vec3 capMiddle = lerp(cap[capFrom], cap[capTo], 0.5);
			for (int i = capFrom; i < capTo; i++) triangle(consumer, entry, capMiddle, cap[i], cap[i + 1], paws.get());
		}
	}

	/**
	 * An ear's outline from the right of its base, up a softly bulging side, over a round tip of
	 * radius {@code tip}, and down to the left of its base.
	 */
	private static double[][] earOutline(double halfWidth, double height, double tip) {
		double[][] points = new double[EAR_POINTS + 1][];
		int half = EAR_POINTS / 3;
		for (int i = 0; i <= EAR_POINTS; i++) {
			if (i <= half) {
				// Right side: a curve that bulges out, ending where the round tip starts
				double t = i / (double) half;
				double x = (1 - t) * (1 - t) * halfWidth + 2 * (1 - t) * t * halfWidth * 0.8 + t * t * tip;
				double y = 2 * (1 - t) * t * height * 0.62 + t * t * (height - tip);
				points[i] = new double[]{x, y};
			} else if (i >= EAR_POINTS - half) {
				// Left side mirrors the right
				double[] mirror = points[EAR_POINTS - i];
				points[i] = new double[]{-mirror[0], mirror[1]};
			} else {
				// The round tip
				double t = (i - half) / (double) (EAR_POINTS - 2 * half);
				double angle = Math.PI * t;
				points[i] = new double[]{tip * Math.cos(angle), height - tip + tip * Math.sin(angle)};
			}
		}
		return points;
	}

	/** An outline placed on the head: centred at {@code center}, leaning out by {@code lean}, at depth {@code z}, lifted by {@code lift}. */
	private static Vec3[] place(double[][] outline, double center, double base, double lean, double z, double lift) {
		Vec3[] placed = new Vec3[outline.length];
		for (int i = 0; i < outline.length; i++) {
			double up = outline[i][1] + lift;
			placed[i] = new Vec3(center + outline[i][0] + lean * up, base - up, z);
		}
		return placed;
	}

	private static final int TAIL_SEGMENTS = 14;
	private static final int TAIL_SIDES = 10;

	/** A big fluffy tail curling up from the lower back, swaying gently, faster as you walk, with a pale tip. Body space. */
	private void drawTail(PoseStack.Pose entry, VertexConsumer consumer, float age, float walk) {
		float sway = (float) Math.sin(age * 0.12) * (0.06f + walk * 0.08f);
		double size = tailSize.get();
		Vec3 root = new Vec3(0, 0.64, 0.12);
		Vec3[] path = new Vec3[TAIL_SEGMENTS + 1];
		float[] radius = new float[TAIL_SEGMENTS + 1];
		for (int i = 0; i <= TAIL_SEGMENTS; i++) {
			double t = i / (double) TAIL_SEGMENTS;
			Vec3 point = new Vec3(sway * t * t * 2, 0.64 - 0.5 * Math.pow(t, 1.4), 0.12 + 0.36 * t - 0.12 * t * t);
			// Grown or shrunk from where it joins the back, so it stays attached at any size
			path[i] = root.add(point.subtract(root).scale(size));
			// Bushy like a fox's: swelling out in the middle, puffing at the tip
			radius[i] = (float) ((0.05 + 0.04 * Math.sin(t * Math.PI) + (t > 0.75 ? 0.012 * Math.sin((t - 0.75) / 0.25 * Math.PI) : 0)) * size);
		}
		for (int i = 0; i < TAIL_SEGMENTS; i++) {
			int segmentColor = i >= TAIL_SEGMENTS * 0.75 ? belly.get() : fur.get();
			ring(consumer, entry, path, radius, i, segmentColor);
		}
		Vec3 end = path[TAIL_SEGMENTS];
		Vec3 direction = end.subtract(path[TAIL_SEGMENTS - 1]).normalize();
		Vec3 cap = end.add(direction.scale(radius[TAIL_SEGMENTS]));
		Vec3[] last = ringPoints(path, radius, TAIL_SEGMENTS);
		for (int s = 0; s < TAIL_SIDES; s++) triangle(consumer, entry, last[s], last[(s + 1) % TAIL_SIDES], cap, belly.get());
	}

	private void ring(VertexConsumer consumer, PoseStack.Pose entry, Vec3[] path, float[] radius, int i, int segmentColor) {
		Vec3[] a = ringPoints(path, radius, i);
		Vec3[] b = ringPoints(path, radius, i + 1);
		for (int s = 0; s < TAIL_SIDES; s++) {
			int n = (s + 1) % TAIL_SIDES;
			quad(consumer, entry, a[s], a[n], b[n], b[s], segmentColor);
		}
	}

	/** The ring of points around the tail at point {@code i} of its path. */
	private static Vec3[] ringPoints(Vec3[] path, float[] radius, int i) {
		Vec3 tangent = path[Math.min(path.length - 1, i + 1)].subtract(path[Math.max(0, i - 1)]).normalize();
		Vec3 u = tangent.cross(new Vec3(1, 0, 0)).normalize();
		Vec3 v = tangent.cross(u).normalize();
		Vec3[] points = new Vec3[TAIL_SIDES];
		for (int s = 0; s < TAIL_SIDES; s++) {
			double angle = Math.PI * 2 * s / TAIL_SIDES;
			points[s] = path[i].add(u.scale(Math.cos(angle) * radius[i])).add(v.scale(Math.sin(angle) * radius[i]));
		}
		return points;
	}

	private static void triangle(VertexConsumer consumer, PoseStack.Pose entry, Vec3 a, Vec3 b, Vec3 c, int color) {
		quad(consumer, entry, a, b, c, c, color);
	}

	/** One face, shaded by how much it faces the light. Drawn from both sides, so winding never hides it. */
	private static void quad(VertexConsumer consumer, PoseStack.Pose entry, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
		Vec3 normal = b.subtract(a).cross(c.subtract(a));
		if (normal.lengthSqr() < 1e-12) normal = c.subtract(a).cross(d.subtract(a));
		float light = (float) (0.62 + 0.38 * Math.abs(normal.normalize().dot(LIGHT)));
		int shaded = shade(color, light);
		vertex(consumer, entry, a, shaded);
		vertex(consumer, entry, b, shaded);
		vertex(consumer, entry, c, shaded);
		vertex(consumer, entry, d, shaded);
		vertex(consumer, entry, d, shaded);
		vertex(consumer, entry, c, shaded);
		vertex(consumer, entry, b, shaded);
		vertex(consumer, entry, a, shaded);
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose entry, Vec3 point, int color) {
		consumer.addVertex(entry, (float) point.x, (float) point.y, (float) point.z).setColor(color);
	}

	private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
		return a.add(b.subtract(a).scale(t));
	}

	private static int shade(int color, float factor) {
		int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
		int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
		int b = Math.min(255, (int) ((color & 0xFF) * factor));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
