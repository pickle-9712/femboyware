package dev.femboyware.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.ViewModelModule;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * View Model wraps the whole first person item method in its own push/pop instead of hooking a call
 * inside it, so it keeps working when other mods (like ViewModel) rewrite the method body.
 */
//? if >=26.3 {
/*@Mixin(net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class)
*///?} else {
@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
//?}
public class HeldItemRendererMixin {
	//? if >=26.1 {
	/*private static final String ARM_WITH_ITEM = "submitArmWithItem";
	*///?} else {
	private static final String ARM_WITH_ITEM = "renderArmWithItem";
	//?}

	//? if >=26.3 {
	/*@Inject(method = ARM_WITH_ITEM, at = @At("HEAD"))
	private void femboyware$viewModel(net.minecraft.client.renderer.state.level.PlayerRenderState playerState, net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState state,
									  float partialTicks, float xRot, InteractionHand hand, float attack, ItemStack item, float inverseArmHeight,
									  PoseStack matrices, SubmitNodeCollector queue, int light, CallbackInfo ci) {
		matrices.pushPose();
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (!FemboyWare.isEnabled(ViewModelModule.class) || player == null) return;
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
		FemboyWare.getModules().get(ViewModelModule.class).apply(matrices, arm);
	}

	@Inject(method = ARM_WITH_ITEM, at = @At("RETURN"))
	private void femboyware$viewModelPop(net.minecraft.client.renderer.state.level.PlayerRenderState playerState, net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState state,
										 float partialTicks, float xRot, InteractionHand hand, float attack, ItemStack item, float inverseArmHeight,
										 PoseStack matrices, SubmitNodeCollector queue, int light, CallbackInfo ci) {
		matrices.popPose();
	}
	*///?} else {
	@Inject(method = ARM_WITH_ITEM, at = @At("HEAD"))
	private void femboyware$viewModel(AbstractClientPlayer player, float tickProgress, float pitch, InteractionHand hand, float swingProgress, ItemStack item,
									  float equipProgress, PoseStack matrices, SubmitNodeCollector queue, int light, CallbackInfo ci) {
		matrices.pushPose();
		if (!FemboyWare.isEnabled(ViewModelModule.class)) return;
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
		FemboyWare.getModules().get(ViewModelModule.class).apply(matrices, arm);
	}

	@Inject(method = ARM_WITH_ITEM, at = @At("RETURN"))
	private void femboyware$viewModelPop(AbstractClientPlayer player, float tickProgress, float pitch, InteractionHand hand, float swingProgress, ItemStack item,
										 float equipProgress, PoseStack matrices, SubmitNodeCollector queue, int light, CallbackInfo ci) {
		matrices.popPose();
	}
	//?}
}
