package dev.femboyware.mixin;
//? if >=26.1 {
/*import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;

// Menu skin cosmetics are not ported to 26.x yet: its skin renderer changed.
@Mixin(PlayerModel.class)
public abstract class GuiSkinRendererMixin {
}
*///?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.CosmeticsModule;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cosmetics on the 3D skins in menus (the main menu's, the phone's Roblox profile): the head and
 * leg hidden as they should be, and the Korblox leg, horns and watch drawn on, in the same pose.
 * Optional, so a version where this renderer changed just goes without.
 */
@Mixin(GuiSkinRenderer.class)
public abstract class GuiSkinRendererMixin extends PictureInPictureRenderer<GuiSkinRenderState> {
	protected GuiSkinRendererMixin(MultiBufferSource.BufferSource bufferSource) {
		super(bufferSource);
	}

	private static final String RENDER_TO_BUFFER = "Lnet/minecraft/client/model/player/PlayerModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V";

	@Inject(method = "renderToTexture(Lnet/minecraft/client/gui/render/state/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
		at = @At(value = "INVOKE", target = RENDER_TO_BUFFER), require = 0)
	private void femboyware$hideParts(GuiSkinRenderState state, PoseStack matrices, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		CosmeticsModule cosmetics = FemboyWare.getModules().get(CosmeticsModule.class);
		matrices.pushPose();
		cosmetics.fitSkin(matrices);
		cosmetics.prepareSkin(state.playerModel());
	}

	@Inject(method = "renderToTexture(Lnet/minecraft/client/gui/render/state/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
		at = @At(value = "INVOKE", target = RENDER_TO_BUFFER, shift = At.Shift.AFTER), require = 0)
	private void femboyware$drawCosmetics(GuiSkinRenderState state, PoseStack matrices, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		FemboyWare.getModules().get(CosmeticsModule.class).decorateSkin(state.playerModel(), matrices, bufferSource, 0xF000F0);
		matrices.popPose();
	}
}
//?}
