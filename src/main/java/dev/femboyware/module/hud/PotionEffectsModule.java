package dev.femboyware.module.hud;

import dev.femboyware.util.Mc;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

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

	private List<MobEffectInstance> effects(boolean editing) {
		List<MobEffectInstance> effects = new ArrayList<>();
		if (mc.player != null) effects.addAll(mc.player.getActiveEffects());
		if (effects.isEmpty() && editing) {
			effects.add(new MobEffectInstance(MobEffects.SPEED, 2400, 1));
			effects.add(new MobEffectInstance(MobEffects.STRENGTH, 1200, 1));
		}
		effects.sort(Comparator.comparingInt(MobEffectInstance::getDuration).reversed());
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
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		List<MobEffectInstance> effects = effects(editing);
		for (int i = 0; i < effects.size(); i++) {
			MobEffectInstance effect = effects.get(i);
			int y = onBottom() ? top + height(editing) - (i + 1) * LINE : top + i * LINE;
			Glass.draw(context, left, y, left + WIDTH, y + LINE - 1, 4, backgroundColor(Theme.list()), false);
			context.blitSprite(RenderPipelines.GUI_TEXTURED, Mc.effectSprite(effect.getEffect()), left + 3, y + 2, 9, 9);

			String name = Component.translatable(effect.getDescriptionId()).getString();
			if (effect.getAmplifier() > 0) name += " " + roman(effect.getAmplifier() + 1);
			int tint = 0xFF000000 | effect.getEffect().value().getColor();
			context.drawString(mc.font, name, left + 15, y + 3, tint);

			String time = effect.isInfiniteDuration() ? "**:**" : StringUtil.formatTickDuration(effect.getDuration(), 20);
			// The time blinks in its last ten seconds
			boolean ending = !effect.isInfiniteDuration() && effect.getDuration() < 200 && (System.currentTimeMillis() / 400) % 2 == 0;
			context.drawString(mc.font, time, left + WIDTH - 4 - mc.font.width(time), y + 3, ending ? endingColor.get() : mutedColor());
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
