package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.RenderUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A combo count that pops up over whoever you're hitting: it bounces with each hit, gets hotter in
 * colour as it climbs, and calls out milestones. Missing the window resets it.
 */
public class ComboCounterModule extends FxModule {
	private static final int[] MILESTONES = {3, 5, 8, 12, 16, 20};
	private static final String[] CALLOUTS = {"Nice", "Great", "Amazing", "Insane", "Godlike", "UwU"};

	private final NumberSetting window = add(new NumberSetting("Window", "Seconds between hits before the combo resets", 1.5, 0.5, 5, 0.1));
	private final NumberSetting minimum = add(new NumberSetting("Minimum", "Combo needed before it shows", 2, 1, 10, 1));
	private final NumberSetting size = add(new NumberSetting("Size", "How big the text is", 1, 0.4, 3, 0.05));
	private final BoolSetting callouts = add(new BoolSetting("Callouts", "Words at milestones, like Nice and Insane", true));
	private final Palette palette = palette(0xFFFFB6E1, 0xFFFF3D8B);

	private LivingEntity target;
	private int combo;
	private long lastHit;

	public ComboCounterModule() {
		super("Combo Counter", "Counts your combo over whoever you're hitting", Category.TARGETS);
	}

	@Override
	public String getInfo() {
		return combo >= minimum.getInt() && active() ? "x" + combo : null;
	}

	@Override
	protected void onDisable() {
		target = null;
		combo = 0;
	}

	@Override
	public void onPostAttack(Entity entity) {
		if (!(entity instanceof LivingEntity living)) return;
		combo = living == target && active() ? combo + 1 : 1;
		target = living;
		lastHit = System.nanoTime();
	}

	private boolean active() {
		return (System.nanoTime() - lastHit) / 1e9 < window.get();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (target == null || combo < minimum.getInt() || target.isRemoved()) return;
		float age = (float) ((System.nanoTime() - lastHit) / 1e9);
		// Lingers half a second after the window closes, fading
		float fade = 1 - Math.max(0, (age - window.getFloat()) / 0.5f);
		// Text with almost no alpha draws fully opaque, so stop just before that
		if (fade < 0.04f) return;
		// A springy bounce on each hit
		float bounce = 1 + 0.6f * (float) Math.exp(-age * 9) * (float) Math.cos(age * 30);
		float heat = Math.min(1, (combo - minimum.getInt()) / 12f);
		int color = FxRender.fade(FxRender.opaque(palette.at(heat)), fade);
		Vec3 top = target.getPosition(tickDelta()).add(0, target.getBbHeight() + 0.6, 0);
		RenderUtil.drawText(event, "x" + combo, top, color, 0.8f * size.getFloat() * bounce);

		if (!callouts.get()) return;
		String callout = null;
		for (int i = 0; i < MILESTONES.length; i++) if (combo >= MILESTONES[i]) callout = CALLOUTS[i];
		if (callout != null) RenderUtil.drawText(event, callout + "!", top.add(0, 0.35 * size.get(), 0), FxRender.fade(0xFFFFFFFF, fade), 0.45f * size.getFloat());
	}
}
