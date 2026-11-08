package dev.femboyware.module.effects;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.combat.AuraModule;
import dev.femboyware.module.combat.AutoAnchorModule;
import dev.femboyware.module.combat.AutoCrystalModule;
import dev.femboyware.module.combat.LegitAuraModule;
import dev.femboyware.setting.BoolSetting;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Base for effects drawn on whoever you're fighting: the targets of Aura, Legit Aura, Auto Crystal
 * and Auto Anchor, and whatever you last hit. Effects fade in when something becomes a target and
 * fade out when it stops being one.
 */
public abstract class TargetFxModule extends FxModule {
	/** Something you hit stays a target this long. */
	private static final long HIT_MEMORY = 4000;
	/** Fades take a quarter of a second. */
	private static final float FADE_SPEED = 4;

	private BoolSetting aura;
	private BoolSetting legitAura;
	private BoolSetting autoCrystal;
	private BoolSetting autoAnchor;
	private BoolSetting lastHit;
	private BoolSetting throughWalls;

	private LivingEntity hit;
	private long hitTime;
	private final Map<LivingEntity, Float> fades = new LinkedHashMap<>();
	private long lastFrame;

	protected TargetFxModule(String name, String description) {
		super(name, description, Category.TARGETS);
	}

	/** Adds the target source settings at this point in the settings list; call once, from a field. */
	protected BoolSetting targetSettings() {
		aura = add(new BoolSetting("Aura", "Show on Aura's target", true));
		legitAura = add(new BoolSetting("Legit Aura", "Show on Legit Aura's target", true));
		autoCrystal = add(new BoolSetting("Auto Crystal", "Show on Auto Crystal's target", true));
		autoAnchor = add(new BoolSetting("Auto Anchor", "Show on Auto Anchor's target", true));
		lastHit = add(new BoolSetting("Last Hit", "Show on whatever you hit for a few seconds, for the legit modules and plain fighting", true));
		throughWalls = add(new BoolSetting("Through Walls", "Draw over terrain", false));
		return throughWalls;
	}

	protected boolean throughWalls() {
		return throughWalls.get();
	}

	@Override
	public void onPostAttack(Entity target) {
		if (target instanceof LivingEntity living) {
			hit = living;
			hitTime = System.currentTimeMillis();
		}
	}

	@Override
	protected void onDisable() {
		fades.clear();
		hit = null;
		lastFrame = 0;
	}

	/** Everything targeted right now. */
	protected Set<LivingEntity> targets() {
		Set<LivingEntity> targets = new LinkedHashSet<>();
		if (aura.get()) addTarget(targets, FemboyWare.getModules().get(AuraModule.class).getTarget());
		if (legitAura.get()) addTarget(targets, FemboyWare.getModules().get(LegitAuraModule.class).getTarget());
		AutoCrystalModule crystal = FemboyWare.getModules().get(AutoCrystalModule.class);
		if (autoCrystal.get() && crystal.isEnabled()) addTarget(targets, crystal.getTarget());
		AutoAnchorModule anchor = FemboyWare.getModules().get(AutoAnchorModule.class);
		if (autoAnchor.get() && anchor.isEnabled()) addTarget(targets, anchor.getTarget());
		if (lastHit.get() && hit != null && System.currentTimeMillis() - hitTime < HIT_MEMORY) addTarget(targets, hit);
		return targets;
	}

	private static void addTarget(Set<LivingEntity> targets, LivingEntity entity) {
		if (entity != null && entity.isAlive() && !entity.isRemoved() && entity != mc.player) targets.add(entity);
	}

	/**
	 * Each target to draw this frame with how faded in it is, 0 to 1. Call once per frame: entities
	 * that stopped being targets stay in, fading out, until they're gone.
	 */
	protected Map<LivingEntity, Float> fadedTargets() {
		long now = System.nanoTime();
		float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;

		Set<LivingEntity> targets = targets();
		for (LivingEntity target : targets) fades.putIfAbsent(target, 0f);
		Iterator<Map.Entry<LivingEntity, Float>> iterator = fades.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<LivingEntity, Float> entry = iterator.next();
			LivingEntity entity = entry.getKey();
			float fade = entry.getValue() + (targets.contains(entity) ? delta : -delta) * FADE_SPEED;
			if (fade <= 0 && !targets.contains(entity) || entity.isRemoved() || entity.level() != mc.level) {
				iterator.remove();
				continue;
			}
			entry.setValue(Math.min(1, Math.max(0, fade)));
		}
		return fades;
	}

	/** Smooth 0 to 1 ease for fades, so effects don't pop. */
	protected static float ease(float fade) {
		return fade * fade * (3 - 2 * fade);
	}
}
