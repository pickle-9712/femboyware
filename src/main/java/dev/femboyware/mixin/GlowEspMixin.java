package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.module.render.GlowEspModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Glow ESP: the entities it picked get the game's glowing outline. */
@Mixin(Minecraft.class)
public abstract class GlowEspMixin {
	@ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"), require = 0)
	private boolean femboyware$glowEsp(boolean original, Entity entity) {
		if (original) return true;
		GlowEspModule module = GlowEspModule.active();
		return module != null && module.glow(entity) != 0;
	}
}
