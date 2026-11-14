package dev.femboyware.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.util.HeldBlades;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The pose a held blade's drawn with, while a hand renderer has marked one (see HeldBlades). */
@Mixin(ItemStackRenderState.class)
public class ItemStackRenderStateMixin {
	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private void femboyware$bladePose(PoseStack matrices, SubmitNodeCollector queue, int light, int overlay, int outline, CallbackInfo ci) {
		HeldBlades.capture(matrices);
		// Sword Models draws its own in place of a held sword
		if (dev.femboyware.module.render.SwordModelsModule.replace(matrices, queue, light)) ci.cancel();
	}
}
