package dev.femboyware.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.FurryModule;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render's Player Armor hides every player's armour; Furry hides just yours, so the fur shows. */
@Mixin(HumanoidArmorLayer.class)
public class ArmorFeatureRendererMixin {
	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
		at = @At("HEAD"), cancellable = true)
	private void femboyware$hideArmor(PoseStack matrices, SubmitNodeCollector queue, int light, HumanoidRenderState state, float yaw, float pitch, CallbackInfo ci) {
		if (FemboyWare.getModules() == null || !(state instanceof AvatarRenderState player)) return;
		if (FemboyWare.getModules().get(NoRenderModule.class).playerArmor() || FemboyWare.getModules().get(FurryModule.class).hidesArmor(player)) ci.cancel();
	}
}
