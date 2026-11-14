package dev.femboyware.module.tung;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Every hit goes "TUNG": a wooden drum and the word floating off them. Keep the combo going and
 * the last hit of it shouts "SAHUR!".
 */
public class TungHitsModule extends Module {
	private static final long LIFETIME = 700;

	private final NumberSetting combo = add(new NumberSetting("Combo", "Hits in a row before SAHUR!", 3, 2, 6, 1));
	private final NumberSetting volume = add(new NumberSetting("Volume", "How loud the drum is", 0.8, 0, 1, 0.05));
	private final BoolSetting text = add(new BoolSetting("Text", "TUNG and SAHUR! float off whoever you hit", true));

	private record Popup(Vec3 at, String word, boolean sahur, long start) {}

	private final List<Popup> popups = new ArrayList<>();
	private int hits;
	private int lastTarget = -1;
	private long lastHit;

	public TungHitsModule() {
		super("Tung Hits", "Every hit goes TUNG, the combo's last one SAHUR!", Category.COSMETIC);
	}

	@Override
	public void onPostAttack(Entity target) {
		if (!(target instanceof LivingEntity)) return;
		long now = System.currentTimeMillis();
		hits = target.getId() == lastTarget && now - lastHit < 1500 ? hits + 1 : 1;
		lastTarget = target.getId();
		lastHit = now;
		boolean sahur = hits % combo.getInt() == 0;
		float loud = volume.getFloat();
		if (loud > 0) {
			float pitch = sahur ? 0.7f : 0.95f + (hits % combo.getInt()) * 0.08f;
			mc.level.playLocalSound(target.getX(), target.getY() + 1, target.getZ(), SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, loud, pitch, false);
			if (sahur) mc.level.playLocalSound(target.getX(), target.getY() + 1, target.getZ(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.PLAYERS, loud * 0.7f, 1.4f, false);
		}
		if (text.get()) {
			// A little to one side each time so they don't stack
			double side = (hits % 3 - 1) * 0.35;
			popups.add(new Popup(target.position().add(side, target.getBbHeight() + 0.3, 0), sahur ? "SAHUR!" : "TUNG", sahur, now));
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.currentTimeMillis();
		popups.removeIf(p -> now - p.start > LIFETIME);
		for (Popup popup : popups) {
			float age = (now - popup.start) / (float) LIFETIME;
			int alpha = Math.max(4, (int) (255 * (1 - age * age)));
			int color = alpha << 24 | (popup.sahur ? Theme.accent() & 0xFFFFFF : 0xFFE0B0);
			RenderUtil.drawText(event, popup.word, popup.at.add(0, age * 0.6, 0), color, popup.sahur ? 0.8f : 0.55f);
		}
	}

	@Override
	protected void onDisable() {
		popups.clear();
	}
}
