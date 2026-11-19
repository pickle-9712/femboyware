package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.module.movement.EntityControlModule;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Entity Control: every mount counts as saddled. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(Mob.class)
public abstract class EntityControlMobMixin {
	@ModifyReturnValue(method = "isSaddled", at = @At("RETURN"), require = 0)
	private boolean femboyware$spoofSaddle(boolean original) {
		return original || EntityControlModule.spoofSaddle();
	}
}
