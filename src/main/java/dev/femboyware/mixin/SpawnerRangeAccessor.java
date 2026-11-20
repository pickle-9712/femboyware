package dev.femboyware.mixin;

import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Spawner Nametags: how close a player has to be for the spawner to run. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(BaseSpawner.class)
public interface SpawnerRangeAccessor {
	@Accessor("requiredPlayerRange")
	int femboyware$requiredPlayerRange();
}
