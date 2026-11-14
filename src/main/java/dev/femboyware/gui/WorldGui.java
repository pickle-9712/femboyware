package dev.femboyware.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.Mc;
import dev.femboyware.util.ProjectionUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * The ClickGUI as a floating screen in the world: while it's on, the GUI is drawn into a texture of
 * its own (by a second GUI renderer, after the normal one) instead of onto your screen, and that
 * texture hangs where you were looking when it opened, facing you, and stays there. Your cursor is
 * traced onto it, so clicking it works like clicking the normal one.
 */
public final class WorldGui {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("femboyware", "dynamic/world_gui");
	/** How far in front of you it appears, at most. */
	private static final double DISTANCE = 2.0;

	//? if >=26.1 {
	/*private static net.minecraft.client.renderer.state.gui.GuiRenderState state;
	*///?} else {
	private static net.minecraft.client.gui.render.state.GuiRenderState state;
	//?}
	private static GuiRenderer renderer;
	private static RenderTarget target;
	private static boolean registered;
	private static TargetTexture texture;

	/** Where the screen hangs (its centre), which way is right and up along it, and its size in blocks. */
	private static Vec3 center, right, up;
	private static double width, height;
	/** The ClickGUI it was placed for; a new one gets placed again. */
	private static ClickGuiScreen placedFor;

	private WorldGui() {
	}

	private static ClickGuiModule module() {
		return FemboyWare.getModules() == null ? null : FemboyWare.getModules().get(ClickGuiModule.class);
	}

	/** Whether {@code screen} is showing in the world rather than on your screen. */
	public static boolean showing(ClickGuiScreen screen) {
		ClickGuiModule module = module();
		return module != null && module.worldScreen() && Minecraft.getInstance().level != null && Minecraft.getInstance().player != null
			&& !screen.isHudEditing() && placedFor == screen && center != null;
	}

	/** True while the GUI is being drawn into the world screen's texture. */
	public static boolean drawing;

	/** The renderer drawing the world screen, so its draws go to the texture (see GuiRendererMixin). */
	public static boolean isOurs(Object guiRenderer) {
		return guiRenderer != null && guiRenderer == renderer;
	}

	public static RenderTarget target() {
		return target;
	}

