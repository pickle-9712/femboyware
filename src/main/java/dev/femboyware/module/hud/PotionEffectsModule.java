package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Your potion effects with their icon, level and time left, flashing as they run out. */
public class PotionEffectsModule extends HudElement {
	private static final int LINE = 14;
	private static final int WIDTH = 120;

	private final BoolSetting hideVanilla = add(new BoolSetting("Hide Vanilla", "Hide Minecraft's own effect icons in the corner", true));

	private final ColorSetting endingColor = add(new ColorSetting("Ending Color", "Time left on an effect about to run out", 0xFFFF6060)).visibleWhen(this::usesCustomColors);

	public PotionEffectsModule() {
		super("Potion Effects", "A list of your potion effects and time left", 1, 25);
	}

	/** Whether Minecraft's own effect icons are hidden; read by InGameHudMixin. */
	public boolean hidesVanilla() {
		return isEnabled() && hideVanilla.get();
	}

	private List<StatusEffectInstance> effects(boolean editing) {
		List<StatusEffectInstance> effects = new ArrayList<>();
		if (mc.player != null) effects.addAll(mc.player.getStatusEffects());
		if (effects.isEmpty() && editing) {
			effects.add(new StatusEffectInstance(StatusEffects.SPEED, 2400, 1));
			effects.add(new StatusEffectInstance(StatusEffects.STRENGTH, 1200, 1));
		}
		effects.sort(Comparator.comparingInt(StatusEffectInstance::getDuration).reversed());
		return effects;
	}

	@Override
	public int width(boolean editing) {
		return effects(editing).isEmpty() ? 0 : WIDTH;
	}

	@Override
	public int height(boolean editing) {
		return effects(editing).size() * LINE;
	}

	@Override
	protected void draw(DrawContext context, int left, int top, boolean editing) {
		List<StatusEffectInstance> effects = effects(editing);
		for (int i = 0; i < effects.size(); i++) {
			StatusEffectInstance effect = effects.get(i);
			int y = onBottom() ? top + height(editing) - (i + 1) * LINE : top + i * LINE;
			Glass.draw(context, left, y, left + WIDTH, y + LINE - 1, 4, backgroundColor(Theme.list()), false);
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, InGameHud.getEffectTexture(effect.getEffectType()), left + 3, y + 2, 9, 9);

			String name = Text.translatable(effect.getTranslationKey()).getString();
			if (effect.getAmplifier() > 0) name += " " + roman(effect.getAmplifier() + 1);
			int tint = 0xFF000000 | effect.getEffectType().value().getColor();
			context.drawTextWithShadow(mc.textRenderer, name, left + 15, y + 3, tint);

			String time = effect.isInfinite() ? "**:**" : StringHelper.formatTicks(effect.getDuration(), 20);
			// The time blinks in its last ten seconds
			boolean ending = !effect.isInfinite() && effect.getDuration() < 200 && (System.currentTimeMillis() / 400) % 2 == 0;
			context.drawTextWithShadow(mc.textRenderer, time, left + WIDTH - 4 - mc.textRenderer.getWidth(time), y + 3, ending ? endingColor.get() : mutedColor());
		}
	}

	private static String roman(int number) {
		return switch (number) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			case 6 -> "VI";
			case 7 -> "VII";
			case 8 -> "VIII";
			case 9 -> "IX";
			case 10 -> "X";
			default -> Integer.toString(number);
		};
	}
}
