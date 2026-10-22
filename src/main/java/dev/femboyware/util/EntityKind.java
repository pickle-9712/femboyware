package dev.femboyware.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/** The groups Esp and Tracers let you pick between and colour separately. */
public enum EntityKind {
	PLAYER, ANIMAL, WATER_ANIMAL, MONSTER, AMBIENT, MISC;

	public static EntityKind of(Entity entity) {
		if (entity instanceof PlayerEntity) return PLAYER;
		return switch (entity.getType().getSpawnGroup()) {
			case CREATURE -> ANIMAL;
			case WATER_AMBIENT, WATER_CREATURE, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> WATER_ANIMAL;
			case MONSTER -> MONSTER;
			case AMBIENT -> AMBIENT;
			default -> MISC;
		};
	}

	/** Red up close, through yellow, to green 60 blocks away and beyond. */
	public static int distanceColor(double distance) {
		double percent = Math.min(distance / 60, 1);
		int r = percent < 0.5 ? 255 : 255 - (int) (255 * (percent - 0.5) / 0.5);
		int g = percent < 0.5 ? (int) (255 * percent / 0.5) : 255;
		return 0xFF000000 | r << 16 | g << 8;
	}
}
