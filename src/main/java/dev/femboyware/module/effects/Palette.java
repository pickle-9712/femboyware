package dev.femboyware.module.effects;

import dev.femboyware.gui.Theme;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;

import java.awt.Color;

/** The colour settings every effect shares, and the colour they give at each point along an effect. */
public final class Palette {
	public enum Mode { STATIC, GRADIENT, RAINBOW, PASTEL, THEME }

	private final EnumSetting<Mode> mode;
	private final ColorSetting first;
	private final ColorSetting second;
	private final NumberSetting flow;

	Palette(EnumSetting<Mode> mode, ColorSetting first, ColorSetting second, NumberSetting flow) {
		this.mode = mode;
		this.first = first;
		this.second = second;
		this.flow = flow;
	}

	/**
	 * The colour {@code t} of the way along an effect, where 0 and 1 meet so loops are seamless.
	 * Gradients and rainbows drift along over time.
	 */
	public int at(float t) {
		float phase = t + (float) (FxModule.time() * flow.get() * 0.25);
		return switch (mode.get()) {
			case STATIC -> first.get();
			case GRADIENT -> FxRender.mix(first.get(), second.get(), 0.5f - 0.5f * (float) Math.cos(phase * Math.PI * 2));
			case RAINBOW -> 0xFF000000 | Color.HSBtoRGB(phase - (float) Math.floor(phase), 0.6f, 1f);
			case PASTEL -> {
				// Pink to light blue and back, like the menu's pastel wave
				float wave = 0.5f - 0.5f * (float) Math.cos(phase * Math.PI * 2);
				yield 0xFF000000 | Color.HSBtoRGB(0.55f + wave * 0.38f, 0.45f, 1f);
			}
			case THEME -> Theme.accent();
		};
	}

	static Palette create(FxModule module, int firstDefault, int secondDefault) {
		EnumSetting<Mode> mode = module.addSetting(new EnumSetting<>("Color Mode", "Static, a two colour gradient, rainbow, pastel or the menu accent", Mode.GRADIENT));
		ColorSetting first = module.addSetting(new ColorSetting("Color", "Main colour", firstDefault))
			.visibleWhen(() -> mode.is(Mode.STATIC) || mode.is(Mode.GRADIENT));
		ColorSetting second = module.addSetting(new ColorSetting("Second Color", "Colour the gradient fades into", secondDefault))
			.visibleWhen(() -> mode.is(Mode.GRADIENT));
		NumberSetting flow = module.addSetting(new NumberSetting("Color Flow", "How fast colours drift along the effect", 1, 0, 5, 0.1))
			.visibleWhen(() -> !mode.is(Mode.STATIC) && !mode.is(Mode.THEME));
		return new Palette(mode, first, second, flow);
	}
}
