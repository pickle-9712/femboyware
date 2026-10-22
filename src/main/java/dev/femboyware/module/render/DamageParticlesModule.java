package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ColorUtil;
import dev.femboyware.util.RenderUtil;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Floating numbers showing how much damage (and healing) entities around you take. */
public class DamageParticlesModule extends Module {
	private record Popup(String text, Vec3d pos, int color, long start) {
	}

	private final NumberSetting range = add(new NumberSetting("Range", "Track entities this close", 24, 4, 64, 1));
	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds each number stays", 1.2, 0.3, 4, 0.1));
	private final NumberSetting scale = add(new NumberSetting("Scale", "Text size", 1, 0.3, 3, 0.05));
	private final BoolSetting healing = add(new BoolSetting("Healing", "Also show healing", true));
	private final ColorSetting damageColor = add(new ColorSetting("Damage", "Damage number colour", 0xFFFF5577));
	private final ColorSetting healColor = add(new ColorSetting("Heal", "Healing number colour", 0xFF55FF99)).visibleWhen(healing::get);

	private final Int2FloatMap health = new Int2FloatOpenHashMap();
	private final List<Popup> popups = new ArrayList<>();
	private final Random random = new Random();

	public DamageParticlesModule() {
		super("Damage Particles", "Shows damage numbers above entities", Category.COSMETIC);
	}

	@Override
	protected void onDisable() {
		health.clear();
		popups.clear();
	}

	@Override
	public void onTick() {
		double maxDistance = range.get() * range.get();
		Int2FloatMap seen = new Int2FloatOpenHashMap();
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || mc.player.squaredDistanceTo(entity) > maxDistance) continue;
			float now = living.getHealth() + living.getAbsorptionAmount();
			seen.put(entity.getId(), now);
			if (!health.containsKey(entity.getId())) continue;
			float delta = now - health.get(entity.getId());
			if (Math.abs(delta) < 0.05f || delta > 0 && !healing.get()) continue;

			Vec3d pos = entity.getEntityPos().add((random.nextDouble() - 0.5) * entity.getWidth(), entity.getHeight() * 0.8, (random.nextDouble() - 0.5) * entity.getWidth());
			String text = String.format(delta < 0 ? "%.1f" : "+%.1f", Math.abs(delta));
			popups.add(new Popup(text, pos, delta < 0 ? damageColor.get() : healColor.get(), System.currentTimeMillis()));
		}
		health.clear();
		health.putAll(seen);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.currentTimeMillis();
		double lifetime = duration.get() * 1000;
		for (Iterator<Popup> iterator = popups.iterator(); iterator.hasNext(); ) {
			Popup popup = iterator.next();
			double progress = (now - popup.start) / lifetime;
			if (progress >= 1) {
				iterator.remove();
				continue;
			}
			int alpha = (int) ((popup.color >>> 24) * (1 - progress * progress));
			Vec3d pos = popup.pos.add(0, progress * 0.8, 0);
			RenderUtil.drawText(event, popup.text, pos, ColorUtil.withAlpha(popup.color, Math.max(alpha, 8)), scale.getFloat() * 0.4f);
		}
	}
}
