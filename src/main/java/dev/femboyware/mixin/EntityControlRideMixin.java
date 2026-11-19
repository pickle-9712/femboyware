package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.module.movement.EntityControlModule;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Entity Control: horse jumps always fully charged. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(LocalPlayer.class)
public abstract class EntityControlRideMixin {
	@ModifyReturnValue(method = "getJumpRidingScale", at = @At("RETURN"), require = 0)
	private float femboyware$maxJump(float original) {
		return EntityControlModule.maxJump() ? 1.0f : original;
	}
}
