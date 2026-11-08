package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.MoveUtil;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;

/**
 * Stops chosen effects counting down. In singleplayer the effect really lasts; on servers the
 * server still counts it down, so there it only freezes the timer you see.
 * Ported from Meteor Client's PotionSaver (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class PotionSaverModule extends Module {
	private final StringSetting effects = add(new StringSetting("Effects", "Comma separated effect ids to freeze",
		"strength, absorption, resistance, fire_resistance, speed, haste, regeneration, water_breathing, saturation, luck, slow_falling, dolphins_grace, conduit_power, hero_of_the_village"));
	private final BoolSetting onlyWhenStationary = add(new BoolSetting("Only Stationary", "Only freeze effects while you stand still", false));

	private Set<MobEffect> frozen = Set.of();
	private String parsedFrom;

	public PotionSaverModule() {
		super("Potion Saver", "Stops potion effects from running out", Category.PLAYER);
	}

	/** Called from StatusEffectInstanceMixin before an effect ticks down. */
	public boolean shouldFreeze(Holder<MobEffect> effect) {
		if (!isEnabled() || mc.player == null) return false;
		if (!effects.get().equals(parsedFrom)) {
			frozen = parseEffects(effects.get());
			parsedFrom = effects.get();
		}
		if (onlyWhenStationary.get() && MoveUtil.isMoving()) return false;
		return frozen.contains(effect.value());
	}

	private static Set<MobEffect> parseEffects(String text) {
		Set<MobEffect> result = new HashSet<>();
		for (String part : text.split(",")) {
			if (part.isBlank()) continue;
			Identifier id = Identifier.tryParse(part.trim().toLowerCase());
			if (id != null) BuiltInRegistries.MOB_EFFECT.getOptional(id).ifPresent(result::add);
		}
		return result;
	}
}
