package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.setting.BoolSetting;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Turns your skin into a Coinbase support agent: the blue polo with the logo, a lanyard and badge,
 * navy slacks, and a headset with a mic, modelled on your head. You keep your cape.
 */
public class CoinbaseAgentModule extends Module {
	private static final Identifier SKIN = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/coinbase_agent.png");
	/** The headset's colours, a 4x4 grid of 4-pixel swatches: see {@link #SET} and the rest. */
	private static final Identifier HEADSET = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/headset.png");
	private static final int SET = 0, PAD = 1, BOOM = 2, BLUE = 3, FOAM = 4, WHITE = 5, DARK_BLUE = 6;

	private final BoolSetting onFakes = add(new BoolSetting("Fake Players", "Your fake players wear it too", true));
	private final BoolSetting headset = add(new BoolSetting("Headset", "The headset and mic on your head", true));
	private final BoolSetting cap = add(new BoolSetting("Cap", "A blue Coinbase baseball cap with the C on the front", true));

	public CoinbaseAgentModule() {
		super("Coinbase Agent", "Turns your skin into a Coinbase support agent: blue polo, badge, cap and headset", Category.LARP);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> player) helper.register(new Headset(asContext(player)));
		});
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<AvatarRenderState, PlayerModel> asContext(AvatarRenderer<?> renderer) {
		return (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer;
	}

	private boolean wears(int id) {
		if (!isEnabled() || mc.player == null) return false;
		if (FemboyWare.getModules().get(FbiRaidModule.class).isYourStandIn(id)) return true;
		return id == mc.player.getId() || onFakes.get() && FemboyWare.getModules().get(FakePlayerModule.class).isFake(id);
	}

	/** Read by AbstractClientPlayerEntityMixin: the agent skin for you (and your fake players), with your own cape. */
	public PlayerSkin skinFor(AbstractClientPlayer player, PlayerSkin textures) {
		if (!wears(player.getId())) return textures;
		return new PlayerSkin(new net.minecraft.core.ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("femboyware", "coinbase_agent"), SKIN),
			textures.cape(), textures.elytra(), PlayerModelType.WIDE, true);
	}

	private static RenderType cutout(Identifier texture) {
		//? if >=26.1 {
		/*return RenderTypes.entityCutout(texture);
		*///?} else {
		return RenderTypes.entityCutoutNoCull(texture);
		//?}
	}

	private class Headset extends RenderLayer<AvatarRenderState, PlayerModel> {
		Headset(RenderLayerParent<AvatarRenderState, PlayerModel> context) {
			super(context);
		}

		@Override
		public void submit(PoseStack matrices, SubmitNodeCollector queue, int light, AvatarRenderState state, float headYaw, float headPitch) {
			// Not on you while you're invisible (the FBI's walking your stand-in out, and it wears them instead)
			if (!headset.get() && !cap.get() || state.isInvisible || !wears(state.id)) return;
			boolean withCap = cap.get(), withHeadset = headset.get();
			matrices.pushPose();
			getParentModel().head.translateAndRotate(matrices);
			queue.submitCustomGeometry(matrices, cutout(HEADSET), (pose, c) -> {
				if (withCap) drawCap(pose, c, light);
				if (withHeadset) drawHeadset(pose, c, light, withCap);
			});
			matrices.popPose();
		}
	}

	/**
	 * The headset in the head's space (blocks; the head runs x and z from -0.25 to 0.25, y from -0.5
	 * at the top down to 0; the face is at -z, your right at -x): a band over the top, a cushioned cup
	 * over each ear with the logo on the outside, and a boom from the right cup round to the mouth.
	 */
	private static void drawHeadset(PoseStack.Pose pose, VertexConsumer c, int light, boolean overCap) {
		// The band: over the top (over the cap's crown when you've one on), down each side to the cups
		// (Over the cap it sits clear of the crown's top, its ends sunk into it: no two faces flush, so nothing flickers)
		float top = overCap ? -0.605f : -0.535f, sx = overCap ? 0.28f : 0.25f;
		box(c, pose, light, -0.2f, top, -0.04f, 0.2f, top + 0.03f, 0.04f, SET);
		box(c, pose, light, -sx - 0.025f, top + 0.01f, -0.035f, -0.18f, top + (overCap ? 0.055f : 0.045f), 0.035f, SET);
		box(c, pose, light, 0.18f, top + 0.01f, -0.035f, sx + 0.025f, top + (overCap ? 0.055f : 0.045f), 0.035f, SET);
		box(c, pose, light, -sx - 0.025f, top + 0.03f, -0.03f, -sx, -0.3f, 0.03f, SET);
		box(c, pose, light, sx, top + 0.03f, -0.03f, sx + 0.025f, -0.3f, 0.03f, SET);
		// The cups, with a cushion against the head and the blue logo on the outside
		for (int side : new int[]{-1, 1}) {
			float in = side * 0.25f, out = side * 0.33f;
			box(c, pose, light, Math.min(in, side * 0.26f), -0.33f, -0.1f, Math.max(in, side * 0.26f), -0.12f, 0.1f, PAD);
			box(c, pose, light, Math.min(side * 0.26f, out), -0.34f, -0.11f, Math.max(side * 0.26f, out), -0.11f, 0.11f, SET);
			float face = side * 0.331f;
			box(c, pose, light, Math.min(out, face), -0.27f, -0.045f, Math.max(out, face), -0.18f, 0.045f, BLUE);
		}
		// The mic boom: forward from the right cup, then in toward the mouth, with the foam tip
		box(c, pose, light, -0.325f, -0.2f, -0.12f, -0.3f, -0.17f, -0.08f, BOOM);
		box(c, pose, light, -0.325f, -0.17f, -0.29f, -0.3f, -0.11f, -0.1f, BOOM);
		box(c, pose, light, -0.325f, -0.13f, -0.31f, -0.1f, -0.11f, -0.285f, BOOM);
		box(c, pose, light, -0.11f, -0.145f, -0.32f, -0.05f, -0.095f, -0.27f, FOAM);
	}

	/**
	 * The cap, in the head's space like the headset: a Coinbase-blue crown a little bigger than the
	 * head, sat low over the hair, a panel seam and button on top, the brim out over the face with a
	 * darker underside, and the white C on the front.
	 */
	private static void drawCap(PoseStack.Pose pose, VertexConsumer c, int light) {
		float w = 0.27f;
		// Every piece either clear of the next or sunk into it, never flush: flush faces flicker against
		// each other when the head tilts, as it does all through the emotes
		box(c, pose, light, -w, -0.535f, -w, w, -0.39f, w, BLUE);
		box(c, pose, light, -w + 0.03f, -0.558f, -w + 0.03f, w - 0.03f, -0.53f, w - 0.03f, BLUE);
		box(c, pose, light, -0.005f, -0.564f, -w + 0.035f, 0.005f, -0.552f, w - 0.035f, DARK_BLUE);
		box(c, pose, light, -0.025f, -0.578f, -0.025f, 0.025f, -0.556f, 0.025f, DARK_BLUE);
		// The brim, its back sunk into the crown: blue on top, darker under, curving down a touch at the tip
		box(c, pose, light, -0.235f, -0.416f, -0.46f, 0.235f, -0.399f, -w + 0.012f, BLUE);
		box(c, pose, light, -0.229f, -0.403f, -0.454f, 0.229f, -0.388f, -w + 0.006f, DARK_BLUE);
		box(c, pose, light, -0.2f, -0.408f, -0.49f, 0.2f, -0.393f, -0.452f, BLUE);
		// The white C on the front of the crown
		float f1 = -w - 0.006f, f2 = -w;
		box(c, pose, light, -0.035f, -0.515f, f1, 0.045f, -0.49f, f2, WHITE);
		box(c, pose, light, -0.06f, -0.515f, f1, -0.035f, -0.425f, f2, WHITE);
		box(c, pose, light, -0.035f, -0.45f, f1, 0.045f, -0.425f, f2, WHITE);
	}

	/** A box coloured with one swatch of the headset texture, its faces drawn both ways round. */
	private static void box(VertexConsumer c, PoseStack.Pose pose, int light, float x1, float y1, float z1, float x2, float y2, float z2, int swatch) {
		float u = (swatch % 4) * 0.25f + 0.125f, v = (swatch / 4) * 0.25f + 0.125f;
		float[][] corners = {
			{x1, y1, z1}, {x2, y1, z1}, {x2, y2, z1}, {x1, y2, z1},
			{x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}, {x1, y2, z2}};
		int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {4, 5, 1, 0}, {3, 2, 6, 7}};
		float[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
		for (int f = 0; f < 6; f++) {
			int[] q = faces[f];
			float[] n = normals[f];
			for (int i = 0; i < 4; i++) vertex(c, pose, corners[q[i]], u, v, light, n);
			for (int i = 3; i >= 0; i--) vertex(c, pose, corners[q[i]], u, v, light, n);
		}
	}

	private static void vertex(VertexConsumer c, PoseStack.Pose pose, float[] p, float u, float v, int light, float[] n) {
		c.addVertex(pose, p[0], p[1], p[2]).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, n[0], n[1], n[2]);
	}
}
