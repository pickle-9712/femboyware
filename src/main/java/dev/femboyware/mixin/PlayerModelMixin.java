package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.CosmeticsModule;
import dev.femboyware.module.render.EmotesModule;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cosmetics: headless hides the head, Korblox hides the real right leg. Set on every pose, since the model is shared by all players. */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void femboyware$cosmetics(AvatarRenderState state, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		CosmeticsModule cosmetics = FemboyWare.getModules().get(CosmeticsModule.class);
		PlayerModel model = (PlayerModel) (Object) this;
		model.head.visible = !cosmetics.hidesHead(state);
		if (cosmetics.replacesRightLeg(state)) model.rightLeg.visible = false;
		FemboyWare.getModules().get(EmotesModule.class).pose(model, state);
	}
}
