package dev.femboyware.mixin;

//? if <26.1 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.module.render.HandModelModule;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 3D Hand: your arm gripping held items, and empty-hand arms reaching to the shoulder. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(ItemInHandRenderer.class)
public abstract class HandModelRendererMixin {
	@WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"), require = 0)
	private void femboyware$armWithItem(ItemInHandRenderer renderer, LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose,
										SubmitNodeCollector collector, int light, Operation<Void> original, @Local(argsOnly = true) InteractionHand hand) {
		if (HandModelModule.active() != null) HandModelModule.renderArm(pose, entity, stack, context, collector, light, hand == InteractionHand.MAIN_HAND);
		original.call(renderer, entity, stack, context, pose, collector, light);
	}

	@WrapOperation(method = {"renderPlayerArm", "renderMapHand"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V"), require = 0)
	private void femboyware$longRightArm(AvatarRenderer<?> renderer, PoseStack pose, SubmitNodeCollector collector, int light, Identifier skin, boolean sleeve, Operation<Void> original) {
		original.call(renderer, pose, collector, light, skin, sleeve);
		if (HandModelModule.active() == null) return;
		pose.pushPose();
		HandModelModule.shoulder(pose, true);
		original.call(renderer, pose, collector, light, skin, sleeve);
		pose.popPose();
	}

	@WrapOperation(method = {"renderPlayerArm", "renderMapHand"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V"), require = 0)
	private void femboyware$longLeftArm(AvatarRenderer<?> renderer, PoseStack pose, SubmitNodeCollector collector, int light, Identifier skin, boolean sleeve, Operation<Void> original) {
		original.call(renderer, pose, collector, light, skin, sleeve);
		if (HandModelModule.active() == null) return;
		pose.pushPose();
		HandModelModule.shoulder(pose, false);
		original.call(renderer, pose, collector, light, skin, sleeve);
		pose.popPose();
	}
}
//?} else {
/*import org.spongepowered.asm.mixin.Mixin;

/^* 3D Hand is 1.21.11 only: 26.x rewrote first-person rendering. *^/
@Mixin(net.minecraft.client.Minecraft.class)
public abstract class HandModelRendererMixin {
}
*///?}
