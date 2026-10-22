package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.SwingAnimationsModule;
import dev.femboyware.module.render.ViewModelModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {
	@Shadow
	private float equipProgressMainHand;
	@Shadow
	private float lastEquipProgressMainHand;
	@Shadow
	private float equipProgressOffHand;
	@Shadow
	private float lastEquipProgressOffHand;
	@Shadow
	private ItemStack mainHand;
	@Shadow
	private ItemStack offHand;

	/**
	 * View Model wraps the whole method in its own push/pop instead of hooking a call inside it,
	 * so it keeps working when other mods (like ViewModel) rewrite the method body.
	 */
	@Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
	private void femboyware$viewModel(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item,
									  float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
		matrices.push();
		if (!FemboyWare.isEnabled(ViewModelModule.class)) return;
		Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
		FemboyWare.getModules().get(ViewModelModule.class).apply(matrices, arm);
	}

	@Inject(method = "renderFirstPersonItem", at = @At("RETURN"))
	private void femboyware$viewModelPop(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item,
										 float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
		matrices.pop();
	}

	/** Swing Animations: items stay fully raised instead of bobbing down when switched. */
	@Inject(method = "updateHeldItems", at = @At("TAIL"))
	private void femboyware$noEquip(CallbackInfo ci) {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(SwingAnimationsModule.class).noEquip()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		mainHand = mc.player.getMainHandStack();
		offHand = mc.player.getOffHandStack();
		equipProgressMainHand = 1f;
		lastEquipProgressMainHand = 1f;
		equipProgressOffHand = 1f;
		lastEquipProgressOffHand = 1f;
	}
}
