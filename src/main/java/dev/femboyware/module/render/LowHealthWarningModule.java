package dev.femboyware.module.render;

import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The screen edges glow and pulse like a heartbeat when your health is low, faster the lower it
 * gets. Ported from the Anubis client's Low Health Warning (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client), drawn with plain GUI fills instead of ImGui.
 */
public class LowHealthWarningModule extends Module {
	public enum Pulse {
		HEARTBEAT, BREATH, STEADY
	}

	private static final float[] BAND_REACH = {1f, 0.55f, 0.22f};
	private static final float[] BAND_WEIGHT = {0.5f, 0.34f, 0.3f};
	private static final int STRIPS = 24;

	private final NumberSetting warnBelow = add(new NumberSetting("Warn Below", "Health (in half hearts) where it starts", 7, 2, 20, 1));
	private final BoolSetting absorption = add(new BoolSetting("Count Absorption", "Golden hearts count as health", true));
	private final EnumSetting<Pulse> pulse = add(new EnumSetting<>("Pulse", "Heartbeat, slow breathing, or a steady glow", Pulse.HEARTBEAT));
	private final NumberSetting intensity = add(new NumberSetting("Intensity", "How strong the glow is", 0.7, 0.2, 1, 0.05));
	private final NumberSetting reach = add(new NumberSetting("Reach", "How far in from the edges it goes", 0.16, 0.06, 0.35, 0.01));
	private final BoolSetting edgeLine = add(new BoolSetting("Edge Line", "A thin bright line right at the edge", true));
	private final BoolSetting themed = add(new BoolSetting("Theme Color", "Use your theme's accent instead of Glow Color", false));
	private final ColorSetting glow = add(new ColorSetting("Glow Color", "Colour of the glow", 0xFFFF2A4A));

	private float level, danger, phase;
	private long lastFrame;

	public LowHealthWarningModule() {
		super("Low Health Warning", "The screen edges pulse red when your health is low", Category.COSMETIC);
	}

	@Override
	protected void onEnable() {
		level = 0;
		phase = 0;
		lastFrame = 0;
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		if (now - lastFrame > 250_000_000L) {
			level = 0;
			phase = 0;
		}
		lastFrame = now;
		boolean dead = mc.player == null || mc.player.isDeadOrDying();
		float want = 0;
		if (!dead && !mc.player.isSpectator() && !mc.player.getAbilities().invulnerable) {
			float health = mc.player.getHealth() + (absorption.get() ? mc.player.getAbsorptionAmount() : 0);
			float limit = warnBelow.get().floatValue();
			if (health < limit) {
				want = 1;
				danger = Math.max(0, Math.min(1, (limit - health) / limit));
			}
		}
		float rate = dead ? 12 : want > level ? 5 : 2.6f;
		level += (want - level) * (1 - (float) Math.exp(-rate * dt));
		if (level < 0.003f && want == 0) {
			level = 0;
			phase = 0;
			return;
		}
		float bpm = 64 + 86 * danger;
		if (pulse.is(Pulse.BREATH)) bpm *= 0.5f;
		phase = (phase + dt * bpm / 60) % 1;
		float beat = beat();
		float shown = level * level * (3 - 2 * level);
		float strength = intensity.get().floatValue() * shown * (0.45f + 0.55f * danger);
		int w = context.guiWidth(), h = context.guiHeight();
		float depth = reach.get().floatValue() * Math.min(w, h) * (1 + 0.12f * beat);
		int rgb = (themed.get() ? Theme.accent() : glow.get()) & 0xFFFFFF;
		for (int i = 0; i < BAND_REACH.length; i++) frame(context, w, h, depth * BAND_REACH[i], rgb, strength * BAND_WEIGHT[i] * (0.6f + 0.4f * beat));
		if (edgeLine.get()) {
			float line = Math.max(2, depth * 0.07f);
			int bright = mix(rgb, 0xFFFFFF, 0.5f);
			frame(context, w, h, line, bright, intensity.get().floatValue() * shown * (0.2f + 0.55f * beat));
		}
	}

	private float beat() {
		if (pulse.is(Pulse.STEADY)) return 0.35f;
		if (pulse.is(Pulse.BREATH)) return 0.5f - 0.5f * (float) Math.cos(Math.PI * 2 * phase);
		return Math.min(1, bump(phase, 0) + 0.62f * bump(phase, 0.2f));
	}

	private static float bump(float at, float center) {
		float d = Math.abs(at - center);
		d = Math.min(d, 1 - d) / 0.065f;
		return (float) Math.exp(-d * d);
	}

	private static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return r << 16 | g << 8 | bl;
	}

	/** A glow fading inwards from all four edges. */
	private static void frame(GuiGraphics context, int w, int h, float depth, int rgb, float alpha) {
		int a = Math.round(255 * Math.max(0, Math.min(1, alpha)));
		if (depth < 1 || a == 0) return;
		int edge = a << 24 | rgb, clear = rgb;
		int d = Math.max(1, Math.round(depth));
		context.fillGradient(0, 0, w, d, edge, clear);
		context.fillGradient(0, h - d, w, h, clear, edge);
		// Sides: thin strips, each a little fainter going inwards
		for (int i = 0; i < STRIPS; i++) {
			int x0 = d * i / STRIPS, x1 = d * (i + 1) / STRIPS;
			if (x1 <= x0) continue;
			int sa = Math.round(a * (1 - (i + 0.5f) / STRIPS));
			int c = sa << 24 | rgb;
			context.fill(x0, 0, x1, h, c);
			context.fill(w - x1, 0, w - x0, h, c);
		}
	}
}
