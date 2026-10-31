package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.FurryModule;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render's Player Armor hides every player's armour; Furry hides just yours, so the fur shows. */
@Mixin(ArmorFeatureRenderer.class)
public class ArmorFeatureRendererMixin {
	@Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/BipedEntityRenderState;FF)V",
		at = @At("HEAD"), cancellable = true)
	private void femboyware$hideArmor(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, BipedEntityRenderState state, float yaw, float pitch, CallbackInfo ci) {
		if (FemboyWare.getModules() == null || !(state instanceof PlayerEntityRenderState player)) return;
		if (FemboyWare.getModules().get(NoRenderModule.class).playerArmor() || FemboyWare.getModules().get(FurryModule.class).hidesArmor(player)) ci.cancel();
	}
}
