package dev.femboyware.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.util.HeldBlades;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Held blades in third person, for effects that follow where the blade is drawn (see HeldBlades). */
@Mixin(ItemInHandLayer.class)
public class ItemInHandLayerMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"))
	private void femboyware$bladeBegin(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm, PoseStack matrices,
									   SubmitNodeCollector queue, int light, CallbackInfo ci) {
		if (state instanceof AvatarRenderState avatar) HeldBlades.begin(avatar.id, arm, stack, false);
	}

	@Inject(method = "submitArmWithItem", at = @At("RETURN"))
	private void femboyware$bladeEnd(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm, PoseStack matrices,
									 SubmitNodeCollector queue, int light, CallbackInfo ci) {
		HeldBlades.end();
	}
}