	/** Hangs the screen in front of you, facing you, where it stays until it's closed. */
	public static void place(ClickGuiScreen screen) {
		Minecraft mc = Minecraft.getInstance();
		ClickGuiModule module = module();
		if (module == null || !module.worldScreen() || mc.player == null || mc.level == null) {
			placedFor = null;
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1);
		// Not through a wall: in front of whatever's in the way
		double distance = DISTANCE;
		HitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(DISTANCE)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() != HitResult.Type.MISS) distance = Math.max(0.6, hit.getLocation().distanceTo(eye) - 0.25);
		center = eye.add(look.scale(distance));
		// Facing you: right is across your view, up is your view's up
		Vec3 r = look.cross(new Vec3(0, 1, 0));
		if (r.lengthSqr() < 1e-6) r = Vec3.directionFromRotation(0, mc.player.getYRot() + 90);
		right = r.normalize();
		up = right.cross(look).normalize();
		height = module.worldSize() * distance / DISTANCE;
		double aspect = mc.getWindow().getGuiScaledHeight() == 0 ? 16 / 9.0 : mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getGuiScaledHeight();
		width = height * aspect;
		placedFor = screen;
	}

	public static void forget(ClickGuiScreen screen) {
		if (placedFor == screen) placedFor = null;
	}

	// ---------------------------------------------------------------- the cursor

	/** The GUI coordinates the cursor's pointing at on the screen in the world, or far off it if it isn't on it. */
	public static double[] toGui(double mouseX, double mouseY) {
		Minecraft mc = Minecraft.getInstance();
		double guiW = mc.getWindow().getGuiScaledWidth(), guiH = mc.getWindow().getGuiScaledHeight();
		if (center == null || guiW <= 0 || guiH <= 0) return new double[]{-10_000, -10_000};
		// The cursor as a ray from the camera, through the last frame's view and projection
		float ndcX = (float) (mouseX / guiW * 2 - 1), ndcY = (float) (1 - mouseY / guiH * 2);
		Matrix4f inverse = new Matrix4f(ProjectionUtil.projection()).mul(ProjectionUtil.view()).invert();
		Vector4f a = inverse.transform(new Vector4f(ndcX, ndcY, -0.5f, 1));
		Vector4f b = inverse.transform(new Vector4f(ndcX, ndcY, 0.5f, 1));
		Vec3 p0 = new Vec3(a.x / a.w, a.y / a.w, a.z / a.w), p1 = new Vec3(b.x / b.w, b.y / b.w, b.z / b.w);
		Vec3 dir = p1.subtract(p0);
		Vec3 cam = ProjectionUtil.camera();
		Vec3 c = center.subtract(cam);
		Vec3 normal = right.cross(up);
		double denominator = dir.dot(normal);
		if (Math.abs(denominator) < 1e-9) return new double[]{-10_000, -10_000};
		double t = c.subtract(p0).dot(normal) / denominator;
		Vec3 point = p0.add(dir.scale(t)).subtract(c);
		// Behind you doesn't count
		if (point.add(c).dot(c) <= 0) return new double[]{-10_000, -10_000};
		double u = point.dot(right) / width + 0.5, v = 0.5 - point.dot(up) / height;
		return new double[]{u * guiW, v * guiH};
	}

	/** The cursor's GUI coordinates on the world screen right now. */
	public static double[] cursor() {
		Minecraft mc = Minecraft.getInstance();
		double x = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / Math.max(1, mc.getWindow().getScreenWidth());
		double y = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / Math.max(1, mc.getWindow().getScreenHeight());
		return toGui(x, y);
	}

	// ---------------------------------------------------------------- drawing the GUI into the texture

	/** After the normal GUI each frame: draws the ClickGUI into the world screen's texture. */
	public static void renderPass() {
		Minecraft mc = Minecraft.getInstance();
		if (!(Mc.screen() instanceof ClickGuiScreen screen) || !showing(screen)) return;
		int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
		if (w <= 0 || h <= 0) return;
		if (target == null) {
			//? if >=26.3 {
			/*target = new TextureTarget("femboyware world gui", w, h, com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, com.mojang.renderpearl.api.GpuFormat.D32_FLOAT);
			*///?} else if >=26.1 {
			/*target = new TextureTarget("femboyware world gui", w, h, true, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
			*///?} else {
			target = new TextureTarget("femboyware world gui", w, h, true);
			//?}
		} else if (target.width != w || target.height != h) {
			target.resize(w, h);
		}
		if (!registered) {
			texture = new TargetTexture();
			mc.getTextureManager().register(TEXTURE, texture);
			registered = true;
		}
		texture.sync();
		if (state == null) {
			//? if >=26.1 {
			/*state = new net.minecraft.client.renderer.state.gui.GuiRenderState();
			renderer = new GuiRenderer(state, mc.gameRenderer.featureRenderDispatcher(), java.util.List.of());
			*///?} else {
			state = new net.minecraft.client.gui.render.state.GuiRenderState();
			renderer = new GuiRenderer(state, mc.renderBuffers().bufferSource(), mc.gameRenderer.getSubmitNodeStorage(), mc.gameRenderer.getFeatureRenderDispatcher(), java.util.List.of());
			//?}
		}
		// Clear to see-through, then draw the ClickGUI into it with the cursor traced onto it
		//? if >=26.1 {
		/*RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), new org.joml.Vector4f(0, 0, 0, 0), target.getDepthTexture(), 0.0);
		*///?} else {
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
		//?}
		double[] mouse = cursor();
		GuiGraphics context = new GuiGraphics(mc, state, (int) mouse[0], (int) mouse[1]);
		drawing = true;
		try {
			screen.paintForWorld(context, (int) mouse[0], (int) mouse[1], mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			//? if >=26.1 {
			/*renderer.render();
			renderer.endFrame();
			*///?} else {
			renderer.render(((dev.femboyware.mixin.GameRendererAccessor) mc.gameRenderer).femboyware$getFog().getBuffer(net.minecraft.client.renderer.fog.FogRenderer.FogMode.NONE));
			//?}
		} finally {
			drawing = false;
		}
	}

	/** The world screen's texture, as the render target the GUI was drawn into. */
	private static final class TargetTexture extends AbstractTexture {
		TargetTexture() {
			sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}

		/** Points at the target's texture (again after a resize, which makes a new one). */
		void sync() {
			texture = target.getColorTexture();
			textureView = target.getColorTextureView();
		}

		@Override
		public void close() {
			// The render target owns it
		}
	}

	// ---------------------------------------------------------------- drawing the screen in the world

	/** Hangs the texture in the world, from the ClickGUI module's world drawing. */
	public static void drawPanel(Render3DEvent event) {
		if (!(Mc.screen() instanceof ClickGuiScreen screen) || !showing(screen) || target == null || !registered) return;
		Vec3 cam = event.camera().pos;
		Vec3 c = center.subtract(cam);
		Vec3 r = right.scale(width / 2), u = up.scale(height / 2);
		Vec3 topLeft = c.subtract(r).add(u), topRight = c.add(r).add(u), bottomRight = c.add(r).subtract(u), bottomLeft = c.subtract(r).subtract(u);
		VertexConsumer quads = FxRender.textured(event, TEXTURE, false, false);
		PoseStack.Pose pose = event.matrices().last();
		// Render targets are stored bottom row first
		FxRender.textured(quads, pose, (float) bottomLeft.x, (float) bottomLeft.y, (float) bottomLeft.z, 0, 0, 0xFFFFFFFF);
		FxRender.textured(quads, pose, (float) bottomRight.x, (float) bottomRight.y, (float) bottomRight.z, 1, 0, 0xFFFFFFFF);
		FxRender.textured(quads, pose, (float) topRight.x, (float) topRight.y, (float) topRight.z, 1, 1, 0xFFFFFFFF);
		FxRender.textured(quads, pose, (float) topLeft.x, (float) topLeft.y, (float) topLeft.z, 0, 1, 0xFFFFFFFF);
	}
}
