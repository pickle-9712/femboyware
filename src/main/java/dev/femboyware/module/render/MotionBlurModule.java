package dev.femboyware.module.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.femboyware.mixin.GameRendererAccessor;
import dev.femboyware.mixin.PostEffectPassAccessor;
import dev.femboyware.mixin.PostEffectProcessorAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Mc;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

/**
 * Motion blur on the world as you turn and move: each frame is blended with the frames before it,
 * so fast motion smears. Your hand and the HUD stay sharp.
 */
public class MotionBlurModule extends Module {
	private static final Identifier EFFECT = Identifier.fromNamespaceAndPath("femboyware", "motion_blur");

	private final NumberSetting strength = add(new NumberSetting("Strength", "How long the smear trails (at 60 fps)", 0.55, 0.05, 0.9, 0.05));

	private long last;

	public MotionBlurModule() {
		super("Motion Blur", "The world smears as you turn and move", Category.COSMETIC);
	}

	@Override
	protected void onDisable() {
		last = 0;
	}

	/** After the world's drawn, before the hand: blends in the frames before. */
	public void apply() {
		if (!isEnabled() || mc.level == null) return;
		PostChain effect = mc.getShaderManager().getPostChain(EFFECT, LevelTargetBundle.MAIN_TARGETS);
		if (effect == null) return;
		long now = System.nanoTime();
		float dt = last == 0 ? 1 / 60f : Math.min(0.25f, (now - last) / 1e9f);
		last = now;
		// The same smear whatever the frame rate: keep strength^(frames at 60fps this frame lasted)
		float keep = (float) Math.pow(strength.get(), dt * 60);
		PostPass pass = ((PostEffectProcessorAccessor) effect).femboyware$getPasses().get(0);
		GpuBuffer config = ((PostEffectPassAccessor) pass).femboyware$getUniformBuffers().get("BlurConfig");
		if (config == null) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, 16).putVec4(keep, 0, 0, 0).get();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(config.slice(), data);
		}
		RenderTarget main = Mc.mainRenderTarget();
		FrameGraphBuilder graph = new FrameGraphBuilder();
		PostChain.TargetBundle targets = PostChain.TargetBundle.of(PostChain.MAIN_TARGET_ID, graph.importExternal("main", main));
		effect.addToFrame(graph, main.width, main.height, targets);
		graph.execute(((GameRendererAccessor) mc.gameRenderer).femboyware$getPool());
	}
}
