package dev.femboyware.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.SwingAnimationsModule;
import dev.femboyware.module.render.ViewModelModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class HeldItemRendererMixin {
	//? if >=26.1 {
	/*private static final String ARM_WITH_ITEM = "submitArmWithItem";
	*///?} else {
	private static final String ARM_WITH_ITEM = "renderArmWithItem";
	//?}

	@Shadow
	private float mainHandHeight;
	@Shadow
	private float oMainHandHeight;
	@Shadow
	private float offHandHeight;
	@Shadow
	private float oOffHandHeight;
	@Shadow
	private ItemStack mainHandItem;
	@Shadow
	private ItemStack offHandItem;

	/**
	 * View Model wraps the whole method in its own push/pop instead of hooking a call inside it,
	 * so it keeps working when other mods (like ViewModel) rewrite the method body.
	 */
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

	/** Swing Animations: items stay fully raised instead of bobbing down when switched. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void femboyware$noEquip(CallbackInfo ci) {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(SwingAnimationsModule.class).noEquip()) return;
		Minecraft mc = Minecraft.getInstance();
		mainHandItem = mc.player.getMainHandItem();
		offHandItem = mc.player.getOffhandItem();
		mainHandHeight = 1f;
		oMainHandHeight = 1f;
		offHandHeight = 1f;
		oOffHandHeight = 1f;
	}
}
