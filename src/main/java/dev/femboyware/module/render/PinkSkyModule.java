package dev.femboyware.module.render;

import dev.femboyware.util.Mc;
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
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * Tints the world towards a colour (pink by default). The sky, fog, clouds and sunsets are
 * recoloured at the source, and with Everything on a colour grade recolours every block, mob,
 * plant and the water too. Every colour keeps its own brightness, so nights stay dark and
 * contrast survives. ESP, nametags and the HUD draw after the grade, so they keep their colours.
 * Client-side only.
 */
public class PinkSkyModule extends Module {
	private static final Identifier GRADE = Identifier.fromNamespaceAndPath("femboyware", "pink");

	private final ColorSetting color = add(new ColorSetting("Color", "The colour to tint towards", 0xFFFF77C8));
	private final NumberSetting strength = add(new NumberSetting("Strength", "How far the sky, fog and clouds are tinted", 0.8, 0, 1, 0.05));
	private final BoolSetting everything = add(new BoolSetting("Everything", "Tint the whole world: blocks, grass, mobs, water", true));
	private final NumberSetting everythingStrength = add(new NumberSetting("Everything Strength", "How pink the whole world gets", 0.75, 0, 1, 0.05)).visibleWhen(everything::get);
	private final BoolSetting sky = add(new BoolSetting("Sky", "Tint the sky", true));
	private final BoolSetting fog = add(new BoolSetting("Fog", "Tint the fog on the horizon", true));
	private final BoolSetting clouds = add(new BoolSetting("Clouds", "Tint the clouds", true));
	private final BoolSetting sunset = add(new BoolSetting("Sunset", "Tint sunrises and sunsets", true));
	private final BoolSetting water = add(new BoolSetting("Water Fog", "Tint the fog underwater", false));
	private final BoolSetting world = add(new BoolSetting("World Tint", "Tint the daylight falling on the world", false));
	private final NumberSetting worldStrength = add(new NumberSetting("World Strength", "How pink the daylight is", 0.35, 0, 1, 0.05)).visibleWhen(world::get);

	public PinkSkyModule() {
		super("Pink Sky", "Turns the sky and the whole world pink", Category.SKY);
	}

	/** Colour grades everything drawn in the world so far; called just before ESP and the like draw. */
	public void gradeWorld() {
		if (!isEnabled() || !everything.get() || everythingStrength.get() <= 0) return;
		PostChain effect = mc.getShaderManager().getPostChain(GRADE, LevelTargetBundle.MAIN_TARGETS);
		if (effect == null) return;
		PostPass pass = ((PostEffectProcessorAccessor) effect).femboyware$getPasses().get(0);
		GpuBuffer config = ((PostEffectPassAccessor) pass).femboyware$getUniformBuffers().get("PinkConfig");
		if (config == null) return;
		int tint = color.get();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, 16)
				.putVec4(((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, everythingStrength.getFloat())
				.get();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(config.slice(), data);
		}
		// The effect runs as a one-pass frame graph over the main framebuffer
		RenderTarget main = Mc.mainRenderTarget();
		FrameGraphBuilder graph = new FrameGraphBuilder();
		PostChain.TargetBundle targets = PostChain.TargetBundle.of(PostChain.MAIN_TARGET_ID, graph.importExternal("main", main));
		effect.addToFrame(graph, main.width, main.height, targets);
		graph.execute(((GameRendererAccessor) mc.gameRenderer).femboyware$getPool());
	}

	/** Called from EnvironmentAttributeInterpolatorMixin with every visual attribute the client reads. */
	public Object modify(EnvironmentAttribute<?> attribute, Object value) {
		if (!isEnabled() || !(value instanceof Integer argb)) return value;
		float amount;
		if (attribute == EnvironmentAttributes.SKY_COLOR) amount = sky.get() ? strength.getFloat() : 0;
		else if (attribute == EnvironmentAttributes.FOG_COLOR) amount = fog.get() ? strength.getFloat() : 0;
		else if (attribute == EnvironmentAttributes.CLOUD_COLOR) amount = clouds.get() ? strength.getFloat() : 0;
		else if (attribute == EnvironmentAttributes.SUNRISE_SUNSET_COLOR) amount = sunset.get() ? strength.getFloat() : 0;
		else if (attribute == EnvironmentAttributes.WATER_FOG_COLOR) amount = water.get() ? strength.getFloat() : 0;
		else if (attribute == EnvironmentAttributes.SKY_LIGHT_COLOR) amount = world.get() ? worldStrength.getFloat() : 0;
		else return value;
		return amount <= 0 ? value : tint(argb, color.get(), amount);
	}

	/** {@code argb} moved {@code amount} of the way to {@code tint} at the same brightness, keeping its alpha. */
	private static int tint(int argb, int tint, float amount) {
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		int tr = (tint >> 16) & 0xFF, tg = (tint >> 8) & 0xFF, tb = tint & 0xFF;
		float scale = luminance(r, g, b) / Math.max(1f, luminance(tr, tg, tb));
		int nr = (int) Mth.lerp(amount, r, Math.min(255, tr * scale));
		int ng = (int) Mth.lerp(amount, g, Math.min(255, tg * scale));
		int nb = (int) Mth.lerp(amount, b, Math.min(255, tb * scale));
		return (argb & 0xFF000000) | (nr << 16) | (ng << 8) | nb;
	}

	private static float luminance(int r, int g, int b) {
		return 0.299f * r + 0.587f * g + 0.114f * b;
	}
}
