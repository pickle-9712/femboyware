package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.ColorUtil;
import net.minecraft.client.gui.DrawContext;

/** Flashes the screen with a colour when you take damage. */
public class DamageTintModule extends Module {
	private final ColorSetting color = add(new ColorSetting("Color", "Tint colour, alpha is the peak strength", 0x70FF2040));

	public DamageTintModule() {
		super("Damage Tint", "Tints your screen when you get hurt", Category.COSMETIC);
	}

	@Override
	public void onRender2D(DrawContext context, float tickDelta) {
		int maxHurt = mc.player.maxHurtTime;
		if (mc.player.hurtTime <= 0 || maxHurt <= 0) return;
		float strength = Math.max(0, (mc.player.hurtTime - tickDelta) / maxHurt);
		int alpha = (int) (color.getAlpha() * strength);
		if (alpha <= 0) return;
		context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), ColorUtil.withAlpha(color.get(), alpha));
	}
}
