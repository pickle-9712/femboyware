package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;

/**
 * How you look to yourself in third person: see-through like a ghost, tinted any colour (or
 * cycling through the rainbow), and outlined with a glow. Only on your screen.
 */
public class SelfVisualsModule extends Module {
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How solid you are (below 1 you're see-through)", 1, 0.1, 1, 0.05));
	private final BoolSetting tint = add(new BoolSetting("Tint", "Colour your model", false));
	private final ColorSetting tintColor = add(new ColorSetting("Tint Color", "The tint", 0xFFFF8AD8));
	private final BoolSetting rainbow = add(new BoolSetting("Rainbow", "The tint cycles through the rainbow", false));
	private final BoolSetting glow = add(new BoolSetting("Glow Outline", "A glowing outline round you", false));
	private final ColorSetting glowColor = add(new ColorSetting("Glow Color", "The outline's colour", 0xFFFF5FD2));

	public SelfVisualsModule() {
		super("Self Visuals", "See-through, tinted or glowing self in third person", Category.COSMETIC);
	}

	private boolean mine(LivingEntityRenderState state) {
		return isEnabled() && mc.player != null && state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar && avatar.id == mc.player.getId();
	}

	/** From LivingEntityRendererMixin: whether you're drawn see-through. */
	public boolean ghost(LivingEntityRenderState state) {
		return mine(state) && opacity.get() < 0.999;
	}

	/** From LivingEntityRendererMixin: your model's tint, with its see-through alpha. */
	public int tint(LivingEntityRenderState state, int original) {
		if (!mine(state)) return original;
		int color = original;
		if (tint.get()) {
			int c = rainbow.get() ? java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000) / 4000f, 0.55f, 1) : tintColor.get();
			color = multiply(color, c | 0xFF000000);
		}
		int alpha = (int) (((color >>> 24) & 0xFF) * opacity.get());
		return (color & 0x00FFFFFF) | alpha << 24;
	}

	/** From LivingEntityRendererMixin when your render state's filled: the glow outline. */
	public void outline(LivingEntity entity, LivingEntityRenderState state) {
		if (!isEnabled() || entity != mc.player || !glow.get()) return;
		int c = rainbow.get() ? java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000) / 4000f, 0.7f, 1) : glowColor.get();
		state.outlineColor = c & 0xFFFFFF;
	}

	private static int multiply(int a, int b) {
		int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255, g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255, bl = (a & 0xFF) * (b & 0xFF) / 255;
		int al = ((a >>> 24) & 0xFF) * ((b >>> 24) & 0xFF) / 255;
		return al << 24 | r << 16 | g << 8 | bl;
	}
}
